package app.satisfyingsand.game

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Turns an arbitrary ARGB image into a grid of sand-grain colours.
 *
 * 1. Pick a row count that matches the image's aspect ratio (clamped), and centre-crop the rest.
 * 2. Area-average every source pixel that falls inside each grid cell (in linear light, so
 *    averages don't come out muddy/dark).
 * 3. Bucket the averaged colours into a small palette with k-means in Oklab space, so similar
 *    shades collapse into one "sand colour".
 * 4. Give each grain a tiny brightness jitter so the result reads as sand, not flat pixels.
 */
object Pixelator {

    class Result(
        val cols: Int,
        val rows: Int,
        /** Final grain colours (opaque ARGB), row-major, length cols*rows. */
        val grains: IntArray,
        /** The bucketed palette (opaque ARGB). */
        val palette: IntArray,
        /** Palette index of each grain. */
        val bucket: IntArray,
    )

    fun rowsFor(srcW: Int, srcH: Int, cols: Int, minRows: Int, maxRows: Int): Int {
        val ideal = (cols.toDouble() * srcH / srcW).roundToInt()
        return ideal.coerceIn(minRows, maxRows)
    }

    fun pixelate(
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        cols: Int,
        minRows: Int,
        maxRows: Int,
        buckets: Int = 8,
        jitter: Float = 0.05f,
        seed: Int = 7,
    ): Result {
        require(pixels.size >= srcW * srcH && srcW > 0 && srcH > 0)
        val rows = rowsFor(srcW, srcH, cols, minRows, maxRows)

        // Centre-crop the source to the grid's aspect ratio.
        val targetAspect = cols.toDouble() / rows
        var cropW = srcW.toDouble()
        var cropH = srcH.toDouble()
        if (cropW / cropH > targetAspect) cropW = cropH * targetAspect else cropH = cropW / targetAspect
        val cropX = (srcW - cropW) / 2.0
        val cropY = (srcH - cropH) / 2.0

        val avg = IntArray(cols * rows)
        for (gy in 0 until rows) {
            val y0 = (cropY + gy * cropH / rows).toInt().coerceIn(0, srcH - 1)
            val y1 = (cropY + (gy + 1) * cropH / rows).toInt().coerceIn(y0 + 1, srcH)
            for (gx in 0 until cols) {
                val x0 = (cropX + gx * cropW / cols).toInt().coerceIn(0, srcW - 1)
                val x1 = (cropX + (gx + 1) * cropW / cols).toInt().coerceIn(x0 + 1, srcW)
                var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0
                for (y in y0 until y1) {
                    val row = y * srcW
                    for (x in x0 until x1) {
                        val p = pixels[row + x]
                        // Treat transparency as blending onto white so PNGs with alpha look sane.
                        val a = ((p ushr 24) and 0xFF) / 255.0
                        r += LIN[(p shr 16) and 0xFF] * a + (1 - a)
                        g += LIN[(p shr 8) and 0xFF] * a + (1 - a)
                        b += LIN[p and 0xFF] * a + (1 - a)
                        wSum += 1.0
                    }
                }
                avg[gy * cols + gx] = argbFromLinear(r / wSum, g / wSum, b / wSum)
            }
        }

        val (palette, bucket) = quantize(avg, buckets, seed)
        val rnd = Random(seed)
        val grains = IntArray(avg.size) { i -> shade(palette[bucket[i]], 1f + (rnd.nextFloat() * 2f - 1f) * jitter) }
        return Result(cols, rows, grains, palette, bucket)
    }

    /** k-means (k-means++ seeded) in Oklab. Returns palette and per-pixel bucket index. */
    fun quantize(colors: IntArray, k: Int, seed: Int = 7, iterations: Int = 14): Pair<IntArray, IntArray> {
        val n = colors.size
        val lab = Array(n) { toOklab(colors[it]) }
        val kk = k.coerceIn(1, n)
        val rnd = Random(seed)

        // k-means++ initialisation.
        val centers = ArrayList<DoubleArray>(kk)
        centers += lab[rnd.nextInt(n)].copyOf()
        val d2 = DoubleArray(n) { Double.MAX_VALUE }
        while (centers.size < kk) {
            val last = centers.last()
            var total = 0.0
            for (i in 0 until n) {
                d2[i] = minOf(d2[i], dist2(lab[i], last))
                total += d2[i]
            }
            if (total <= 1e-12) break // fewer distinct colours than k
            var pick = rnd.nextDouble() * total
            var chosen = n - 1
            for (i in 0 until n) {
                pick -= d2[i]
                if (pick <= 0) { chosen = i; break }
            }
            centers += lab[chosen].copyOf()
        }

        val assign = IntArray(n)
        repeat(iterations) {
            for (i in 0 until n) {
                var best = 0
                var bestD = Double.MAX_VALUE
                for (c in centers.indices) {
                    val d = dist2(lab[i], centers[c])
                    if (d < bestD) { bestD = d; best = c }
                }
                assign[i] = best
            }
            val sums = Array(centers.size) { DoubleArray(3) }
            val counts = IntArray(centers.size)
            for (i in 0 until n) {
                val s = sums[assign[i]]
                s[0] += lab[i][0]; s[1] += lab[i][1]; s[2] += lab[i][2]
                counts[assign[i]]++
            }
            for (c in centers.indices) if (counts[c] > 0) {
                centers[c][0] = sums[c][0] / counts[c]
                centers[c][1] = sums[c][1] / counts[c]
                centers[c][2] = sums[c][2] / counts[c]
            }
        }

        // Drop empty clusters and re-index.
        val used = assign.toSet().sorted()
        val remap = IntArray(centers.size) { -1 }
        used.forEachIndexed { newIdx, old -> remap[old] = newIdx }
        val palette = IntArray(used.size) { fromOklab(centers[used[it]]) }
        return palette to IntArray(n) { remap[assign[it]] }
    }

    fun shade(argb: Int, factor: Float): Int {
        val r = (((argb shr 16) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ---- colour maths ----

    private val LIN = DoubleArray(256) { srgbToLinear(it / 255.0) }

    private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun linearToSrgb(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
    }

    private fun argbFromLinear(r: Double, g: Double, b: Double): Int {
        val ri = (linearToSrgb(r) * 255).roundToInt()
        val gi = (linearToSrgb(g) * 255).roundToInt()
        val bi = (linearToSrgb(b) * 255).roundToInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    private fun dist2(a: DoubleArray, b: DoubleArray): Double {
        val d0 = a[0] - b[0]; val d1 = a[1] - b[1]; val d2 = a[2] - b[2]
        return d0 * d0 + d1 * d1 + d2 * d2
    }

    fun toOklab(argb: Int): DoubleArray {
        val r = LIN[(argb shr 16) and 0xFF]
        val g = LIN[(argb shr 8) and 0xFF]
        val b = LIN[argb and 0xFF]
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        )
    }

    fun fromOklab(lab: DoubleArray): Int {
        val l = (lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2]).pow(3)
        val m = (lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2]).pow(3)
        val s = (lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2]).pow(3)
        return argbFromLinear(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
        )
    }
}
