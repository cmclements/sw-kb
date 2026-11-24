package app.satisfyingsand.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * All game rules, independent of Android so they can be unit tested on the JVM.
 *
 * Coordinates are in "grain units" (1 unit = 1 sand grain):
 *  - The sand picture occupies x in [0, cols), y in [0, rows).
 *  - Below it, after a thin lip of [BASE_GAP], is a tray of [trayCols] x [trayRows] cells.
 *    Boxes are a little smaller than a cell ([BOX_FRACTION]) and move freely inside the tray.
 *
 * Every grain belongs to a colour bucket (palette index). Every box has a colour too and only
 * collects grains of that colour within [pullRadius] of the middle of its top edge.
 */
class SandGame(
    val cols: Int,
    val rows: Int,
    initialGrains: IntArray,
    initialBuckets: IntArray,
    val palette: IntArray,
    val trayCols: Int = 8,
    /** Tray height in cells, including the (initially empty) top row against the picture. */
    val trayRows: Int = 4,
    /** How many boxes the picture is split into (all present from the start). */
    val targetBoxes: Int = 21,
    seed: Int = 1,
) {
    init {
        require(initialGrains.size == cols * rows && initialBuckets.size == cols * rows)
        require(trayRows >= 2 && trayCols >= 2)
    }

    /** Side of one tray cell. */
    val cellSize: Float = cols.toFloat() / trayCols
    /** Side of a box: a bit smaller than a cell so boxes slip past each other easily. */
    val size: Float = cellSize * BOX_FRACTION
    private val inset: Float = (cellSize - size) / 2f
    val pullRadius: Float = cellSize * PULL_RADIUS_CELLS

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
    val worldHeight: Float get() = playTop + trayRows * cellSize

    /** Top-left of a box sitting neatly in tray cell ([cx],[cy]). */
    fun slotLeft(cx: Int): Float = cx * cellSize + inset
    fun slotTop(cy: Int): Float = playTop + cy * cellSize + inset

    enum class BoxState { SPAWNING, IDLE, POPPING }

    inner class Box(val id: Int, val color: Int, var x: Float, var y: Float, val capacity: Int) {
        /** Grain colours in arrival order (bottom-up, left-to-right inside the box). */
        val grains = IntArray(capacity)
        /** Grains per side of the square grid drawn inside the box. */
        val side: Int = ceil(sqrt(capacity.toFloat())).toInt().coerceAtLeast(1)
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
    private var tick = 0
    /** Tick on which each cell last received a grain, so a grain moves at most once per tick. */
    private val stamp = IntArray(cols * rows)
    /** Sideways momentum of each grain: -1, 0 or +1. Moves with the grain. */
    private val vel = ByteArray(cols * rows)
    private var dragStartX = 0f
    private var dragStartY = 0f
    private val candidates = IntArray(cols * rows)

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
        val mouthY = slotTop(0)
        val minY = floor(mouthY - pullRadius).toInt().coerceAtLeast(0)
        for (y in rows - 1 downTo minY) {
            if (mouthY - (y + 0.5f) > pullRadius) break
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
        // Settle into the nearest cell when released close to it, keeping lanes open.
        val cx = ((box.centerX / cellSize) - 0.5f).roundToInt().coerceIn(0, trayCols - 1)
        val cy = (((box.centerY - playTop) / cellSize) - 0.5f).roundToInt().coerceIn(0, trayRows - 1)
        val sx = slotLeft(cx)
        val sy = slotTop(cy)
        if (abs(sx - box.x) <= cellSize * SNAP && abs(sy - box.y) <= cellSize * SNAP && !overlapsAny(box, sx, sy)) {
            box.x = sx; box.y = sy
        }
        if (abs(box.x - dragStartX) + abs(box.y - dragStartY) > cellSize * 0.1f) moves++
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
        while (stepAcc >= SAND_STEP && steps < MAX_STEPS) {
            sandStep()
            stepAcc -= SAND_STEP
            steps++
        }
        if (steps == MAX_STEPS) stepAcc = 0f

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
                delay += 0.15f
            }
        }
    }

    /**
     * One tick of falling sand.
     *
     * Rows are scanned top-down and every grain moves at most once per tick, so when sand is
     * removed from the bottom the gap travels upward one grain at a time: the pile settles from
     * the bottom instead of a whole column dropping at once.
     *
     * Grains behave like round sand on a slope. Each grain carries a sideways momentum ([vel]).
     * A resting grain rolls diagonally down into a gap; on gentler slopes it slips sideways toward
     * a drop two cells over, which makes slopes settle at a realistic angle (between about 27 and
     * 45 degrees) instead of a rigid 45. Once moving, a grain keeps sliding the same way until
     * friction stops it, so sand avalanches down slopes in streams and the picture washes out.
     */
    fun sandStep() {
        tick++
        val leftFirst = tick and 1 == 0
        for (y in 0 until rows - 1) {
            val row = y * cols
            for (i in 0 until cols) {
                val x = if (leftFirst) i else cols - 1 - i
                val idx = row + x
                if (grid[idx] == EMPTY || stamp[idx] == tick) continue
                val below = idx + cols
                val v = vel[idx].toInt()

                if (grid[below] == EMPTY) {
                    if (rnd.nextFloat() >= FALL_CHANCE) continue
                    var to = below
                    // Falling grains keep some of their sideways motion; a few drift at random.
                    val d = if (v != 0) v else if (rnd.nextFloat() < DRIFT_CHANCE) (if (rnd.nextBoolean()) 1 else -1) else 0
                    if (d != 0 && x + d in 0 until cols && grid[below + d] == EMPTY &&
                        rnd.nextFloat() < (if (v != 0) MOMENTUM_FALL else 1f)
                    ) to = below + d
                    move(idx, to)
                    continue
                }

                // Resting on something: roll or slide downhill, preferring the way it's moving.
                val first = if (v != 0) v else if (rnd.nextBoolean()) 1 else -1
                var moved = false
                for (d in intArrayOf(first, -first)) {
                    if (d == -first && v != 0) break // momentum: don't reverse mid-slide
                    val nx = x + d
                    if (nx !in 0 until cols) continue
                    // Roll diagonally down.
                    if (grid[below + d] == EMPTY) {
                        if (rnd.nextFloat() < (if (v == d) 0.97f else ROLL_CHANCE)) {
                            move(idx, below + d); vel[below + d] = d.toByte(); moved = true
                        }
                        break
                    }
                    // Slip sideways toward a drop two cells over (gentler slopes still flow).
                    val nx2 = x + 2 * d
                    if (grid[idx + d] == EMPTY && nx2 in 0 until cols && grid[below + 2 * d] == EMPTY) {
                        if (rnd.nextFloat() < (if (v == d) SLIDE_MOMENTUM else SLIDE_CHANCE)) {
                            move(idx, idx + d); vel[idx + d] = d.toByte(); moved = true
                        }
                        break
                    }
                }
                // Friction: grains that couldn't move gradually lose their momentum.
                if (!moved && v != 0 && rnd.nextFloat() < FRICTION) vel[idx] = 0
            }
        }
    }

    private fun move(from: Int, to: Int) {
        grid[to] = grid[from]; kind[to] = kind[from]; vel[to] = vel[from]
        grid[from] = EMPTY; kind[from] = NONE; vel[from] = 0
        stamp[to] = tick
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

    /** Pulls matching grains from anywhere within reach. Returns true if any are in reach. */
    private fun pull(b: Box, dt: Float): Boolean {
        if (b.reserved >= b.capacity) { b.pullAcc = 0f; return false }
        val mx = b.mouthX
        val my = b.mouthY
        if (my - pullRadius > rows) { b.pullAcc = 0f; return false }
        var n = 0
        forEachInReach(mx, my) { idx -> if (kind[idx] == b.color) candidates[n++] = idx }
        if (n == 0) { b.pullAcc = 0f; return false }
        b.pullAcc += dt * PULL_RATE
        var take = min(min(b.pullAcc.toInt(), n), b.capacity - b.reserved)
        b.pullAcc -= take
        if (b.pullAcc > 2f) b.pullAcc = 2f
        // Random grains from anywhere in the radius (partial Fisher-Yates over the candidates).
        var left = n
        while (take-- > 0) {
            val k = rnd.nextInt(left)
            val idx = candidates[k]
            candidates[k] = candidates[--left]
            val color = grid[idx]
            grid[idx] = EMPTY
            kind[idx] = NONE
            vel[idx] = 0
            gridRemaining--
            remainingByColor[b.color]--
            b.reserved++
            flying += FlyingGrain(
                color, idx % cols + 0.5f, idx / cols + 0.5f, b,
                duration = 0.35f + rnd.nextFloat() * 0.25f,
                swirl = (rnd.nextFloat() * 2f - 1f) * pullRadius * 0.25f,
            )
        }
        return true
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
        val count = min(b.fill, b.capacity)
        val shown = min(count, MAX_BURST)
        for (j in 0 until shown) {
            val i = if (shown == count) j else rnd.nextInt(count)
            val c = b.grains[i]
            val px = b.x + rnd.nextFloat() * size
            val py = b.y + rnd.nextFloat() * size
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val spd = (0.5f + rnd.nextFloat() * 1.6f) * cellSize
            particles += Particle(
                px, py,
                cos(ang) * spd + (px - cx) * 3f, sin(ang) * spd + (py - cy) * 3f - cellSize,
                c, 0.6f + rnd.nextFloat() * 0.6f,
            )
        }
        rings += Ring(cx, cy, b.swatch)
        if (b.fill > 0) events += Event.Pop(cx, cy)
    }

    private fun updateParticles(dt: Float) {
        val gravity = GRAVITY_CELLS * cellSize
        val pit = particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.age += dt
            if (p.age >= p.life) { pit.remove(); continue }
            p.vy += gravity * dt
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
     * row, leaving only a few gaps, so the colour you want is usually stuck behind others.
     */
    private fun createAllBoxes() {
        val colors = remainingByColor.indices.filter { remainingByColor[it] > 0 }
        if (colors.isEmpty()) return
        val slots = trayCols * (trayRows - 1)
        val target = targetBoxes.coerceIn(colors.size, max(colors.size, slots - 1))
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
            val b = Box(nextBoxId++, color, slotLeft(cell % trayCols), slotTop(1 + cell / trayCols), cap)
            b.state = BoxState.SPAWNING
            b.anim = -0.03f * i // pop in one after another
            boxes += b
        }
    }

    companion object {
        const val EMPTY = 0
        const val NONE = -1
        const val BASE_GAP = 1.5f
        /** Box side as a fraction of a tray cell. */
        const val BOX_FRACTION = 0.84f
        /** Sand ticks run at 150 per second (several per frame). */
        const val SAND_STEP = 1f / 150f
        private const val MAX_STEPS = 6
        /** Chance per tick that an unsupported grain falls (a touch under 1 = uneven settling). */
        const val FALL_CHANCE = 0.93f
        /** Chance a falling grain with no momentum drifts diagonally. */
        const val DRIFT_CHANCE = 0.05f
        /** Chance a falling grain with momentum keeps moving sideways as it drops. */
        const val MOMENTUM_FALL = 0.5f
        /** Chance per tick that a resting grain rolls into a gap diagonally below it. */
        const val ROLL_CHANCE = 0.75f
        /** Chance a resting grain slips sideways toward a drop two cells away (shallow slopes). */
        const val SLIDE_CHANCE = 0f
        /** Same, for a grain that is already sliding that way. */
        const val SLIDE_MOMENTUM = 0.8f
        /** Chance per tick that a blocked grain loses its sideways momentum. */
        const val FRICTION = 0.35f
        /** Pull radius in tray cells, from the middle of the box's top edge. */
        const val PULL_RADIUS_CELLS = 0.85f
        const val PULL_RATE = 280f        // grains per second while sand is in reach
        const val SPAWN_TIME = 0.35f
        const val POP_SWELL_TIME = 0.2f
        const val RING_TIME = 0.5f
        const val GRAVITY_CELLS = 5f      // burst particle gravity, in cells / s^2
        const val MAX_BURST = 180
        private const val EPS = 0.001f
        private const val STEP = 0.25f
        /** How far (in box lengths) a blocked box may be nudged to slip past a corner. */
        private const val CORNER_ASSIST = 0.35f
        /** Release within this fraction of a cell of a slot and the box settles into it. */
        private const val SNAP = 0.3f
    }
}
