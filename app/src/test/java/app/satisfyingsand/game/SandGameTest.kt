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

    private fun game(cols: Int, rows: Int, n: Int, boxLen: Int, seed: Int = 1, target: Int = 12): SandGame {
        val (g, b) = banded(cols, rows, n)
        return SandGame(cols, rows, g, b, palette.copyOf(n), boxLen = boxLen, trayBoxes = 4, targetBoxes = target, seed = seed)
    }

    @Test
    fun loneGrainFallsToTheFloor() {
        val g = IntArray(12 * 10); val k = IntArray(12 * 10) { -1 }
        g[2] = palette[0]; k[2] = 0
        val game = SandGame(12, 10, g, k, palette.copyOf(1), boxLen = 3, targetBoxes = 1)
        repeat(20) { game.sandStep() }
        val x = (0 until 12).single { game.cell(it, 9) != SandGame.EMPTY } // may drift sideways
        assertEquals(palette[0], game.cell(x, 9))
        assertEquals(0, game.kind[9 * 12 + x])
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
    fun allBoxesExistUpFrontAndCoverEverything() {
        val game = game(24, 24, 3, boxLen = 4, target = 12)
        assertTrue(game.boxes.size in 3..12)
        for (c in 0 until 3) {
            val cap = game.boxes.filter { it.color == c }.sumOf { it.capacity }
            assertTrue("colour $c under-covered", cap >= game.remainingByColor[c])
        }
        val before = game.boxes.size
        repeat(60 * 3) { game.update(1f / 60f) } // nothing touched: no new boxes ever appear
        assertEquals(before, game.boxes.size)
    }

    @Test
    fun boxOnlyCollectsItsOwnColour() {
        val (g, k) = banded(24, 24, 3)
        val game = SandGame(24, 24, g, k, palette.copyOf(3), boxLen = 4, trayBoxes = 4, targetBoxes = 3, seed = 4)
        // Three boxes, one per colour: line them up along the base, they can't overlap.
        game.boxes.forEachIndexed { i, b -> game.dragBoxTo(b, i * 8f + 2f, game.playTop) }
        repeat(60 * 6) { game.update(1f / 60f); game.events.clear() }
        assertTrue(game.grainsCollected > 0)
        for (b in game.boxes) for (i in 0 until b.fill) assertEquals(game.palette[b.color], b.grains[i])
    }

    /**
     * A planning bot plays whole games on the tray's slot grid: it picks a box whose colour is
     * reachable, clears blockers out of its route to the top row, and parks finished boxes.
     * Every game must be winnable and conserve sand.
     */
    @Test
    fun botCanClearTheWholePicture() {
        for (seed in 1..6) {
            val n = 2 + seed % 2
            val game = game(24, 30, n, boxLen = 4, seed = seed, target = 14)
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

/** Test helper: plays by moving boxes one slot at a time, using only legal drags. */
class SlotBot(private val game: SandGame) {
    private val w = (game.worldWidth / game.size).toInt()
    private val h = game.trayBoxes
    private var active: SandGame.Box? = null

    private fun slotOf(b: SandGame.Box) = Math.round(b.x / game.size) + w * Math.round((b.y - game.playTop) / game.size)
    private fun live() = game.boxes.filter { it.state != SandGame.BoxState.POPPING }
    private fun reach(b: SandGame.Box, col: Int) = game.grainsInReach(b.color, col * game.size + game.size / 2, game.playTop)
    private fun bestReach(b: SandGame.Box) = (0 until w).maxOf { reach(b, it) }

    /** Moves [b] one slot to [to] (must be adjacent and free). */
    private fun step(b: SandGame.Box, to: Int) {
        val tx = (to % w) * game.size
        val ty = game.playTop + (to / w) * game.size
        game.beginDrag(b)
        game.dragBoxTo(b, tx, ty)
        game.endDrag(b)
    }

    /** BFS over slots; [passable] decides which slots may be crossed. */
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
            // Park a finished/idle box that's in the top row so it doesn't block the base.
            active = boxes.filter { it.reserved < it.capacity }.maxByOrNull { bestReach(it) }
                ?.takeIf { bestReach(it) > 0 }
            for (b in boxes) if (b !== active && slotOf(b) < w && game.isColorReachable(b.color).not()) {
                path(slotOf(b), { it >= w && it !in occ }, { it !in occ })?.let { step(b, it.first()); return }
            }
            return
        }
        val here = slotOf(a)
        val bestCol = (0 until w).maxBy { reach(a, it) }
        if (here == bestCol) return // in position, let it pull
        // Route to the best top-row slot, ignoring other boxes; then clear the first blocker.
        val route = path(here, { it == bestCol }, { true }) ?: return
        val next = route.first()
        val blocker = occ[next]
        if (blocker == null) { step(a, next); return }
        // Walk the nearest gap back to the blocked cell, one box at a time (15-puzzle style).
        val chain = path(next, { it !in occ && it != here }, { it != here }) ?: return
        val gap = chain.last()
        val prevCell = if (chain.size >= 2) chain[chain.size - 2] else next
        occ[prevCell]?.let { step(it, gap) }
    }
}
