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
    fun twoHalvesKeepTheirColors() {
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

    /** Horizontal bands of color: band k uses palette[k % n]. */
    private fun banded(cols: Int, rows: Int, n: Int, band: Int = 6): Pair<IntArray, IntArray> {
        val buckets = IntArray(cols * rows) { i -> (i / cols / band) % n }
        return IntArray(cols * rows) { palette[buckets[it]] } to buckets
    }

    private fun game(cols: Int, rows: Int, n: Int, seed: Int = 1, target: Int = 13, trayRows: Int = 3): SandGame {
        val (g, b) = banded(cols, rows, n)
        return SandGame(cols, rows, g, b, palette.copyOf(n), trayCols = 8, trayRows = trayRows, targetBoxes = target, seed = seed)
    }

    @Test
    fun loneGrainFallsToTheFloor() {
        val g = IntArray(16 * 10); val k = IntArray(16 * 10) { -1 }
        g[2] = palette[0]; k[2] = 0
        val game = SandGame(16, 10, g, k, palette.copyOf(1), trayCols = 8, targetBoxes = 1)
        repeat(100) { game.sandStep() }
        val x = (0 until 16).single { game.cell(it, 9) != SandGame.EMPTY } // may drift sideways
        assertEquals(palette[0], game.cell(x, 9))
        assertEquals(0, game.kind[9 * 16 + x])
        assertEquals(1, game.grid.count { it != SandGame.EMPTY })
    }

    @Test
    fun removingTheBottomGrainSettlesFromTheBottomUp() {
        // A solid block: take one bottom grain and the gap should climb (or be filled from the
        // side) one grain per tick, rather than the whole column dropping at once.
        val rows = 12
        val g = IntArray(16 * rows) { palette[0] }; val k = IntArray(16 * rows)
        val game = SandGame(16, rows, g, k, palette.copyOf(1), trayCols = 8, targetBoxes = 1)
        game.grid[(rows - 1) * 16 + 5] = SandGame.EMPTY
        game.kind[(rows - 1) * 16 + 5] = SandGame.NONE
        repeat(3) {
            game.sandStep()
            assertEquals("exactly one gap at any time", 1, game.grid.count { it == SandGame.EMPTY })
            assertTrue("gap must not reach the top within 3 ticks", (0 until 16).all { x -> game.cell(x, 0) != SandGame.EMPTY })
        }
    }

    @Test
    fun grainsRollOffSlopes() {
        // A tower of sand one grain wide must collapse sideways into a pile.
        val rows = 20; val cols = 24
        val g = IntArray(cols * rows); val k = IntArray(cols * rows) { -1 }
        for (y in 0 until rows) { g[y * cols + 12] = palette[0]; k[y * cols + 12] = 0 }
        val game = SandGame(cols, rows, g, k, palette.copyOf(1), trayCols = 8, targetBoxes = 1)
        repeat(600) { game.sandStep() }
        val tallest = (0 until cols).maxOf { x -> (0 until rows).count { y -> game.cell(x, y) != SandGame.EMPTY } }
        assertTrue("tower should have slumped, tallest column = $tallest", tallest <= 6)
        assertEquals(rows, game.grid.count { it != SandGame.EMPTY })
    }

    @Test
    fun cliffAvalanchesToASlopeShallowerThan45Degrees() {
        // Half the box full of sand, half empty: the cliff must collapse and run out further than
        // it is tall, like real sand flowing down a slope (a strict 45-degree rule can't do that).
        val rows = 30; val cols = 90
        val g = IntArray(cols * rows); val k = IntArray(cols * rows) { -1 }
        for (y in 0 until rows) for (x in 0 until 25) { g[y * cols + x] = palette[0]; k[y * cols + x] = 0 }
        val game = SandGame(cols, rows, g, k, palette.copyOf(1), trayCols = 9, targetBoxes = 1, seed = 3)
        repeat(3000) { game.sandStep() }
        val height = IntArray(cols) { x -> (0 until rows).count { y -> game.cell(x, y) != SandGame.EMPTY } }
        val toe = (0 until cols).first { height[it] == 0 }
        val drop = height[0]
        assertEquals(25 * rows, height.sum())
        assertTrue("run-out $toe should exceed height $drop (angle < 45 deg)", toe > drop * 1.15)
        assertTrue("but sand shouldn't flow flat like water: run-out $toe, height $drop", toe < drop * 2.6)
    }

    @Test
    fun boxesAreSmallerThanCellsAndNeverOverlap() {
        val game = game(48, 48, 2)
        assertTrue(game.size < game.cellSize)
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
    fun allBoxesExistUpFrontAndCoverEverything() {
        val game = game(48, 48, 3)
        assertTrue(game.boxes.size in 3..13)
        assertTrue("top row starts empty", game.boxes.all { it.y > game.slotTop(0) + 0.5f })
        for (c in 0 until 3) {
            val cap = game.boxes.filter { it.color == c }.sumOf { it.capacity }
            assertTrue("color $c under-covered", cap >= game.remainingByColor[c])
        }
        val before = game.boxes.size
        repeat(60 * 3) { game.update(1f / 60f) } // nothing touched: no new boxes ever appear
        assertEquals(before, game.boxes.size)
    }

    @Test
    fun boxOnlyCollectsItsOwnColor() {
        val (g, k) = banded(48, 48, 3)
        val game = SandGame(48, 48, g, k, palette.copyOf(3), trayCols = 8, trayRows = 3, targetBoxes = 3, seed = 4)
        game.boxes.forEachIndexed { i, b -> game.dragBoxTo(b, game.slotLeft(i * 3), game.slotTop(0)) }
        repeat(60 * 6) { game.update(1f / 60f); game.events.clear() }
        assertTrue(game.grainsCollected > 0)
        for (b in game.boxes) for (i in 0 until b.fill) assertEquals(game.palette[b.color], b.grains[i])
    }

    /**
     * A planning bot plays whole games on the tray's cell grid: it picks a box whose color is
     * reachable, walks gaps to clear its route to the top row, and lets it pull.
     * Every game must be winnable and conserve sand.
     */
    @Test
    fun botCanClearTheWholePicture() {
        for (seed in 1..4) {
            val n = 2 + seed % 2
            val game = game(48, 54, n, seed = seed, target = 13)
            val bot = SlotBot(game)
            var frames = 0
            while (!game.won && frames < 60 * 1200) {
                if (frames % 4 == 0) bot.act()
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

/** Test helper: plays by moving boxes one cell at a time, using only legal drags. */
class SlotBot(private val game: SandGame) {
    private val w = game.trayCols
    private val h = game.trayRows
    private var active: SandGame.Box? = null

    private fun slotOf(b: SandGame.Box): Int {
        val cx = (b.centerX / game.cellSize).toInt().coerceIn(0, w - 1)
        val cy = ((b.centerY - game.playTop) / game.cellSize).toInt().coerceIn(0, h - 1)
        return cy * w + cx
    }
    private fun live() = game.boxes.filter { it.state != SandGame.BoxState.POPPING }
    private fun reach(b: SandGame.Box, col: Int) = game.grainsInReach(b.color, game.slotLeft(col) + game.size / 2, game.slotTop(0))
    private fun bestReach(b: SandGame.Box) = (0 until w).maxOf { reach(b, it) }

    /** Moves [b] one cell to [to] (must be adjacent and free). */
    private fun step(b: SandGame.Box, to: Int) {
        game.beginDrag(b)
        game.dragBoxTo(b, game.slotLeft(to % w), game.slotTop(to / w))
        game.endDrag(b)
    }

    /** BFS over cells; [passable] decides which cells may be crossed. */
    private fun path(from: Int, goal: (Int) -> Boolean, passable: (Int) -> Boolean): List<Int>? {
        val prev = IntArray(w * h) { -2 }
        prev[from] = -1
        val q = ArrayDeque(listOf(from))
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            if (c != from && goal(c)) {
                val out = ArrayList<Int>(); var k = c
                while (k != from) { out += k; k = prev[k] }
                return out.reversed()
            }
            val x = c % w; val y = c / w
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val nb = ny * w + nx
                if (prev[nb] == -2 && passable(nb)) { prev[nb] = c; q += nb }
            }
        }
        return null
    }

    fun act() {
        val boxes = live()
        val occ = HashMap<Int, SandGame.Box>()
        for (b in boxes) occ[slotOf(b)] = b

        val a = active
        if (a == null || a !in boxes || a.reserved >= a.capacity || bestReach(a) == 0) {
            active = boxes.filter { it.reserved < it.capacity }.maxByOrNull { bestReach(it) }
                ?.takeIf { bestReach(it) > 0 }
            // Park top-row boxes that can't reach anything so they don't block the base.
            for (b in boxes) if (b !== active && slotOf(b) < w && !game.isColorReachable(b.color)) {
                path(slotOf(b), { it >= w && it !in occ }, { it !in occ })?.let { step(b, it.first()); return }
            }
            return
        }
        val here = slotOf(a)
        val bestCol = (0 until w).maxBy { reach(a, it) }
        if (here == bestCol) return // in position, let it pull
        val route = path(here, { it == bestCol }, { true }) ?: return
        val next = route.first()
        if (occ[next] == null) { step(a, next); return }
        // Walk the nearest gap back to the blocked cell, one box at a time (15-puzzle style).
        val chain = path(next, { it !in occ && it != here }, { it != here }) ?: return
        val gap = chain.last()
        val prevCell = if (chain.size >= 2) chain[chain.size - 2] else next
        occ[prevCell]?.let { step(it, gap) }
    }
}
