package app.satisfyingsand.play

import android.content.Context
import app.satisfyingsand.R
import kotlin.math.roundToLong

/**
 * Leaderboard rules. Each library picture has one leaderboard: fastest time on its ranked
 * level ([RANKED_DIFFICULTY] with [RANKED_COLORS] colors). A global leaderboard ranks the total of
 * a player's best ranked times across *all* library pictures, submitted only once every picture's
 * ranked level is complete (so skipping pictures can't produce a smaller total).
 */
object Leaderboards {
    const val RANKED_DIFFICULTY = "medium"
    const val RANKED_COLORS = 8

    private val byPicture = mapOf(
        "Sunset" to R.string.leaderboard_sunset,
        "Mountains" to R.string.leaderboard_mountains,
        "Heart" to R.string.leaderboard_heart,
        "Rainbow" to R.string.leaderboard_rainbow,
        "Smiley" to R.string.leaderboard_smiley,
        "Balloon" to R.string.leaderboard_balloon,
        "Mushroom" to R.string.leaderboard_mushroom,
        "Night City" to R.string.leaderboard_night_city,
    )

    fun isRanked(difficulty: String, colors: Int) = difficulty == RANKED_DIFFICULTY && colors == RANKED_COLORS

    /** Leaderboard ID for a library picture, or null if it isn't configured. */
    fun forPicture(ctx: Context, pictureName: String): String? =
        byPicture[pictureName]?.let { ctx.getString(it) }?.takeIf { it.isNotBlank() }

    fun total(ctx: Context): String? = ctx.getString(R.string.leaderboard_total_time).takeIf { it.isNotBlank() }

    /**
     * Total of the best ranked times (ms) across [pictureNames], or null unless every one has a
     * best time. [bestSeconds] looks up a picture's best ranked time.
     */
    fun totalMillis(pictureNames: List<String>, bestSeconds: (String) -> Float?): Long? {
        var total = 0L
        for (name in pictureNames) total += toMillis(bestSeconds(name) ?: return null)
        return total
    }

    /**
     * Play Games "Time" leaderboards take scores in milliseconds (66032 is shown as 1:06.03).
     * Rounded, not truncated: a Float time like 83.456 s is stored as 83.45599... and must still
     * post as 83456 ms.
     */
    fun toMillis(seconds: Float): Long = (seconds.toDouble() * 1000.0).roundToLong().coerceAtLeast(1L)
}
