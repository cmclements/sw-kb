package app.satisfyingsand.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * All game rules, independent of Android so they can be unit tested on the JVM.
 *
 * Coordinates: everything is measured in "grain units" (1 unit = 1 sand grain).
 *  - The image occupies x in [0, cols), y in [0, rows).
 *  - Below it, after a thin lip of [BASE_GAP], is the play grid: playCols x playRows cells,
 *    each [boxLen] grains square. Row 0 of the play grid is the "base" (dock row) touching the image.
 */
class SandGame(
    val cols: Int,
    val rows: Int,
    initialGrains: IntArray,
    val boxLen: Int,
    val playRows: Int = 4,
    val maxBoxes: Int = 8,
    seed: Int = 1,
) {
    val playCols: Int = cols / boxLen
    val capacity: Int = boxLen * boxLen
    /** How far (in grains) above the base a docked box can reach: one box length. */
    val reach: Int = boxLen

    init {
        require(cols % boxLen == 0) { "cols must be a multiple of boxLen" }
        require(initialGrains.size == cols * rows)
        require(playRows >= 2)
    }

    /** Colour of each grain (opaque ARGB) or [EMPTY]. Row-major. */
    val grid: IntArray = initialGrains.copyOf()
    /** The untouched picture, for the win screen. */
    val original: IntArray = initialGrains.copyOf()
    val totalGrains: Int = grid.count { it != EMPTY }
    var gridRemaining: Int = totalGrains
        private set

    val playTop: Float get() = rows + BASE_GAP
    val worldWidth: Float get() = cols.toFloat()
    val worldHeight: Float get() = playTop + playRows * boxLen

    enum class BoxState { SPAWNING, IDLE, POPPING }

    inner class Box(val id: Int, var cx: Int, var cy: Int) {
        /** Rendered position in cell units (animates toward the logical cx/cy along [path]). */
        var x = cx.toFloat()
        var y = cy.toFloat()
        val path = ArrayDeque<Long>()
        val grains = IntArray(capacity)
        var fill = 0
        var reserved = 0
        var state = BoxState.SPAWNING
        /** 0..1 progress of the current SPAWNING/POPPING animation (may start negative = delay). */
        var anim = 0f
        var pullAcc = 0f
        var pullCursor = 0
        var wasDocked = false

        val fillFraction: Float get() = fill.toFloat() / capacity
        val isSettled: Boolean get() = path.isEmpty() && abs(x - cx) < 0.02f && abs(y - cy) < 0.02f
        val isDocked: Boolean get() = cy == 0 && isSettled && state != BoxState.POPPING
        /** World-space left/top of the box's cell. */
        val worldX: Float get() = x * boxLen
        val worldY: Float get() = playTop + y * boxLen

        /** World-space centre of slot [i] inside the box (slots fill bottom-up, left-to-right). */
        fun slotCenterX(i: Int): Float {
            val s = (boxLen - 2 * BOX_PAD) / boxLen
            return worldX + BOX_PAD + ((i % boxLen) + 0.5f) * s
        }
        fun slotCenterY(i: Int): Float {
            val s = (boxLen - 2 * BOX_PAD) / boxLen
            return worldY + BOX_PAD + ((boxLen - 1 - i / boxLen) + 0.5f) * s
        }
    }

    class FlyingGrain(
        val color: Int, val sx: Float, val sy: Float, val box: SandGame.Box, val slot: Int,
        val duration: Float, val wobble: Float,
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
        data class Pop(val x: Float, val y: Float, val remainingFraction: Float) : Event()
        data class Dock(val boxId: Int) : Event()
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
    private var dragStart: Long = -1

    /** Fraction of the picture drained so far, 0..1. */
    val progress: Float get() = if (totalGrains == 0) 1f else 1f - gridRemaining.toFloat() / totalGrains

    init {
        val wanted = boxesNeeded().coerceAtMost(maxBoxes)
        repeat(wanted) { spawnBox(immediate = true) }
    }

    // ---------------------------------------------------------------- queries

    fun boxAtCell(cx: Int, cy: Int): Box? = boxes.firstOrNull { it.cx == cx && it.cy == cy }

    fun cellAtWorld(wx: Float, wy: Float): Pair<Int, Int>? {
        if (wx < 0 || wx >= cols || wy < playTop || wy >= worldHeight) return null
        return Pair((wx / boxLen).toInt(), ((wy - playTop) / boxLen).toInt())
    }

    /** Hit-test against rendered box positions, so a box mid-slide can still be grabbed. */
    fun boxAtWorld(wx: Float, wy: Float): Box? = boxes.lastOrNull {
        wx >= it.worldX && wx < it.worldX + boxLen && wy >= it.worldY && wy < it.worldY + boxLen
    }

    fun cell(x: Int, y: Int): Int = grid[y * cols + x]

    // ---------------------------------------------------------------- input

    fun beginDrag(box: Box) {
        dragStart = pack(box.cx, box.cy)
    }

    /**
     * Move [box] to cell ([tx],[ty]) if there's a route through empty cells.
     * Returns true when the box moved.
     */
    fun dragTo(box: Box, tx: Int, ty: Int): Boolean {
        if (box.state == BoxState.POPPING || won) return false
        if (tx !in 0 until playCols || ty !in 0 until playRows) return false
        if (tx == box.cx && ty == box.cy) return false
        val route = findPath(box, tx, ty) ?: return false
        box.path.addAll(route)
        box.cx = tx
        box.cy = ty
        return true
    }

    fun endDrag(box: Box) {
        if (dragStart != -1L && dragStart != pack(box.cx, box.cy)) moves++
        dragStart = -1
    }

    private fun findPath(box: Box, tx: Int, ty: Int): List<Long>? {
        val blocked = BooleanArray(playCols * playRows)
        for (b in boxes) if (b !== box) blocked[b.cy * playCols + b.cx] = true
        if (blocked[ty * playCols + tx]) return null
        val prev = IntArray(playCols * playRows) { -2 }
        val start = box.cy * playCols + box.cx
        prev[start] = -1
        val queue = ArrayDeque<Int>().apply { add(start) }
        val target = ty * playCols + tx
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == target) break
            val cx = cur % playCols
            val cy = cur / playCols
            for (d in DIRS) {
                val nx = cx + d.first
                val ny = cy + d.second
                if (nx !in 0 until playCols || ny !in 0 until playRows) continue
                val n = ny * playCols + nx
                if (prev[n] != -2 || blocked[n]) continue
                prev[n] = cur
                queue.add(n)
            }
        }
        if (prev[target] == -2) return null
        val out = ArrayList<Long>()
        var c = target
        while (c != start) {
            out.add(pack(c % playCols, c / playCols))
            c = prev[c]
        }
        out.reverse()
        return out
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
            // Celebrate: pop every box that holds any sand, one after another.
            var delay = 0f
            for (b in boxes) if (b.fill > 0 && b.state != BoxState.POPPING) {
                b.state = BoxState.POPPING
                b.anim = -delay
                delay += 0.6f
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
                val c = grid[idx]
                if (c == EMPTY) continue
                val below = idx + cols
                if (grid[below] == EMPTY) {
                    grid[below] = c
                    grid[idx] = EMPTY
                    continue
                }
                if (rnd.nextFloat() > SLIDE_CHANCE) continue
                val dir = if (rnd.nextBoolean()) 1 else -1
                for (d in intArrayOf(dir, -dir)) {
                    val nx = x + d
                    if (nx !in 0 until cols) continue
                    val diag = below + d
                    if (grid[diag] == EMPTY && grid[idx + d] == EMPTY) {
                        grid[diag] = c
                        grid[idx] = EMPTY
                        break
                    }
                }
            }
        }
    }

    private fun updateBoxes(dt: Float) {
        val it = boxes.iterator()
        val popped = ArrayList<Box>()
        while (it.hasNext()) {
            val b = it.next()
            // Slide along the path.
            var budget = MOVE_SPEED * dt
            while (budget > 0f && b.path.isNotEmpty()) {
                val head = b.path.first()
                val hx = unpackX(head).toFloat()
                val hy = unpackY(head).toFloat()
                val dx = hx - b.x
                val dy = hy - b.y
                val d = sqrt(dx * dx + dy * dy)
                if (d <= budget) {
                    b.x = hx; b.y = hy; budget -= d
                    b.path.removeFirst()
                } else {
                    b.x += dx / d * budget; b.y += dy / d * budget; budget = 0f
                }
            }

            when (b.state) {
                BoxState.SPAWNING -> {
                    b.anim += dt / SPAWN_TIME
                    if (b.anim >= 1f) { b.anim = 1f; b.state = BoxState.IDLE }
                }
                BoxState.POPPING -> {
                    b.anim += dt / POP_SWELL_TIME
                    if (b.anim >= 1f) { popped += b; it.remove(); continue }
                }
                BoxState.IDLE -> Unit
            }

            val docked = b.isDocked
            if (docked && !b.wasDocked) events += Event.Dock(b.id)
            b.wasDocked = docked
            if (docked && !won) pull(b, dt) else b.pullAcc = 0f

            if (b.state != BoxState.POPPING && b.fill >= capacity) {
                b.state = BoxState.POPPING
                b.anim = 0f
            }
        }
        for (b in popped) burst(b)
    }

    private fun pull(b: Box, dt: Float) {
        if (b.reserved >= capacity) return
        b.pullAcc += dt * capacity / FILL_TIME
        val x0 = b.cx * boxLen
        while (b.pullAcc >= 1f && b.reserved < capacity) {
            val idx = takeGrain(b, x0) ?: break
            b.pullAcc -= 1f
            val gx = idx % cols
            val gy = idx / cols
            val color = grid[idx]
            grid[idx] = EMPTY
            gridRemaining--
            flying += FlyingGrain(
                color, gx + 0.5f, gy + 0.5f, b, b.reserved++,
                duration = 0.22f + rnd.nextFloat() * 0.18f,
                wobble = (rnd.nextFloat() * 2f - 1f) * 1.2f,
            )
        }
        if (b.pullAcc > 1f) b.pullAcc = 1f
    }

    /**
     * Next grain for a docked box: columns of its span take turns (so the picture above slides
     * down as an intact block instead of streaking), and within a column the lowest grain inside
     * one box length of the base is taken.
     */
    private fun takeGrain(b: Box, x0: Int): Int? {
        val minY = (rows - reach).coerceAtLeast(0)
        for (k in 0 until boxLen) {
            val x = x0 + (b.pullCursor + k) % boxLen
            for (y in rows - 1 downTo minY) {
                val idx = y * cols + x
                if (grid[idx] != EMPTY) {
                    b.pullCursor = (b.pullCursor + k + 1) % boxLen
                    return idx
                }
            }
        }
        return null
    }

    private fun updateFlying(dt: Float) {
        val it = flying.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.t += dt / f.duration
            val tx = f.box.slotCenterX(f.slot)
            val ty = f.box.slotCenterY(f.slot)
            if (f.t >= 1f) {
                f.box.grains[f.slot] = f.color
                f.box.fill++
                grainsCollected++
                it.remove()
                continue
            }
            val e = f.t * f.t // accelerate into the box, like suction
            f.x = f.sx + (tx - f.sx) * e + sin(f.t * PI.toFloat()) * f.wobble
            f.y = f.sy + (ty - f.sy) * e
        }
    }

    private fun burst(b: Box) {
        boxesPopped++
        val cx = b.worldX + boxLen / 2f
        val cy = b.worldY + boxLen / 2f
        for (i in 0 until capacity) {
            val c = b.grains[i]
            if (c == EMPTY) continue
            val px = b.slotCenterX(i)
            val py = b.slotCenterY(i)
            val ang = rnd.nextFloat() * 2f * PI.toFloat()
            val spd = 6f + rnd.nextFloat() * 18f
            val ox = (px - cx) * 3f
            val oy = (py - cy) * 3f
            particles += Particle(
                px, py,
                cos(ang) * spd + ox, sin(ang) * spd + oy - 14f,
                c, 0.6f + rnd.nextFloat() * 0.6f,
            )
        }
        val ringColor = b.grains.firstOrNull { it != EMPTY } ?: 0xFFFFFFFF.toInt()
        rings += Ring(cx, cy, ringColor)
        events += Event.Pop(cx, cy, 1f - progress)
        if (!won) scheduleSpawnsIfNeeded()
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

    /** Boxes still required so that total free capacity covers the sand left in the picture. */
    private fun boxesNeeded(): Int {
        var free = 0
        for (b in boxes) if (b.state != BoxState.POPPING) free += capacity - b.reserved
        val shortfall = gridRemaining - free - pendingSpawns.size * capacity
        return if (shortfall <= 0) 0 else (shortfall + capacity - 1) / capacity
    }

    private fun scheduleSpawnsIfNeeded() {
        var need = boxesNeeded()
        while (need > 0 && liveBoxCount() + pendingSpawns.size < maxBoxes) {
            pendingSpawns += SPAWN_DELAY + pendingSpawns.size * 0.15f
            need--
        }
    }

    private fun liveBoxCount() = boxes.count { it.state != BoxState.POPPING }

    private fun updateSpawns(dt: Float) {
        if (won) { pendingSpawns.clear(); return }
        for (i in pendingSpawns.indices) pendingSpawns[i] -= dt
        val ready = pendingSpawns.count { it <= 0f }
        if (ready > 0) {
            pendingSpawns.removeAll { it <= 0f }
            repeat(ready) { if (!spawnBox(immediate = false)) pendingSpawns += 0.3f }
        }
        // Safety net: never leave the player without enough boxes.
        if (pendingSpawns.isEmpty() && boxes.none { it.state == BoxState.POPPING }) scheduleSpawnsIfNeeded()
    }

    /** Places a new empty box as far from the base as possible. */
    private fun spawnBox(immediate: Boolean): Boolean {
        for (cy in playRows - 1 downTo 0) {
            val free = (0 until playCols).filter { cx -> boxes.none { it.cx == cx && it.cy == cy } }
            if (free.isEmpty()) continue
            val cx = free[rnd.nextInt(free.size)]
            val b = Box(nextBoxId++, cx, cy)
            if (immediate) { b.state = BoxState.IDLE; b.anim = 1f }
            boxes += b
            return true
        }
        return false
    }

    companion object {
        const val EMPTY = 0
        const val BASE_GAP = 0.6f
        const val BOX_PAD = 0.3f
        const val SAND_STEP = 1f / 60f
        const val SLIDE_CHANCE = 0.55f
        const val MOVE_SPEED = 22f        // cells per second
        const val FILL_TIME = 0.9f        // seconds for a box to fill when sand is plentiful
        const val SPAWN_TIME = 0.35f
        const val SPAWN_DELAY = 0.3f
        const val POP_SWELL_TIME = 0.2f
        const val RING_TIME = 0.5f
        const val GRAVITY = 70f           // grains / s^2 for burst particles

        private val DIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
        fun unpackX(p: Long): Int = (p shr 32).toInt()
        fun unpackY(p: Long): Int = p.toInt()
    }
}
