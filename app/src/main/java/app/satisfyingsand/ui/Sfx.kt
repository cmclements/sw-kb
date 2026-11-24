package app.satisfyingsand.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny synthesized sound kit. Sounds are generated as WAV files in the cache dir on first use and
 * played through SoundPool, so the app ships with no audio assets.
 *
 *  - A seamless "sand friction" loop whose volume follows how much sand is flowing.
 *  - Soft xylophone-like tones, one pentatonic note per sand color, played very quietly while a
 *    box is pulling its color, giving the hiss a faint musical shimmer.
 *  - Pops (rising pitch as the picture drains), a dock click and a win chime.
 */
class Sfx(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val loaded = HashSet<Int>()
    private val pops = IntArray(POP_NOTES.size)
    private val tones = IntArray(TONE_NOTES.size)
    private var friction = 0
    private var win = 0
    private var dock = 0
    private var frictionStream = 0
    private var frictionLevel = 0f

    /** Master volume for sound effects, 0..1. */
    var volume = 0.8f

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        POP_NOTES.forEachIndexed { i, semis -> pops[i] = load(dir, "pop_v2_$i", pop(440.0 * 2.0.pow(semis / 12.0))) }
        TONE_NOTES.forEachIndexed { i, semis -> tones[i] = load(dir, "tone_v1_$i", xylo(261.63 * 2.0.pow(semis / 12.0))) }
        friction = load(dir, "friction_v2", sandLoop())
        win = load(dir, "win_v2", chime())
        dock = load(dir, "dock_v2", click())
    }

    private fun ready(id: Int) = synchronized(loaded) { id in loaded }

    /** [progress] 0..1: pops climb a pentatonic scale as the picture drains. */
    fun pop(progress: Float) {
        if (volume <= 0f) return
        val i = (progress * (pops.size - 1)).toInt().coerceIn(0, pops.size - 1)
        pool.play(pops[i], 0.9f * volume, 0.9f * volume, 1, 0, 1f)
    }

    /**
     * Sets how much sand is flowing, 0..1. Call every frame; the friction loop fades smoothly
     * toward this level.
     */
    fun flow(level: Float, dt: Float) {
        val target = level.coerceIn(0f, 1f)
        val k = if (target > frictionLevel) 6f else 3f // fade in a bit faster than out
        frictionLevel += (target - frictionLevel) * (1f - exp(-k * dt))
        val v = FRICTION_MAX * frictionLevel.pow(0.7f) * volume
        if (frictionStream == 0) {
            if (v > 0.002f && ready(friction)) frictionStream = pool.play(friction, v, v, 0, -1, 1f)
        } else {
            pool.setVolume(frictionStream, v, v)
        }
    }

    fun stopFlow() {
        if (frictionStream != 0) pool.stop(frictionStream)
        frictionStream = 0
        frictionLevel = 0f
    }

    /** Number of distinct color tones available. */
    val toneCount: Int get() = tones.size

    /** A very soft xylophone note for a sand color ([note] in 0 until [toneCount]). */
    fun tone(note: Int, strength: Float = 1f) {
        if (volume <= 0f) return
        val v = TONE_VOLUME * strength * volume * (0.75f + 0.25f * Random.nextFloat())
        val pan = 0.15f * (Random.nextFloat() * 2f - 1f)
        pool.play(tones[note.coerceIn(0, tones.size - 1)], v * (1f - pan), v * (1f + pan), 0, 0, 1f)
    }

    fun dock() { if (volume > 0f) pool.play(dock, 0.45f * volume, 0.45f * volume, 0, 0, 1f) }
    fun win() { if (volume > 0f) pool.play(win, volume, volume, 2, 0, 1f) }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis

    private fun load(dir: File, name: String, samples: FloatArray): Int {
        val f = File(dir, "$name.wav")
        if (!f.exists()) FileOutputStream(f).use { it.write(wav(samples)) }
        return pool.load(f.path, 1)
    }

    /**
     * Seamless 2 s loop of pouring sand: thousands of tiny overlapping soft-edged bursts of
     * filtered noise (no hard transients, so no clicks), band-limited to a gentle hiss with a
     * slow random swell so the loop doesn't sound mechanical.
     */
    private fun sandLoop(): FloatArray {
        val n = (RATE * 2.0).toInt()
        val out = FloatArray(n)
        val rnd = Random(42)
        val grainsPerSec = 7000
        repeat((grainsPerSec * 2.0).toInt()) {
            val start = rnd.nextInt(n)
            val len = (RATE * (0.004 + rnd.nextDouble() * 0.012)).toInt()
            val amp = (0.6 + rnd.nextDouble() * 0.4).toFloat()
            var lp = 0.0
            val cutoff = 0.15 + rnd.nextDouble() * 0.35 // per-grain brightness
            for (i in 0 until len) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / len) // Hann window: soft attack and release
                lp += (rnd.nextDouble() * 2 - 1 - lp) * cutoff
                out[(start + i) % n] += (lp * w * amp).toFloat() // wraps, so the loop is seamless
            }
        }
        // Gentle band-limit (remove rumble and fizz), then a slow, loop-periodic swell.
        var hpPrev = 0f; var hpOut = 0f; var lp = 0f
        for (i in 0 until n) {
            val x = out[i]
            hpOut = 0.97f * (hpOut + x - hpPrev); hpPrev = x
            lp += (hpOut - lp) * 0.32f
            val swell = 0.85f + 0.15f * sin(2 * PI * i / n * 3).toFloat()
            out[i] = lp * swell
        }
        val peak = out.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        for (i in 0 until n) out[i] = out[i] / peak * 0.6f
        return out
    }

    /** Soft mallet-on-wood note: fundamental plus the xylophone's ~3.9x partial, quick decay. */
    private fun xylo(freq: Double): FloatArray {
        val n = (RATE * 0.9).toInt()
        val rnd = Random(freq.toInt())
        var lp = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val attack = 1 - exp(-t * 600)
            val body = sin(2 * PI * freq * t) * exp(-t * 7)
            val partial = 0.35 * sin(2 * PI * freq * 3.93 * t) * exp(-t * 22)
            val octave = 0.12 * sin(2 * PI * freq * 2 * t) * exp(-t * 12)
            lp += ((if (t < 0.004) rnd.nextDouble() * 2 - 1 else 0.0) - lp) * 0.2
            ((body + partial + octave) * attack * 0.5 + lp * 0.15).toFloat()
        }
    }

    private fun pop(freq: Double): FloatArray {
        val n = (RATE * 0.22).toInt()
        val rnd = Random(1)
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            val f = freq * (0.7 + 0.6 * (1 - exp(-t * 40)))
            val env = exp(-t * 18) * (1 - exp(-t * 900))
            val tone = sin(2 * PI * f * t) + 0.35 * sin(2 * PI * f * 2.01 * t)
            val click = if (t < 0.006) (rnd.nextDouble() * 2 - 1) * (1 - t / 0.006) * 0.5 else 0.0
            ((tone * 0.5 * env) + click).toFloat()
        }
    }

    private fun click(): FloatArray {
        val n = (RATE * 0.06).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / RATE
            (sin(2 * PI * 1600 * t) * exp(-t * 90) * 0.4 * (1 - exp(-t * 2000))).toFloat()
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
        /** Major-pentatonic steps relative to C4 for the color tones (two and a half octaves). */
        private val TONE_NOTES = intArrayOf(0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28)
        private const val FRICTION_MAX = 0.32f
        private const val TONE_VOLUME = 0.09f
    }
}
