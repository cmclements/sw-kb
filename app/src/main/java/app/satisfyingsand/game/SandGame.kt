package app.satisfyingsand.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * All game rules, independent of Android so they can be unit tested on the JVM.
 *
 * Coordinates are in "grain units" (1 unit = 1 sand grain):
 *  - The picture occupies x in [0, cols), y in [0, rows).
 *  - Below it, after a thin lip of [BASE_GAP], is an open tray where boxes move freely.
 *
 * Every grain belongs to a colour bucket (palette index). Every box has a colour too and only
 * collects grains of that colour that are within [pullRadius] of the middle of its top edge.
 */
class SandGame(
    val cols: Int,
    val rows: Int,
    initialGrains: IntArray,
    initialBuckets: IntArray,
    val palette: IntArray,
    /** Box side length in grains. */
    val boxLen: Int,
    /** Tray height, in box lengths. */
    val trayBoxes: Int = 4,
    val maxBoxes: Int = 6,
    seed: Int = 1,
) {
    val capacity: Int = boxLen * boxLen
    val pullRadius: Float = boxLen * PULL_RADIUS_BOXES
    val size: Float = boxLen.toFloat()

    init {
        require(initialGrains.size == cols * rows && initialBuckets.size == cols * rows)
        require(trayBoxes >= 2 && cols >= boxLen * 2)
    }

    /** Colour of each grain (opaque ARGB) or [EMPTY]. Row-major. */
    val grid: IntArray = initialGrains.copyOf()
    /** Colour bucket of each grain, or [NONE]. Moves together with [grid]. */
    val kind: IntArray = initialBuckets.copyOf()
    /** The untouched picture, for the win screen. */
    val original: IntArray = initialGrains.copyOf()
    val totalGrains: Int = grid.count { it != EMPTY }
    var gridRemaining: Int = totalGrains
        private set
    /** Grains of each colour still in the picture. */
    val remainingByColor = IntArray(palette.size).also { r -> for (k in initialBuckets) if (k >= 0) r[k]++ }

    val playTop: Float get() = rows + BASE_GAP
    val worldWidth: Float get() = cols.toFloat()
    val worldHeight: Float get() = playTop + trayBoxes * boxLen

    enum class BoxState { SPAWNING, IDLE, POPPING }

    inner class Box(val id: Int, val color: Int, var x: Float, var y: Float) {
        /** Grain colours in arrival order (bottom-up, left-to-right inside the box). */
        val grains = IntArray(capacity)
        var fill = 0
        var reserved = 0
        var state = BoxState.SPAWNING
        /** 0..1 progress of SPAWNING/POPPING animations (may start negative = delay). */
        var anim = 0f
        var pullAcc = 0f
        var pulling = false

        val swatch: Int get() = palette[color]
        val fillFraction: Float get() = fill.toFloat() / capacity
        val centerX: Float get() = x + size / 2
        val centerY: Float get() = y + size / 2
        /** Where suction originates: the middle of the box's top edge. */
        val mouthX: Float get() = x + size / 2
        val mouthY: Float get() = y

        fun slotCenterX(i: Int): Float {
            val s = (size - 2 * BOX_PAD) / boxLen
            return x + BOX_PAD + ((i % boxLen) + 0.5f) * s
        }
        fun slotCenterY(i: Int): Float {
            val s = (size - 2 * BOX_PAD) / boxLen
            return y + BOX_PAD + ((boxLen - 1 - i / boxLen) + 0.5f) * s
        }
    }

    class FlyingGrain(
        val color: Int, val sx: Float, val sy: Float, val box: SandGame.Box,
        val duration: Float, val swirl: Float,
    ) {
        var t = 0f
        var x = sx
        var y = sy
    }

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, val life: Float) {
        var age = 0f
    }

    class Ring(val x: Float, val y: Float, val color: Int) { var age = 0f }

    sealed class Event {
        data class Pop(val x: Float, val y: Float) : Event()
        data class StartPull(val boxId: Int) : Event()
        data object Win : Event()
    }

    val boxes = ArrayList<Box>()
    val flying = ArrayList<FlyingGrain>()
    val particles = ArrayList<Particle>()
    val rings = ArrayList<Ring>()
    val events = ArrayList<Event>()

    var moves = 0
        private set
    var elapsed = 0f
        private set
    var won = false
        private set
    var boxesPopped = 0
        private set
    var grainsCollected = 0
        private set

    private val rnd = Random(seed)
    private var nextBoxId = 1
    private var stepAcc = 0f
    private var stepParity = false
    private val pendingSpawns = ArrayList<Float>()
    private var dragStartX = 0f
    private var dragStartY = 0f

    /** Fraction of the picture drained so far, 0..1. */
    val progress: Float get() = if (totalGrains == 0) 1f else 1f - gridRemaining.toFloat() / totalGrains

    init {
        repeat(maxBoxes) { spawnBox(immediate = true) }
    }

    // ---------------------------------------------------------------- queries

    fun cell(x: Int, y: Int): Int = grid[y * cols + x]

    /** Hit-test (topmost first) with a little slop so boxes are easy to grab. */
    fun boxAtWorld(wx: Float, wy: Float, slop: Float = 0f): Box? = boxes.lastOrNull {
        wx >= it.x - slop && wx < it.x + size + slop && wy >= it.y - slop && wy < it.y + size + slop
    }

    /** Can a box sitting anywhere along the base currently reach any grain of this colour? */
    fun isColorReachable(color: Int): Boolean {
        val minY = floor(playTop - pullRadius).toInt().coerceAtLeast(0)
        for (y in rows - 1 downTo minY) {
            val dy = playTop - (y + 0.5f)
            if (dy > pullRadius) break
            val row = y * cols
            for (x in 0 until cols) if (kind[row + x] == color) return true
        }
        return false
    }

    /** Number of grains of [color] within the pull radius of a mouth at ([mx],[my]). */
    fun grainsInReach(color: Int, mx: Float, my: Float): Int {
        var n = 0
        forEachInReach(mx, my) { idx -> if (kind[idx] == color) n++ }
        return n
    }

    private inline fun forEachInReach(mx: Float, my: Float, f: (Int) -> Unit) {
        val r = pullRadius
        val y0 = floor(my - r).toInt().coerceAtLeast(0)
        val y1 = min(rows - 1, ceil(my + r).toInt())
        val x0 = floor(mx - r).toInt().coerceAtLeast(0)
        val x1 = min(cols - 1, ceil(mx + r).toInt())
        for (y in y0..y1) {
            val dy = y + 0.5f - my
            for (x in x0..x1) {
                val dx = x + 0.5f - mx
                if (dx * dx + dy * dy <= r * r) f(y * cols + x)
            }
        }
    }

    // ---------------------------------------------------------------- input

    fun beginDrag(box: Box) {
        dragStartX = box.x
        dragStartY = box.y
    }

    /**
     * Moves [box] toward the top-left position ([tx],[ty]), sliding around other boxes and the
     * tray walls. Returns true if it moved at all.
     */
    fun dragBoxTo(box: Box, tx: Float, ty: Float): Boolean {
        if (box.state == BoxState.POPPING || won) return false
        val gx = tx.coerceIn(0f, worldWidth - size)
        val gy = ty.coerceIn(playTop, worldHeight - size)
        val startX = box.x
        val startY = box.y
        val dist = max(abs(gx - box.x), abs(gy - box.y))
        val steps = max(1, ceil(dist / 0.25f).toInt())
        val stepX = (gx - box.x) / steps
        val stepY = (gy - box.y) / steps
        for (i in 0 until steps) {
            val nx = box.x + stepX
            if (stepX != 0f && !overlapsAny(box, nx, box.y)) box.x = nx
            val ny = box.y + stepY
            if (stepY != 0f && !overlapsAny(box, box.x, ny)) box.y = ny
        }
        return box.x != startX || box.y != startY
    }

    fun endDrag(box: Box) {
        if (abs(box.x - dragStartX) + abs(box.y - dragStartY) > 0.5f) moves++
    }

    private fun overlapsAny(self: Box, x: Float, y: Float): Boolean {
        for (b in boxes) {
            if (b === self || b.state == BoxState.POPPING) continue
            if (x < b.x + size - EPS && x + size > b.x + EPS && y < b.y + size - EPS && y + size > b.y + EPS) return true
        }
        return false
    }

    // ---------------------------------------------------------------- simulation

    fun update(dtRaw: Float) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        if (!won) elapsed += dt

        stepAcc += dt
        var steps = 0
        while (stepAcc >= SAND_STEP && steps < 4) {
            sandStep()
            stepAcc -= SAND_STEP
            steps++
        }
        if (steps == 4) stepAcc = 0f

        updateBoxes(dt)
        updateFlying(dt)
        updateParticles(dt)
        updateSpawns(dt)

        if (!won && gridRemaining == 0 && flying.isEmpty()) {
            won = true
            events += Event.Win
            var delay = 0f
            for (b in boxes) if (b.state != BoxState.POPPING) {
                b.state = BoxState.POPPING
                b.anim = -delay
                delay += 0.25f
            }
        }
    }

    /** One tick of falling sand: straight down, otherwise sometimes slide diagonally. */
    fun sandStep() {
        stepParity = !stepParity
        for (y in rows - 2 downTo 0) {
            val row = y * cols
            for (i in 0 until cols) {
                val x = if (stepParity) i else cols - 1 - i
                val idx = row + x
                if (grid[idx] == EMPTY) continue
                val below = idx + cols
                if (grid[below] == EMPTY) {
                    move(idx, below)
                    continue
                }
                if (rnd.nextFloat() > SLIDE_CHANCE) continue
                val dir = if (rnd.nextBoolean()) 1 else -1
                for (d in intArrayOf(dir, -dir)) {
                    if (x + d !in 0 until cols) continue
                    if (grid[below + d] == EMPTY && grid[idx + d] == EMPTY) {
                        move(idx, below + d)
                        break
                    }
                }
            }
        }
    }

    private fun move(from: Int, to: Int) {
        grid[to] = grid[from]; kind[to] = kind[from]
        grid[from] = EMPTY; kind[from] = NONE
    }

    private fun updateBoxes(dt: Float) {
        val popped = ArrayList<Box>()
        val it = boxes.iterator()
        while (it.hasNext()) {
            val b = it.next()
            when (b.state) {
                BoxState.SPAWNING -> {
                    b.anim += dt / SPAWN_TIME
                    if (b.anim >= 1f) { b.anim = 1f; b.state = BoxState.IDLE }
                }
                BoxState.POPPING -> {
                    b.anim += dt / POP_SWELL_TIME
                    if (b.anim >= 1f) { popped += b; it.remove() }
                    continue
                }
                BoxState.IDLE -> Unit
            }

            val wasPulling = b.pulling
            b.pulling = !won && pull(b, dt)
            if (b.pulling && !wasPulling) events += Event.StartPull(b.id)

            // A box is done when it's full, or when its colour is gone from the picture.
            val inFlight = b.reserved != b.fill
            val colorGone = remainingByColor[b.color] == 0
            if (!inFlight && (b.fill >= capacity || colorGone)) {
                b.state = BoxState.POPPING
                b.anim = 0f
            }
        }
        for (b in popped) burst(b)
    }

    /** Pulls matching grains within reach. Returns true if anything is in reach. */
    private fun pull(b: Box, dt: Float): Boolean {
        if (b.reserved >= capacity) { b.pullAcc = 0f; return false }
        val mx = b.mouthX
        val my = b.mouthY
        if (my - pullRadius > rows) { b.pullAcc = 0f; return false }
        b.pullAcc += dt * capacity / FILL_TIME
        var any = false
        while (b.reserved < capacity) {
            val idx = nearestMatching(b.color, mx, my) ?: break
            any = true
            if (b.pullAcc < 1f) break
            b.pullAcc -= 1f
            val color = grid[idx]
            grid[idx] = EMPTY
            kind[idx] = NONE
            gridRemaining--
            remainingByColor[b.color]--
            b.reserved++
            flying += FlyingGrain(
                color, idx % cols + 0.5f, idx / cols + 0.5f, b,
                duration = 0.38f + rnd.nextFloat() * 0.22f,
                swirl = (rnd.nextFloat() * 2f - 1f) * 2.2f,
            )
        }
        if (!any) b.pullAcc = 0f
        if (b.pullAcc > 1f) b.pullAcc = 1f
        return any
    }

    /** Nearest grain of [color] within the pull radius (ties broken randomly). */
    private fun nearestMatching(color: Int, mx: Float, my: Float): Int? {
        var best = -1
        var bestD = Float.MAX_VALUE
        forEachInReach(mx, my) { idx ->
            if (kind[idx] == color) {
                val dx = idx % cols + 0.5f - mx
                val dy = idx / cols + 0.5f - my
                val d = dx * dx + dy * dy + rnd.nextFloat() * 0.8f
                if (d < bestD) { bestD = d; best = idx }
            }
        }
        return if (best >= 0) best else null
    }

    private fun updateFlying(dt: Float) {
        val it = flying.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.t += dt / f.duration
            val tx = f.box.centerX
            val ty = f.box.centerY
            if (f.t >= 1f) {
                val b = f.box
                if (b.fill < capacity) b.grains[b.fill] = f.color
                b.fill++
                grainsCollected++
                it.remove()
                continue
            }
            // Accelerate into the centre of the box along a gentle swirl, like being sucked in.
            val e = f.t * f.t
            val dx = tx - f.sx
            val dy = ty - f.sy
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val bend = sin(f.t * PI.toFloat()) * f.swirl
            f.x = f.sx + dx * e + (-dy / len) * bend
            f.y = f.sy + dy * e + (dx / len) * bend
        }
    }

    private fun burst(b: Box) {
        boxesPopped++
        val cx = b.centerX
        val cy = b.centerY
        for (i in 0 until min(b.fill, capacity)) {
            val c = b.grains[i]
            val px = b.slotCenterX(i)
            val py = b.slotCenterY(i)
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val spd = 6f + rnd.nextFloat() * 18f
            particles += Particle(
                px, py,
                cos(ang) * spd + (px - cx) * 3f, sin(ang) * spd + (py - cy) * 3f - 14f,
                c, 0.6f + rnd.nextFloat() * 0.6f,
            )
        }
        rings += Ring(cx, cy, b.swatch)
        if (b.fill > 0) events += Event.Pop(cx, cy)
        if (!won) scheduleSpawns()
    }

    private fun updateParticles(dt: Float) {
        val pit = particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.age += dt
            if (p.age >= p.life) { pit.remove(); continue }
            p.vy += GRAVITY * dt
            p.vx *= (1f - 1.5f * dt)
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
        val rit = rings.iterator()
        while (rit.hasNext()) {
            val r = rit.next()
            r.age += dt
            if (r.age >= RING_TIME) rit.remove()
        }
    }

    // ---------------------------------------------------------------- box supply

    private fun liveBoxes() = boxes.filter { it.state != BoxState.POPPING }

    /** Grains of each colour not yet covered by a live box's free space. */
    private fun uncovered(): IntArray {
        val need = remainingByColor.copyOf()
        for (b in liveBoxes()) need[b.color] -= capacity - b.reserved
        return need
    }

    private fun scheduleSpawns() {
        val needBoxes = uncovered().sumOf { if (it > 0) (it + capacity - 1) / capacity else 0 }
        var room = min(needBoxes, maxBoxes - liveBoxes().size) - pendingSpawns.size
        while (room-- > 0) pendingSpawns += SPAWN_DELAY + pendingSpawns.size * 0.15f
    }

    /** True when no live box can currently reach any sand of its colour. */
    private fun isStuck(): Boolean {
        val live = liveBoxes()
        if (live.isEmpty()) return true
        return live.none { it.reserved < capacity && isColorReachable(it.color) }
    }

    private fun updateSpawns(dt: Float) {
        if (won) { pendingSpawns.clear(); return }
        for (i in pendingSpawns.indices) pendingSpawns[i] -= dt
        val ready = pendingSpawns.count { it <= 0f }
        if (ready > 0) {
            pendingSpawns.removeAll { it <= 0f }
            repeat(ready) { if (!spawnBox(immediate = false)) pendingSpawns += 0.3f }
        }
        if (pendingSpawns.isEmpty() && boxes.none { it.state == BoxState.POPPING }) {
            scheduleSpawns()
            // Rescue: if every box is waiting on buried colours, add one that can make progress now,
            // even past the usual limit.
            if (pendingSpawns.isEmpty() && isStuck() && liveBoxes().size < maxBoxes * 2) spawnBox(immediate = false)
        }
    }

    /**
     * Chooses a colour that still needs a box, favouring colours the player can reach right now,
     * weighted by how much of it is waiting.
     */
    private fun pickColor(): Int? {
        val need = uncovered()
        val candidates = need.indices.filter { need[it] > 0 }
        if (candidates.isEmpty()) return null
        val reachable = candidates.filter { isColorReachable(it) }
        val pool = reachable.ifEmpty { candidates }
        // Don't stack lots of boxes of one colour on the board when others are waiting.
        val live = liveBoxes()
        val weights = pool.map { c ->
            val onBoard = live.count { it.color == c }
            need[c].toFloat() / (1 + 2 * onBoard)
        }
        var r = rnd.nextFloat() * weights.sum()
        for (i in pool.indices) {
            r -= weights[i]
            if (r <= 0f) return pool[i]
        }
        return pool.last()
    }

    /** Places a new box of a needed colour in a free spot, as far from the picture as possible. */
    private fun spawnBox(immediate: Boolean): Boolean {
        val color = pickColor() ?: return true
        val spot = freeSpot() ?: return false
        val b = Box(nextBoxId++, color, spot.first, spot.second)
        if (immediate) { b.state = BoxState.IDLE; b.anim = 1f }
        boxes += b
        return true
    }

    private fun freeSpot(): Pair<Float, Float>? {
        val maxX = worldWidth - size
        val xs = (0..(maxX / (size / 2)).toInt()).map { it * size / 2 }.shuffled(rnd)
        var y = worldHeight - size
        while (y >= playTop + size * 0.5f) {
            for (x in xs) {
                val probe = Box(-1, 0, x, y)
                if (!overlapsAny(probe, x, y)) return x to y
            }
            y -= size / 2
        }
        return null
    }

    companion object {
        const val EMPTY = 0
        const val NONE = -1
        const val BASE_GAP = 0.6f
        const val BOX_PAD = 0.35f
        const val SAND_STEP = 1f / 60f
        const val SLIDE_CHANCE = 0.55f
        /** Pull radius, measured in box lengths from the middle of the box's top edge. */
        const val PULL_RADIUS_BOXES = 1.5f
        const val FILL_TIME = 1.1f        // seconds to fill a box when sand is plentiful
        const val SPAWN_TIME = 0.35f
        const val SPAWN_DELAY = 0.3f
        const val POP_SWELL_TIME = 0.2f
        const val RING_TIME = 0.5f
        const val GRAVITY = 70f           // grains / s^2 for burst particles
        private const val EPS = 0.001f
    }
}
