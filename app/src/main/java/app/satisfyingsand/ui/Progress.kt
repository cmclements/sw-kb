package app.satisfyingsand.ui

import android.content.Context

/**
 * Remembers which difficulty/color levels have been completed for each library picture, and the
 * best time and moves for each. Stored in SharedPreferences as "picture|difficulty|colors" ->
 * "seconds,moves".
 */
class Progress(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    data class Best(val seconds: Float, val moves: Int)

    private fun key(picture: String, difficulty: String, colors: Int) = "$picture|$difficulty|$colors"

    fun best(picture: String, difficulty: String, colors: Int): Best? {
        val v = prefs.getString(key(picture, difficulty, colors), null) ?: return null
        val parts = v.split(',')
        return Best(parts.getOrNull(0)?.toFloatOrNull() ?: return null, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    fun isDone(picture: String, difficulty: String, colors: Int) = best(picture, difficulty, colors) != null

    /** Records a win, keeping the fastest time. Returns true if it's a new best. */
    fun record(picture: String, difficulty: String, colors: Int, seconds: Float, moves: Int): Boolean {
        val old = best(picture, difficulty, colors)
        if (old != null && old.seconds <= seconds) return false
        prefs.edit().putString(key(picture, difficulty, colors), "$seconds,$moves").apply()
        return true
    }

    fun completedCount(picture: String, difficulties: List<String>, colors: IntArray): Int =
        difficulties.sumOf { d -> colors.count { c -> isDone(picture, d, c) } }
}
