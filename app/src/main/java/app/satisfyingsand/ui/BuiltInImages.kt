package app.satisfyingsand.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.random.Random

/** The built-in picture library: small original illustrations drawn in code (no image assets). */
object BuiltInImages {

    class Entry(val name: String, val draw: (Canvas, Float, Float) -> Unit)

    const val W = 300
    const val H = 400

    val entries: List<Entry> = listOf(
        Entry("Sunset", ::sunset),
        Entry("Mountains", ::mountains),
        Entry("Heart", ::heart),
        Entry("Rainbow", ::rainbow),
        Entry("Smiley", ::smiley),
        Entry("Balloon", ::balloon),
        Entry("Mushroom", ::mushroom),
        Entry("Night City", ::city),
    )

    fun render(entry: Entry): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        entry.draw(Canvas(bmp), W.toFloat(), H.toFloat())
        return bmp
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun vGrad(c: Canvas, top: Float, bottom: Float, w: Float, vararg colors: Int) {
        val p = Paint().apply { shader = LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP) }
        c.drawRect(0f, top, w, bottom, p)
    }

    private fun sunset(c: Canvas, w: Float, h: Float) {
        vGrad(c, 0f, h * 0.62f, w, Color.rgb(58, 28, 92), Color.rgb(196, 64, 110), Color.rgb(255, 150, 70))
        c.drawCircle(w * 0.5f, h * 0.6f, w * 0.2f, fill(Color.rgb(255, 214, 102)))
        vGrad(c, h * 0.62f, h, w, Color.rgb(230, 110, 80), Color.rgb(60, 30, 90))
        val glint = fill(Color.rgb(255, 200, 110))
        for (i in 0 until 7) {
            val y = h * 0.65f + i * h * 0.045f
            val half = w * (0.16f - i * 0.018f)
            c.drawRect(w * 0.5f - half, y, w * 0.5f + half, y + h * 0.012f, glint)
        }
        val island = Path().apply {
            moveTo(0f, h * 0.63f); quadTo(w * 0.12f, h * 0.55f, w * 0.28f, h * 0.63f); close()
        }
        c.drawPath(island, fill(Color.rgb(35, 18, 50)))
    }

    private fun mountains(c: Canvas, w: Float, h: Float) {
        vGrad(c, 0f, h, w, Color.rgb(110, 180, 240), Color.rgb(200, 230, 250))
        val back = Path().apply {
            moveTo(0f, h * 0.6f); lineTo(w * 0.25f, h * 0.3f); lineTo(w * 0.5f, h * 0.55f)
            lineTo(w * 0.75f, h * 0.25f); lineTo(w, h * 0.5f); lineTo(w, h); lineTo(0f, h); close()
        }
        c.drawPath(back, fill(Color.rgb(120, 130, 170)))
        val snow = Path().apply {
            moveTo(w * 0.75f, h * 0.25f); lineTo(w * 0.68f, h * 0.33f); lineTo(w * 0.73f, h * 0.31f)
            lineTo(w * 0.77f, h * 0.34f); lineTo(w * 0.82f, h * 0.32f); close()
        }
        c.drawPath(snow, fill(Color.WHITE))
        val front = Path().apply {
            moveTo(0f, h * 0.75f); lineTo(w * 0.4f, h * 0.45f); lineTo(w * 0.8f, h * 0.78f); lineTo(w, h * 0.7f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        c.drawPath(front, fill(Color.rgb(70, 80, 120)))
        val snow2 = Path().apply {
            moveTo(w * 0.4f, h * 0.45f); lineTo(w * 0.32f, h * 0.51f); lineTo(w * 0.38f, h * 0.5f)
            lineTo(w * 0.43f, h * 0.53f); lineTo(w * 0.47f, h * 0.5f); close()
        }
        c.drawPath(snow2, fill(Color.WHITE))
        c.drawRect(0f, h * 0.85f, w, h, fill(Color.rgb(70, 150, 80)))
        val tree = fill(Color.rgb(30, 90, 50))
        for (i in 0 until 6) {
            val x = w * (0.08f + i * 0.17f)
            val t = Path().apply { moveTo(x, h * 0.76f); lineTo(x - w * 0.05f, h * 0.88f); lineTo(x + w * 0.05f, h * 0.88f); close() }
            c.drawPath(t, tree)
        }
    }

    private fun heart(c: Canvas, w: Float, h: Float) {
        val bg = Paint().apply {
            shader = RadialGradient(w / 2, h / 2, h * 0.7f, Color.rgb(255, 200, 220), Color.rgb(230, 110, 160), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w, h, bg)
        val p = Path()
        val cx = w / 2; val cy = h * 0.45f; val s = w * 0.4f
        p.moveTo(cx, cy + s * 0.95f)
        p.cubicTo(cx - s * 1.4f, cy + s * 0.1f, cx - s * 0.8f, cy - s * 1.0f, cx, cy - s * 0.35f)
        p.cubicTo(cx + s * 0.8f, cy - s * 1.0f, cx + s * 1.4f, cy + s * 0.1f, cx, cy + s * 0.95f)
        c.drawPath(p, fill(Color.rgb(210, 30, 60)))
        c.drawOval(RectF(cx - s * 0.75f, cy - s * 0.55f, cx - s * 0.35f, cy - s * 0.2f), fill(Color.rgb(255, 120, 140)))
    }

    private fun rainbow(c: Canvas, w: Float, h: Float) {
        vGrad(c, 0f, h, w, Color.rgb(90, 170, 240), Color.rgb(180, 220, 255))
        val colors = intArrayOf(
            Color.rgb(230, 50, 50), Color.rgb(250, 140, 40), Color.rgb(250, 220, 50),
            Color.rgb(70, 190, 80), Color.rgb(50, 120, 220), Color.rgb(120, 60, 180),
        )
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w * 0.065f }
        colors.forEachIndexed { i, col ->
            stroke.color = col
            val r = w * 0.62f - i * stroke.strokeWidth
            c.drawArc(RectF(w / 2 - r, h * 0.7f - r, w / 2 + r, h * 0.7f + r), 180f, 180f, false, stroke)
        }
        val cloud = fill(Color.WHITE)
        for ((x, y) in listOf(0.12f to 0.7f, 0.88f to 0.7f)) {
            c.drawCircle(w * x, h * y, w * 0.12f, cloud)
            c.drawCircle(w * x - w * 0.1f, h * y + h * 0.03f, w * 0.09f, cloud)
            c.drawCircle(w * x + w * 0.1f, h * y + h * 0.03f, w * 0.09f, cloud)
        }
        c.drawRect(0f, h * 0.82f, w, h, fill(Color.rgb(90, 180, 90)))
    }

    private fun smiley(c: Canvas, w: Float, h: Float) {
        c.drawColor(Color.rgb(40, 170, 170))
        val cx = w / 2; val cy = h / 2; val r = w * 0.4f
        c.drawCircle(cx, cy, r, fill(Color.rgb(255, 210, 50)))
        c.drawCircle(cx, cy, r * 0.92f, fill(Color.rgb(255, 225, 80)))
        val dark = fill(Color.rgb(70, 40, 20))
        c.drawOval(RectF(cx - r * 0.42f, cy - r * 0.45f, cx - r * 0.18f, cy - r * 0.05f), dark)
        c.drawOval(RectF(cx + r * 0.18f, cy - r * 0.45f, cx + r * 0.42f, cy - r * 0.05f), dark)
        val mouth = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 40, 20); style = Paint.Style.STROKE; strokeWidth = r * 0.12f; strokeCap = Paint.Cap.ROUND
        }
        c.drawArc(RectF(cx - r * 0.55f, cy - r * 0.35f, cx + r * 0.55f, cy + r * 0.6f), 20f, 140f, false, mouth)
        val blush = fill(Color.argb(140, 255, 120, 100))
        c.drawCircle(cx - r * 0.6f, cy + r * 0.2f, r * 0.13f, blush)
        c.drawCircle(cx + r * 0.6f, cy + r * 0.2f, r * 0.13f, blush)
    }

    private fun balloon(c: Canvas, w: Float, h: Float) {
        vGrad(c, 0f, h, w, Color.rgb(120, 200, 250), Color.rgb(250, 230, 200))
        val cloud = fill(Color.argb(230, 255, 255, 255))
        c.drawOval(RectF(w * 0.02f, h * 0.15f, w * 0.35f, h * 0.22f), cloud)
        c.drawOval(RectF(w * 0.6f, h * 0.68f, w * 0.98f, h * 0.76f), cloud)
        val cx = w / 2; val cy = h * 0.4f; val r = w * 0.3f
        val stripes = intArrayOf(Color.rgb(230, 60, 60), Color.rgb(255, 200, 60), Color.rgb(60, 140, 220))
        c.save()
        val clip = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.85f, cy + r * 0.5f); lineTo(cx + r * 0.85f, cy + r * 0.5f); lineTo(cx, cy + r * 1.5f); close()
        }
        c.clipPath(clip)
        val sw = r * 2 / 6
        for (i in 0 until 6) c.drawRect(cx - r + i * sw, cy - r, cx - r + (i + 1) * sw, cy + r * 1.5f, fill(stripes[i % 3]))
        c.restore()
        val rope = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 60, 40); strokeWidth = 3f }
        c.drawLine(cx - r * 0.25f, cy + r * 1.35f, cx - r * 0.2f, cy + r * 1.7f, rope)
        c.drawLine(cx + r * 0.25f, cy + r * 1.35f, cx + r * 0.2f, cy + r * 1.7f, rope)
        c.drawRect(cx - r * 0.25f, cy + r * 1.7f, cx + r * 0.25f, cy + r * 2.0f, fill(Color.rgb(150, 90, 50)))
    }

    private fun mushroom(c: Canvas, w: Float, h: Float) {
        vGrad(c, 0f, h * 0.8f, w, Color.rgb(250, 240, 200), Color.rgb(200, 230, 170))
        c.drawRect(0f, h * 0.8f, w, h, fill(Color.rgb(80, 160, 70)))
        val stem = Path().apply {
            moveTo(w * 0.38f, h * 0.5f); lineTo(w * 0.62f, h * 0.5f); lineTo(w * 0.66f, h * 0.85f); lineTo(w * 0.34f, h * 0.85f); close()
        }
        c.drawPath(stem, fill(Color.rgb(245, 230, 200)))
        val cap = Path().apply {
            moveTo(w * 0.1f, h * 0.53f)
            cubicTo(w * 0.1f, h * 0.15f, w * 0.9f, h * 0.15f, w * 0.9f, h * 0.53f); close()
        }
        c.drawPath(cap, fill(Color.rgb(210, 40, 40)))
        val dot = fill(Color.WHITE)
        for ((x, y, r) in listOf(Triple(0.3f, 0.38f, 0.07f), Triple(0.55f, 0.3f, 0.08f), Triple(0.75f, 0.43f, 0.06f), Triple(0.45f, 0.47f, 0.045f))) {
            c.drawCircle(w * x, h * y, w * r, dot)
        }
        val grass = fill(Color.rgb(50, 120, 50))
        val rnd = Random(3)
        repeat(30) {
            val x = rnd.nextFloat() * w
            val t = Path().apply { moveTo(x, h * 0.8f - rnd.nextFloat() * h * 0.06f); lineTo(x - 4, h * 0.82f); lineTo(x + 4, h * 0.82f); close() }
            c.drawPath(t, grass)
        }
    }

    private fun city(c: Canvas, w: Float, h: Float) {
        vGrad(c, 0f, h, w, Color.rgb(15, 20, 60), Color.rgb(70, 50, 120))
        c.drawCircle(w * 0.75f, h * 0.18f, w * 0.1f, fill(Color.rgb(250, 245, 210)))
        val star = fill(Color.rgb(255, 255, 230))
        val rnd = Random(11)
        repeat(25) { c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.45f, 1.8f, star) }
        val windows = fill(Color.rgb(255, 210, 90))
        var x = 0f
        var i = 0
        while (x < w) {
            val bw = w * (0.12f + rnd.nextFloat() * 0.1f)
            val top = h * (0.4f + rnd.nextFloat() * 0.3f)
            c.drawRect(x, top, x + bw, h, fill(if (i % 2 == 0) Color.rgb(30, 30, 55) else Color.rgb(45, 40, 75)))
            var wy = top + 10f
            while (wy < h - 14f) {
                var wx = x + 6f
                while (wx < x + bw - 10f) {
                    if (rnd.nextFloat() < 0.55f) c.drawRect(wx, wy, wx + 6f, wy + 8f, windows)
                    wx += 12f
                }
                wy += 16f
            }
            x += bw; i++
        }
        // A little crescent glow under the moon for some extra shading.
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.75f, h * 0.18f, w * 0.3f, Color.argb(70, 250, 245, 210), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        c.drawCircle(w * 0.75f, h * 0.18f, w * 0.3f, glow)
    }
}
