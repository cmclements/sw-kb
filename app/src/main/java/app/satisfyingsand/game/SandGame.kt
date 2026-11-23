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
    /** Roughly how many boxes the whole picture is split into (all present from the start). */
    val targetBoxes: Int = 16,
    seed: Int = 1,
) {
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

    inner class Box(val id: Int, val color: Int, var x: Float, var y: Float, val capacity: Int) {
        /** Grain colours in arrival order (bottom-up, left-to-right inside the box). */
        val grains = IntArray(capacity)
        /** Slots per side of the square grain grid drawn inside the box. */
        val side: Int = ceil(sqrt(capacity.toFloat())).toInt().coerceAtLeast(1)
        val slotSize: Float get() = (size - 2 * BOX_PAD) / side
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

        fun slotCenterX(i: Int): Float = x + BOX_PAD + ((i % side) + 0.5f) * slotSize
        fun slotCenterY(i: Int): Float = y + BOX_PAD + ((side - 1 - i / side) + 0.5f) * slotSize
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
    private var dragStartX = 0f
    private var dragStartY = 0f

    /** Fraction of the picture drained so far, 0..1. */
    val progress: Float get() = if (totalGrains == 0) 1f else 1f - gridRemaining.toFloat() / totalGrains

    init {
        createAllBoxes()
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
     * tray walls. When a box almost clears a corner it is nudged sideways so it slips past
     * instead of catching. Returns true if it moved at all.
     */
    fun dragBoxTo(box: Box, tx: Float, ty: Float): Boolean {
        if (box.state == BoxState.POPPING || won) return false
        val gx = tx.coerceIn(0f, worldWidth - size)
        val gy = ty.coerceIn(playTop, worldHeight - size)
        val startX = box.x
        val startY = box.y
        val dist = max(abs(gx - box.x), abs(gy - box.y))
        val steps = max(1, ceil(dist / STEP).toInt())
        val stepX = (gx - box.x) / steps
        val stepY = (gy - box.y) / steps
        for (i in 0 until steps) {
            if (stepX != 0f) {
                val nx = box.x + stepX
                if (!overlapsAny(box, nx, box.y)) box.x = nx else nudge(box, horizontal = true, stepX)
            }
            if (stepY != 0f) {
                val ny = box.y + stepY
                if (!overlapsAny(box, box.x, ny)) box.y = ny else nudge(box, horizontal = false, stepY)
            }
        }
        return box.x != startX || box.y != startY
    }

    /** Corner assist: shift a blocked box a little along the other axis if that opens the way. */
    private fun nudge(box: Box, horizontal: Boolean, step: Float) {
        var off = STEP
        while (off <= size * CORNER_ASSIST) {
            for (sign in floatArrayOf(-1f, 1f)) {
                val d = sign * off
                val px = if (horizontal) box.x else (box.x + d).coerceIn(0f, worldWidth - size)
                val py = if (horizontal) (box.y + d).coerceIn(playTop, worldHeight - size) else box.y
                val ax = if (horizontal) px + step else px
                val ay = if (horizontal) py else py + step
                if (!overlapsAny(box, px, py) && !overlapsAny(box, ax, ay)) {
                    val shift = sign * min(off, STEP)
                    val sx = if (horizontal) box.x else box.x + shift
                    val sy = if (horizontal) box.y + shift else box.y
                    if (!overlapsAny(box, sx, sy)) { box.x = sx; box.y = sy }
                    return
                }
            }
            off += STEP
        }
    }

    fun endDrag(box: Box) {
        // Settle into line with the tray's slots when released close to one, so the tray
        // doesn't fill up with sliver gaps nothing can pass through.
        val sx = (Math.round(box.x / size) * size).coerceIn(0f, worldWidth - size)
        val sy = (playTop + Math.round((box.y - playTop) / size) * size).coerceIn(playTop, worldHeight - size)
        if (abs(sx - box.x) <= size * SNAP && abs(sy - box.y) <= size * SNAP && !overlapsAny(box, sx, sy)) {
            box.x = sx; box.y = sy
        }
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
                    // Falling grains sometimes drift sideways, so sand mixes instead of
                    // dropping in rigid columns.
                    if (rnd.nextFloat() < DRIFT_CHANCE) {
                        val d = if (rnd.nextBoolean()) 1 else -1
                        if (x + d in 0 until cols && grid[below + d] == EMPTY) {
                            move(idx, below + d)
                            continue
                        }
                    }
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
            if (!inFlight && (b.fill >= b.capacity || colorGone)) {
                b.state = BoxState.POPPING
                b.anim = 0f
            }
        }
        for (b in popped) burst(b)
    }

    /** Pulls matching grains within reach. Returns true if anything is in reach. */
    private fun pull(b: Box, dt: Float): Boolean {
        if (b.reserved >= b.capacity) { b.pullAcc = 0f; return false }
        val mx = b.mouthX
        val my = b.mouthY
        if (my - pullRadius > rows) { b.pullAcc = 0f; return false }
        b.pullAcc += dt * PULL_RATE
        var any = false
        while (b.reserved < b.capacity) {
            val idx = randomMatching(b.color, mx, my) ?: break
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

    private val candidates = ArrayList<Int>()

    /** A random grain of [color] anywhere inside the pull radius. */
    private fun randomMatching(color: Int, mx: Float, my: Float): Int? {
        candidates.clear()
        forEachInReach(mx, my) { idx -> if (kind[idx] == color) candidates += idx }
        return if (candidates.isEmpty()) null else candidates[rnd.nextInt(candidates.size)]
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
                if (b.fill < b.capacity) b.grains[b.fill] = f.color
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
        for (i in 0 until min(b.fill, b.capacity)) {
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

    // ---------------------------------------------------------------- box set-up

    /**
     * Splits every colour's sand into boxes up front: each colour gets enough boxes that, all
     * together, there are about [targetBoxes]. The boxes are shuffled into the tray below the top
     * row, leaving only a couple of gaps, so the colour you want is usually stuck behind others.
     */
    private fun createAllBoxes() {
        val colors = remainingByColor.indices.filter { remainingByColor[it] > 0 }
        if (colors.isEmpty()) return
        val slotsX = (worldWidth / size).toInt()
        // The top row (touching the picture) starts empty; boxes pack the rows below it.
        val slots = slotsX * (trayBoxes - 1)
        val target = targetBoxes.coerceIn(colors.size, max(colors.size, slots - 2))
        // Smallest per-box capacity that keeps the box count within target.
        var lo = 1
        var hi = totalGrains
        while (lo < hi) {
            val mid = (lo + hi) / 2
            val count = colors.sumOf { (remainingByColor[it] + mid - 1) / mid }
            if (count <= target) hi = mid else lo = mid + 1
        }
        val specs = ArrayList<Pair<Int, Int>>() // colour to capacity
        for (c in colors) {
            val n = remainingByColor[c]
            val k = (n + lo - 1) / lo
            val per = (n + k - 1) / k  // even split, so every box of a colour fills to 100%
            repeat(k) { specs += c to per }
        }
        specs.shuffle(rnd)
        val cells = (0 until slots).shuffled(rnd).take(specs.size)
        specs.forEachIndexed { i, (color, cap) ->
            val cell = cells[i]
            val b = Box(nextBoxId++, color, (cell % slotsX) * size, playTop + (1 + cell / slotsX) * size, cap)
            b.state = BoxState.SPAWNING
            b.anim = -0.04f * i // pop in one after another
            boxes += b
        }
    }

    companion object {
        const val EMPTY = 0
        const val NONE = -1
        const val BASE_GAP = 0.6f
        const val BOX_PAD = 0.35f
        const val SAND_STEP = 1f / 60f
        const val SLIDE_CHANCE = 0.55f
        /** Pull radius, measured in box lengths from the middle of the box's top edge. */
        const val PULL_RADIUS_BOXES = 0.9f
        const val PULL_RATE = 36f         // grains per second while sand is in reach
        const val DRIFT_CHANCE = 0.22f
        const val SPAWN_TIME = 0.35f
        const val POP_SWELL_TIME = 0.2f
        const val RING_TIME = 0.5f
        const val GRAVITY = 70f           // grains / s^2 for burst particles
        private const val EPS = 0.001f
        private const val STEP = 0.25f
        /** How far (in box lengths) a blocked box may be nudged to slip past a corner. */
        private const val CORNER_ASSIST = 0.35f
        /** Release within this fraction of a box of a slot and the box settles into it. */
        private const val SNAP = 0.2f
    }
}
