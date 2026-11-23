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

    private val palette = intArrayOf(0xFFCC9944.toInt(), 0xFF3366CC.toInt(), 0xFF22AA55.toInt())

    /** Horizontal bands of colour: band k uses palette[k % n]. */
    private fun banded(cols: Int, rows: Int, n: Int, band: Int = 4): Pair<IntArray, IntArray> {
        val buckets = IntArray(cols * rows) { i -> (i / cols / band) % n }
        return IntArray(cols * rows) { palette[buckets[it]] } to buckets
    }

    private fun game(cols: Int, rows: Int, n: Int, boxLen: Int, seed: Int = 1, maxBoxes: Int = 6): SandGame {
        val (g, b) = banded(cols, rows, n)
        return SandGame(cols, rows, g, b, palette.copyOf(n), boxLen = boxLen, trayBoxes = 4, maxBoxes = maxBoxes, seed = seed)
    }

    @Test
    fun loneGrainFallsToTheFloor() {
        val g = IntArray(12 * 10); val k = IntArray(12 * 10) { -1 }
        g[2] = palette[0]; k[2] = 0
        val game = SandGame(12, 10, g, k, palette.copyOf(1), boxLen = 3, maxBoxes = 1)
        repeat(20) { game.sandStep() }
        assertEquals(palette[0], game.cell(2, 9))
        assertEquals(0, game.kind[9 * 12 + 2])
        assertEquals(1, game.grid.count { it != SandGame.EMPTY })
    }

    @Test
    fun boxesNeverOverlapWhenDragged() {
        val game = game(24, 24, 2, boxLen = 4)
        val a = game.boxes[0]
        val b = game.boxes[1]
        game.dragBoxTo(a, b.x, b.y) // try to drop it right on top of another box
        for (p in game.boxes) for (q in game.boxes) if (p !== q) {
            val overlap = p.x < q.x + game.size - 0.01f && p.x + game.size > q.x + 0.01f &&
                p.y < q.y + game.size - 0.01f && p.y + game.size > q.y + 0.01f
            assertFalse("boxes ${p.id} and ${q.id} overlap", overlap)
        }
    }

    @Test
    fun boxOnlyCollectsItsOwnColour() {
        val game = game(24, 24, 3, boxLen = 4, seed = 4, maxBoxes = 1)
        val box = game.boxes.single()
        assertTrue("spawned colour should be reachable", game.isColorReachable(box.color))
        assertTrue(game.dragBoxTo(box, 10f, game.playTop))
        repeat(60 * 4) { game.update(1f / 60f); game.events.clear() }
        val boxesWithSand = game.boxes.filter { it.fill > 0 }
        assertTrue(game.grainsCollected > 0)
        for (b in boxesWithSand) for (i in 0 until b.fill) assertEquals(game.palette[b.color], b.grains[i])
    }

    /** A bot plays whole games with free movement; they must always be winnable and conserve sand. */
    @Test
    fun botCanClearTheWholePicture() {
        for (seed in 1..6) {
            val n = 2 + seed % 2
            val game = game(24, 30, n, boxLen = 4, seed = seed)
            val rnd = kotlin.random.Random(seed)
            val stall = HashMap<Int, Int>()
            val detour = HashMap<Int, Pair<Float, Int>>()
            var frames = 0
            while (!game.won && frames < 60 * 900) {
                if (frames % 3 == 0) for (b in game.boxes.toList()) {
                    if (b.state == SandGame.BoxState.POPPING) continue
                    val d = detour[b.id]
                    val (tx, ty) = if (d != null && d.second > 0) {
                        detour[b.id] = d.first to d.second - 1
                        d.first to (game.playTop + game.worldHeight) / 2
                    } else if (b.reserved < game.capacity && game.isColorReachable(b.color)) {
                        var bestX = b.x; var best = -1
                        var x = 0f
                        while (x <= game.worldWidth - game.size) {
                            val c = game.grainsInReach(b.color, x + game.size / 2, game.playTop)
                            if (c > best) { best = c; bestX = x }
                            x += 1f
                        }
                        bestX to game.playTop
                    } else {
                        b.x to game.worldHeight
                    }
                    val moved = game.dragBoxTo(b, tx, ty)
                    val atTarget = kotlin.math.abs(b.x - tx.coerceIn(0f, game.worldWidth - game.size)) < 0.1f &&
                        kotlin.math.abs(b.y - ty.coerceIn(game.playTop, game.worldHeight - game.size)) < 0.1f
                    if (!moved && !atTarget) {
                        val s = (stall[b.id] ?: 0) + 1
                        stall[b.id] = s
                        if (s > 5) {
                            detour[b.id] = rnd.nextFloat() * (game.worldWidth - game.size) to 15
                            stall[b.id] = 0
                        }
                    } else stall[b.id] = 0
                }
                game.update(1f / 60f)
                game.events.clear()
                frames++
            }
            assertTrue("seed $seed not won after $frames frames, remaining=${game.gridRemaining}", game.won)
            assertEquals(0, game.gridRemaining)
            assertTrue(game.remainingByColor.all { it == 0 })
            assertEquals(game.totalGrains, game.grainsCollected)
        }
    }
}
