package app.satisfyingsand.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import app.satisfyingsand.game.SandGame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val game: SandGame,
    private val sfx: Sfx,
    private val listener: Listener,
) : View(context) {

    interface Listener {
        fun onExit()
        fun onPlayAgain()
        fun onNewPicture()
    }

    private val dp = resources.displayMetrics.density

    // World -> screen mapping (world units are sand grains).
    private var g = 1f
    private var ox = 0f
    private var oy = 0f
    private val hudH = 56 * dp

    private val sandBmp = Bitmap.createBitmap(game.cols, game.rows, Bitmap.Config.ARGB_8888)
    private val originalBmp = Bitmap.createBitmap(game.cols, game.rows, Bitmap.Config.ARGB_8888).apply {
        setPixels(game.original, 0, game.cols, 0, 0, game.cols, game.rows)
    }
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val srcRect = Rect()

    private var lastNanos = 0L
    private var dragging: SandGame.Box? = null
    private var grainSoundCooldown = 0f
    private var winAge = -1f
    private val playAgainBtn = RectF()
    private val newPicBtn = RectF()
    private val backBtn = RectF()
    private val soundBtn = RectF()

    private class Confetti(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int, var rot: Float, val spin: Float)
    private val confetti = ArrayList<Confetti>()

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val margin = 12 * dp
        val top = paddingTop + hudH
        val availW = w - paddingLeft - paddingRight - 2 * margin
        val availH = h - top - paddingBottom - margin
        g = min(availW / game.worldWidth, availH / game.worldHeight)
        ox = paddingLeft + (w - paddingLeft - paddingRight - game.worldWidth * g) / 2f
        oy = top + (availH - game.worldHeight * g) / 2f
    }

    private fun sx(wx: Float) = ox + wx * g
    private fun sy(wy: Float) = oy + wy * g

    // ------------------------------------------------------------------ frame

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
        lastNanos = now
        game.update(dt)
        handleEvents()
        updateEffects(dt)

        canvas.drawColor(BG)
        drawImageWell(canvas)
        drawPlayArea(canvas)
        drawBoxes(canvas)
        drawFlying(canvas)
        drawParticles(canvas)
        drawHud(canvas)
        if (game.won) drawWin(canvas, dt)

        postInvalidateOnAnimation()
    }

    private fun handleEvents() {
        for (e in game.events) when (e) {
            is SandGame.Event.Pop -> {
                sfx.pop(game.progress)
                haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            }
            is SandGame.Event.Dock -> {
                sfx.dock()
                haptic(HapticFeedbackConstants.CLOCK_TICK)
            }
            SandGame.Event.Win -> {
                sfx.win()
                winAge = 0f
                spawnConfetti()
            }
        }
        game.events.clear()
    }

    private fun haptic(type: Int) {
        performHapticFeedback(type)
    }

    private fun updateEffects(dt: Float) {
        grainSoundCooldown -= dt
        if (game.flying.isNotEmpty() && grainSoundCooldown <= 0f) {
            sfx.grain()
            grainSoundCooldown = 0.05f + Random.nextFloat() * 0.05f
        }
        val it = confetti.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.vy += 900 * dp / 3f * dt
            c.vx *= 1f - 0.8f * dt
            c.x += c.vx * dt; c.y += c.vy * dt; c.rot += c.spin * dt
            if (c.y > height + 40 * dp) it.remove()
        }
    }

    private fun spawnConfetti() {
        val colors = intArrayOf(ACCENT, Color.rgb(255, 110, 140), Color.rgb(110, 200, 255), Color.rgb(140, 230, 140), Color.WHITE)
        repeat(140) {
            val ang = (-PI / 2 + (Random.nextDouble() - 0.5) * 1.6).toFloat()
            val spd = (500 + Random.nextFloat() * 700) * dp / 2f
            confetti += Confetti(
                width / 2f, height * 0.55f, cos(ang) * spd, sin(ang) * spd,
                colors[Random.nextInt(colors.size)], Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 720f,
            )
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun drawImageWell(c: Canvas) {
        val pad = 0.5f * g
        rect.set(sx(0f) - pad, sy(0f) - pad, sx(game.worldWidth) + pad, sy(game.rows.toFloat()) + pad * 0.2f)
        paint.color = WELL
        c.drawRoundRect(rect, 10 * dp, 10 * dp, paint)

        // Suction glow over the region each docked box is pulling from.
        for (b in game.boxes) if (b.isDocked && b.reserved < game.capacity) {
            val x0 = sx((b.cx * game.boxLen).toFloat())
            val x1 = sx(((b.cx + 1) * game.boxLen).toFloat())
            val y1 = sy(game.rows.toFloat())
            val y0 = sy((game.rows - game.reach).toFloat())
            paint.shader = LinearGradient(0f, y0, 0f, y1, Color.TRANSPARENT, Color.argb(70, 255, 230, 160), Shader.TileMode.CLAMP)
            c.drawRect(x0, y0, x1, y1, paint)
            paint.shader = null
        }

        sandBmp.setPixels(game.grid, 0, game.cols, 0, 0, game.cols, game.rows)
        rect.set(sx(0f), sy(0f), sx(game.worldWidth), sy(game.rows.toFloat()))
        c.drawBitmap(sandBmp, null, rect, pixelPaint)
    }

    private fun drawPlayArea(c: Canvas) {
        // The "base" lip between picture and play area.
        paint.color = LIP
        rect.set(sx(0f) - 0.5f * g, sy(game.rows.toFloat()), sx(game.worldWidth) + 0.5f * g, sy(game.playTop))
        c.drawRoundRect(rect, 3 * dp, 3 * dp, paint)

        val inset = 0.25f * g
        for (cy in 0 until game.playRows) for (cx in 0 until game.playCols) {
            val x = sx((cx * game.boxLen).toFloat())
            val y = sy(game.playTop + cy * game.boxLen)
            val size = game.boxLen * g
            rect.set(x + inset, y + inset, x + size - inset, y + size - inset)
            paint.color = if (cy == 0) DOCK_CELL else CELL
            c.drawRoundRect(rect, 8 * dp, 8 * dp, paint)
            if (cy == 0) {
                // Little upward chevron marks the base slots.
                stroke.color = Color.argb(70, 255, 230, 160)
                stroke.strokeWidth = 2.5f * dp
                stroke.strokeCap = Paint.Cap.ROUND
                val mx = rect.centerX(); val my = rect.centerY(); val s = size * 0.12f
                c.drawLine(mx - s, my + s * 0.5f, mx, my - s * 0.5f, stroke)
                c.drawLine(mx, my - s * 0.5f, mx + s, my + s * 0.5f, stroke)
            }
        }
    }

    private fun drawBoxes(c: Canvas) {
        for (b in game.boxes) {
            val scale = when (b.state) {
                SandGame.BoxState.SPAWNING -> easeOutBack(b.anim.coerceIn(0f, 1f))
                SandGame.BoxState.POPPING -> 1f + 0.2f * b.anim.coerceAtLeast(0f)
                SandGame.BoxState.IDLE -> 1f
            }
            if (scale <= 0.01f) continue
            val size = game.boxLen * g
            val cxs = sx(b.worldX) + size / 2
            val cys = sy(b.worldY) + size / 2
            val lifted = b === dragging
            c.save()
            c.scale(scale * (if (lifted) 1.06f else 1f), scale * (if (lifted) 1.06f else 1f), cxs, cys)

            val left = sx(b.worldX) + 0.08f * g
            val top = sy(b.worldY) + 0.08f * g
            val right = sx(b.worldX + game.boxLen) - 0.08f * g
            val bottom = sy(b.worldY + game.boxLen) - 0.08f * g
            if (lifted) {
                paint.color = Color.argb(90, 0, 0, 0)
                rect.set(left + 3 * dp, top + 6 * dp, right + 3 * dp, bottom + 6 * dp)
                c.drawRoundRect(rect, 9 * dp, 9 * dp, paint)
            }
            rect.set(left, top, right, bottom)
            paint.color = BOX_BODY
            c.drawRoundRect(rect, 9 * dp, 9 * dp, paint)

            // Collected grains, drawn at the same scale as the picture.
            val s = (game.boxLen - 2 * SandGame.BOX_PAD) / game.boxLen
            for (i in 0 until game.capacity) {
                val col = b.grains[i]
                if (col == SandGame.EMPTY) continue
                paint.color = col
                val gx = sx(b.slotCenterX(i)); val gy = sy(b.slotCenterY(i)); val h = s * g / 2 + 0.5f
                c.drawRect(gx - h, gy - h, gx + h, gy + h, paint)
            }

            stroke.strokeWidth = 2.5f * dp
            stroke.color = when {
                lifted -> ACCENT
                b.isDocked -> Color.argb(255, 255, 225, 150)
                else -> BOX_EDGE
            }
            c.drawRoundRect(rect, 9 * dp, 9 * dp, stroke)

            // Fill meter: thin bar along the box's right edge + percentage label.
            val frac = b.fillFraction
            val barX = right - 4 * dp
            paint.color = Color.argb(60, 255, 255, 255)
            c.drawRect(barX - 2 * dp, top + 6 * dp, barX, bottom - 6 * dp, paint)
            paint.color = ACCENT
            val barTop = bottom - 6 * dp - (bottom - top - 12 * dp) * frac
            c.drawRect(barX - 2 * dp, barTop, barX, bottom - 6 * dp, paint)
            if (b.fill > 0 && b.state != SandGame.BoxState.POPPING) {
                text.textSize = min(14 * dp, size * 0.26f)
                text.setShadowLayer(4 * dp, 0f, 0f, Color.BLACK)
                c.drawText("${(frac * 100).toInt()}%", cxs, top + text.textSize + 2 * dp, text)
                text.clearShadowLayer()
            }

            if (b.state == SandGame.BoxState.POPPING && b.anim > 0f) {
                paint.color = Color.argb((b.anim.coerceAtMost(1f) * 220).toInt(), 255, 250, 235)
                c.drawRoundRect(rect, 9 * dp, 9 * dp, paint)
            }
            c.restore()
        }
    }

    private fun drawFlying(c: Canvas) {
        val h = g * 0.5f
        for (f in game.flying) {
            paint.color = f.color
            val x = sx(f.x); val y = sy(f.y)
            val shrink = 1f - 0.25f * f.t
            c.drawRect(x - h * shrink, y - h * shrink, x + h * shrink, y + h * shrink, paint)
        }
    }

    private fun drawParticles(c: Canvas) {
        for (p in game.particles) {
            val a = 1f - p.age / p.life
            paint.color = (p.color and 0x00FFFFFF) or ((a * 255).toInt().coerceIn(0, 255) shl 24)
            val h = g * 0.45f * (0.6f + 0.4f * a)
            c.drawRect(sx(p.x) - h, sy(p.y) - h, sx(p.x) + h, sy(p.y) + h, paint)
        }
        for (r in game.rings) {
            val t = r.age / SandGame.RING_TIME
            stroke.color = (r.color and 0x00FFFFFF) or (((1f - t) * 220).toInt() shl 24)
            stroke.strokeWidth = (1f - t) * 6 * dp + 1 * dp
            c.drawCircle(sx(r.x), sy(r.y), game.boxLen * g * (0.4f + 1.2f * easeOut(t)), stroke)
        }
    }

    private fun drawHud(c: Canvas) {
        val top = paddingTop.toFloat()
        val cy = top + hudH / 2

        // Back button.
        backBtn.set(paddingLeft + 6 * dp, top + 6 * dp, paddingLeft + 50 * dp, top + hudH - 6 * dp)
        stroke.color = Color.WHITE; stroke.strokeWidth = 2.5f * dp; stroke.strokeCap = Paint.Cap.ROUND
        val bx = backBtn.centerX(); val s = 8 * dp
        c.drawLine(bx + s * 0.4f, cy - s, bx - s * 0.6f, cy, stroke)
        c.drawLine(bx - s * 0.6f, cy, bx + s * 0.4f, cy + s, stroke)

        // Sound toggle.
        soundBtn.set(width - paddingRight - 50 * dp, top + 6 * dp, width - paddingRight - 6 * dp, top + hudH - 6 * dp)
        text.textSize = 18 * dp
        text.color = if (sfx.enabled) Color.WHITE else Color.argb(110, 255, 255, 255)
        c.drawText("♪", soundBtn.centerX(), cy + 6 * dp, text)
        text.color = Color.WHITE
        if (!sfx.enabled) {
            stroke.color = Color.argb(200, 255, 255, 255)
            c.drawLine(soundBtn.centerX() - 9 * dp, cy + 9 * dp, soundBtn.centerX() + 9 * dp, cy - 9 * dp, stroke)
        }

        // Progress bar.
        val barL = backBtn.right + 10 * dp
        val barR = soundBtn.left - 10 * dp
        rect.set(barL, cy - 2 * dp, barR, cy + 6 * dp)
        paint.color = Color.argb(50, 255, 255, 255)
        c.drawRoundRect(rect, 4 * dp, 4 * dp, paint)
        rect.right = barL + (barR - barL) * game.progress
        paint.color = ACCENT
        if (rect.width() > 1f) c.drawRoundRect(rect, 4 * dp, 4 * dp, paint)

        text.textSize = 12 * dp
        text.textAlign = Paint.Align.LEFT
        c.drawText("${(game.progress * 100).toInt()}% drained", barL, cy - 8 * dp, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("${game.moves} moves · ${formatTime(game.elapsed)}", barR, cy - 8 * dp, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawWin(c: Canvas, dt: Float) {
        winAge += dt
        for (cf in confetti) {
            paint.color = cf.color
            c.save(); c.rotate(cf.rot, cf.x, cf.y)
            c.drawRect(cf.x - 4 * dp, cf.y - 2.5f * dp, cf.x + 4 * dp, cf.y + 2.5f * dp, paint)
            c.restore()
        }
        val a = ((winAge - 0.9f) / 0.4f).coerceIn(0f, 1f)
        if (a <= 0f) return

        paint.color = Color.argb((a * 170).toInt(), 10, 6, 20)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        val panelW = min(width - 48 * dp, 360 * dp)
        val imgW = panelW - 64 * dp
        val imgH = imgW * game.rows / game.cols
        val panelH = imgH + 230 * dp
        val left = (width - panelW) / 2
        val top = (height - panelH) / 2 + (1 - easeOut(a)) * 40 * dp
        rect.set(left, top, left + panelW, top + panelH)
        paint.color = Color.argb((a * 255).toInt(), 0x2A, 0x21, 0x40)
        c.drawRoundRect(rect, 18 * dp, 18 * dp, paint)

        text.alpha = (a * 255).toInt()
        text.textSize = 26 * dp
        text.color = ACCENT; text.alpha = (a * 255).toInt()
        c.drawText("Picture drained!", width / 2f, top + 44 * dp, text)

        // The picture they just emptied, restored.
        srcRect.set(0, 0, game.cols, game.rows)
        rect.set(left + 32 * dp, top + 64 * dp, left + 32 * dp + imgW, top + 64 * dp + imgH)
        pixelPaint.alpha = (a * 255).toInt()
        c.drawBitmap(originalBmp, srcRect, rect, pixelPaint)
        pixelPaint.alpha = 255

        text.color = Color.WHITE; text.alpha = (a * 255).toInt()
        text.textSize = 15 * dp
        c.drawText(
            "${game.totalGrains} grains · ${game.moves} moves · ${formatTime(game.elapsed)}",
            width / 2f, rect.bottom + 30 * dp, text,
        )

        val btnTop = rect.bottom + 52 * dp
        val btnW = (panelW - 64 * dp - 12 * dp) / 2
        playAgainBtn.set(left + 32 * dp, btnTop, left + 32 * dp + btnW, btnTop + 48 * dp)
        newPicBtn.set(playAgainBtn.right + 12 * dp, btnTop, playAgainBtn.right + 12 * dp + btnW, btnTop + 48 * dp)
        paint.color = ACCENT; paint.alpha = (a * 255).toInt()
        c.drawRoundRect(playAgainBtn, 12 * dp, 12 * dp, paint)
        paint.color = Color.argb((a * 255).toInt(), 0x4A, 0x3D, 0x6B)
        c.drawRoundRect(newPicBtn, 12 * dp, 12 * dp, paint)
        text.textSize = 15 * dp
        text.color = Color.rgb(40, 25, 10); text.alpha = (a * 255).toInt()
        c.drawText("Play again", playAgainBtn.centerX(), playAgainBtn.centerY() + 5 * dp, text)
        text.color = Color.WHITE; text.alpha = (a * 255).toInt()
        c.drawText("New picture", newPicBtn.centerX(), newPicBtn.centerY() + 5 * dp, text)
        text.alpha = 255
    }

    // ------------------------------------------------------------------ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val wx = (e.x - ox) / g
        val wy = (e.y - oy) / g
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (backBtn.contains(e.x, e.y)) { listener.onExit(); return true }
                if (soundBtn.contains(e.x, e.y)) { sfx.enabled = !sfx.enabled; return true }
                if (game.won) {
                    if (winAge > 1.1f) {
                        if (playAgainBtn.contains(e.x, e.y)) listener.onPlayAgain()
                        else if (newPicBtn.contains(e.x, e.y)) listener.onNewPicture()
                    }
                    return true
                }
                val b = game.boxAtWorld(wx, wy)
                if (b != null && b.state != SandGame.BoxState.POPPING) {
                    dragging = b
                    game.beginDrag(b)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val b = dragging ?: return true
                if (b.state == SandGame.BoxState.POPPING || !game.boxes.contains(b)) { dragging = null; return true }
                followFinger(b, wx, wy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging?.let { game.endDrag(it) }
                dragging = null
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Moves the box toward the finger: straight there if a route exists, else one step closer. */
    private fun followFinger(b: SandGame.Box, wx: Float, wy: Float) {
        val clampedX = wx.coerceIn(0f, game.worldWidth - 0.01f)
        val clampedY = wy.coerceIn(game.playTop, game.worldHeight - 0.01f)
        val (tx, ty) = game.cellAtWorld(clampedX, clampedY) ?: return
        if (tx == b.cx && ty == b.cy) return
        if (game.dragTo(b, tx, ty)) return
        val dx = Integer.signum(tx - b.cx)
        val dy = Integer.signum(ty - b.cy)
        val horizFirst = kotlin.math.abs(tx - b.cx) >= kotlin.math.abs(ty - b.cy)
        val tries = if (horizFirst) listOf(dx to 0, 0 to dy) else listOf(0 to dy, dx to 0)
        for ((sx, sy) in tries) if ((sx != 0 || sy != 0) && game.dragTo(b, b.cx + sx, b.cy + sy)) return
    }

    // ------------------------------------------------------------------ helpers

    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)
    private fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private fun formatTime(s: Float): String {
        val total = s.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sandBmp.recycle()
        originalBmp.recycle()
    }

    companion object {
        val BG = Color.rgb(0x1B, 0x16, 0x26)
        val WELL = Color.rgb(0x0E, 0x0B, 0x16)
        val LIP = Color.rgb(0x4A, 0x3D, 0x6B)
        val CELL = Color.rgb(0x24, 0x1D, 0x33)
        val DOCK_CELL = Color.rgb(0x33, 0x29, 0x4A)
        val BOX_BODY = Color.rgb(0x16, 0x12, 0x22)
        val BOX_EDGE = Color.rgb(0xB8, 0xAC, 0xD6)
        val ACCENT = Color.rgb(0xF2, 0xB3, 0x3D)
    }
}
