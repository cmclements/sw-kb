package app.satisfyingsand.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelatorTest {

    @Test
    fun solidImageBecomesOneBucket() {
        val px = IntArray(100 * 100) { 0xFF336699.toInt() }
        val r = Pixelator.pixelate(px, 100, 100, cols = 12, minRows = 8, maxRows = 16, buckets = 6)
        assertEquals(12, r.rows)
        assertEquals(1, r.palette.size)
        assertTrue(r.bucket.all { it == 0 })
    }

    @Test
    fun twoHalvesKeepTheirColours() {
        val w = 80; val h = 80
        val px = IntArray(w * h) { i -> if (i % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt() }
        val r = Pixelator.pixelate(px, w, h, cols = 8, minRows = 8, maxRows = 8, buckets = 4, jitter = 0f)
        assertEquals(2, r.palette.size)
        assertEquals(0xFFFF0000.toInt(), r.grains[0])
        assertEquals(0xFF0000FF.toInt(), r.grains[7])
        assertNotEquals(r.bucket[0], r.bucket[7])
    }

    @Test
    fun aspectIsClampedAndCropped() {
        val px = IntArray(400 * 100) { 0xFF00FF00.toInt() }
        val r = Pixelator.pixelate(px, 400, 100, cols = 24, minRows = 18, maxRows = 32)
        assertEquals(18, r.rows) // very wide image -> clamped to min rows, sides cropped
        assertEquals(24 * 18, r.grains.size)
    }

    @Test
    fun averagingIsGammaCorrect() {
        // Black/white checkerboard should average to a mid grey around sRGB 188, not 128.
        val px = IntArray(16 * 16) { i -> if ((i % 16 + i / 16) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val r = Pixelator.pixelate(px, 16, 16, cols = 1, minRows = 1, maxRows = 1, buckets = 1, jitter = 0f)
        val grey = r.grains[0] and 0xFF
        assertTrue("grey=$grey", grey in 180..195)
    }
}

class SandGameTest {

    private fun solid(cols: Int, rows: Int) = IntArray(cols * rows) { 0xFFCC9944.toInt() }

    @Test
    fun loneGrainFallsToTheFloor() {
        val g = IntArray(6 * 10)
        g[2] = 0xFFFFFFFF.toInt()
        val game = SandGame(6, 10, g, boxLen = 3, playRows = 2, maxBoxes = 1)
        repeat(20) { game.sandStep() }
        assertEquals(0xFFFFFFFF.toInt(), game.cell(2, 9))
        assertEquals(1, game.grid.count { it != SandGame.EMPTY })
    }

    @Test
    fun pathfindingRespectsOtherBoxes() {
        val game = SandGame(6, 6, solid(6, 6), boxLen = 3, playRows = 2, maxBoxes = 4)
        // 2 columns x 2 rows of cells; 36 grains / 9 per box -> 4 boxes fill the whole grid.
        assertEquals(4, game.boxes.size)
        val b = game.boxes.first()
        assertFalse(game.dragTo(b, (b.cx + 1) % 2, b.cy))
    }

    @Test
    fun dockedBoxDrainsAndPops() {
        val game = SandGame(12, 12, solid(12, 12), boxLen = 4, playRows = 3, maxBoxes = 4)
        val b = game.boxes.minBy { it.cy } // top-most box always has a free route to the base
        game.beginDrag(b)
        assertTrue(game.dragTo(b, b.cx, 0))
        game.endDrag(b)
        assertEquals(1, game.moves)
        var popped = false
        repeat(400) {
            game.update(1f / 60f)
            if (game.events.any { it is SandGame.Event.Pop }) popped = true
        }
        assertTrue("box should fill and pop", popped)
        assertTrue(game.gridRemaining < game.totalGrains)
    }

    /** A greedy bot plays the whole game; it must always be winnable and conserve sand. */
    @Test
    fun botCanClearTheWholePicture() {
        for (seed in 1..5) {
            val cols = 24; val rows = 30
            val game = SandGame(cols, rows, solid(cols, rows), boxLen = 4, playRows = 4, maxBoxes = 8, seed = seed)
            var frames = 0
            while (!game.won && frames < 60 * 600) {
                // Find a dock column whose reach still has sand and no box, and bring a box there.
                for (dock in 0 until game.playCols) {
                    if (game.boxAtCell(dock, 0) != null) continue
                    val hasSand = (0 until game.boxLen).any { dx ->
                        (game.rows - game.reach until game.rows).any { y -> game.cell(dock * game.boxLen + dx, y) != SandGame.EMPTY }
                    }
                    if (!hasSand) continue
                    val candidate = game.boxes
                        .filter { it.cy != 0 && it.state != SandGame.BoxState.POPPING }
                        .firstOrNull { game.dragTo(it, dock, 0) }
                    if (candidate != null) break
                }
                // Move full-reserved or idle docked boxes away from empty columns.
                for (b in game.boxes.toList()) {
                    if (b.cy != 0 || b.state == SandGame.BoxState.POPPING) continue
                    val hasSand = (0 until game.boxLen).any { dx ->
                        (game.rows - game.reach until game.rows).any { y -> game.cell(b.cx * game.boxLen + dx, y) != SandGame.EMPTY }
                    }
                    if (!hasSand && b.reserved == b.fill) {
                        for (cy in game.playRows - 1 downTo 1) {
                            if ((0 until game.playCols).any { cx -> game.dragTo(b, cx, cy) }) break
                        }
                    }
                }
                game.update(1f / 60f)
                game.events.clear()
                frames++
            }
            assertTrue("seed $seed not won after $frames frames, remaining=${game.gridRemaining}", game.won)
            assertEquals(0, game.gridRemaining)
            assertEquals(game.totalGrains, game.grainsCollected)
        }
    }
}
