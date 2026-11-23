package app.satisfyingsand.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny synthesized sound kit. Sounds are generated as WAV files in the cache dir on first use and
 * played through SoundPool, so the app ships with no audio assets.
 */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val pops = IntArray(POP_NOTES.size)
    private val grains = IntArray(3)
    private var win = 0
    private var dock = 0
    var enabled = true

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        POP_NOTES.forEachIndexed { i, semis ->
            pops[i] = load(dir, "pop_v2_$i", pop(440.0 * 2.0.pow(semis / 12.0)))
        }
        for (i in grains.indices) grains[i] = load(dir, "grain_v2_$i", grain(i))
        win = load(dir, "win_v2", chime())
        dock = load(dir, "dock_v2", click())
    }

    /** [progress] 0..1 — pops climb a pentatonic scale as the picture drains. */
    fun pop(progress: Float) {
        if (!enabled) return
        val i = (progress * (pops.size - 1)).toInt().coerceIn(0, pops.size - 1)
        pool.play(pops[i], 0.9f, 0.9f, 1, 0, 1f)
    }

    fun grain() {
        if (!enabled) return
        val v = 0.18f + Random.nextFloat() * 0.1f
        pool.play(grains[Random.nextInt(grains.size)], v, v, 0, 0, 0.85f + Random.nextFloat() * 0.3f)
    }

    fun dock() { if (enabled) pool.play(dock, 0.5f, 0.5f, 0, 0, 1f) }
    fun win() { if (enabled) pool.play(win, 1f, 1f, 2, 0, 1f) }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis

    private fun load(dir: File, name: String, samples: FloatArray): Int {
        val f = File(dir, "$name.wav")
        if (!f.exists()) FileOutputStream(f).use { it.write(wav(samples)) }
        return pool.load(f.path, 1)
    }

    private fun pop(freq: Double): FloatArray {
        val n = (RATE * 0.22).toInt()
        val rnd = Random(1)
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            // Bubbly upward sweep + a soft harmonic, with a fast decay.
            val f = freq * (0.7 + 0.6 * (1 - exp(-t * 40)))
            val env = exp(-t * 18) * (1 - exp(-t * 900))
            val tone = sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2.01 * t)
            val click = if (t < 0.006) (rnd.nextDouble() * 2 - 1) * (1 - t / 0.006) * 0.5 else 0.0
            ((tone * 0.5 * env) + click).toFloat()
        }
    }

    private fun grain(variant: Int): FloatArray {
        val n = (RATE * 0.03).toInt()
        val rnd = Random(10 + variant)
        var lp = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val env = exp(-t * 160)
            // High-passed noise tick: sounds like a single grain of sand landing.
            val noise = rnd.nextDouble() * 2 - 1
            lp += (noise - lp) * 0.25
            ((noise - lp) * env).toFloat()
        }
    }

    private fun click(): FloatArray {
        val n = (RATE * 0.06).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            (sin(2 * PI * 1600 * t) * exp(-t * 90) * 0.4).toFloat()
        }
    }

    private fun chime(): FloatArray {
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.5, 1318.5)
        val step = 0.11
        val n = (RATE * (step * notes.size + 0.9)).toInt()
        val out = FloatArray(n)
        notes.forEachIndexed { k, f ->
            val start = (k * step * RATE).toInt()
            for (i in start until n) {
                val t = (i - start).toDouble() / RATE
                val env = exp(-t * 4.5) * (1 - exp(-t * 400))
                out[i] += (0.22 * env * (sin(2 * PI * f * t) + 0.3 * sin(2 * PI * f * 3 * t))).toFloat()
            }
        }
        return out
    }

    private fun wav(samples: FloatArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        return header.array() + data.array()
    }

    companion object {
        private const val RATE = 44100
        /** Major-pentatonic steps relative to A4, rising as the picture drains. */
        private val POP_NOTES = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24)
    }
}
