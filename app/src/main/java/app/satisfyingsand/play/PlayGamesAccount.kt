package app.satisfyingsand.play

import android.app.Activity
import android.util.Log
import app.satisfyingsand.BuildConfig
import com.google.android.gms.games.PlayGames

/**
 * Google Play Games account: automatic sign-in (Play Games v2 signs players in at launch), an
 * explicit sign-in button for players who declined, score submission and leaderboard screens.
 * Everything is a no-op until a Play Games project ID is configured (see README).
 */
class PlayGamesAccount(private val activity: Activity) {
    val enabled: Boolean = BuildConfig.PLAY_GAMES_ENABLED
    var signedIn = false
        private set
    var playerName: String? = null
        private set
    /** Called on the main thread whenever sign-in state or player name changes. */
    var onChange: (() -> Unit)? = null
    /** Called right after a successful sign-in, e.g. to upload scores earned while signed out. */
    var onSignedIn: (() -> Unit)? = null

    fun refresh() {
        if (!enabled) return
        safely {
            PlayGames.getGamesSignInClient(activity).isAuthenticated.addOnCompleteListener { t ->
                update(t.isSuccessful && t.result.isAuthenticated)
            }
        }
    }

    fun signIn() {
        if (!enabled) return
        safely {
            PlayGames.getGamesSignInClient(activity).signIn().addOnCompleteListener { t ->
                update(t.isSuccessful && t.result.isAuthenticated)
            }
        }
    }

    private fun update(now: Boolean) {
        val wasSignedIn = signedIn
        signedIn = now
        if (now) {
            safely {
                PlayGames.getPlayersClient(activity).currentPlayer.addOnSuccessListener { p ->
                    playerName = p.displayName
                    onChange?.invoke()
                }
            }
            if (!wasSignedIn) onSignedIn?.invoke()
        } else {
            playerName = null
        }
        onChange?.invoke()
    }

    /** Submits a time (Play Games keeps each player's best automatically). */
    fun submitTime(leaderboardId: String?, millis: Long) {
        if (!enabled || !signedIn || leaderboardId == null) return
        safely { PlayGames.getLeaderboardsClient(activity).submitScore(leaderboardId, millis) }
    }

    fun showLeaderboard(leaderboardId: String) {
        if (!enabled) return
        safely {
            PlayGames.getLeaderboardsClient(activity).getLeaderboardIntent(leaderboardId)
                .addOnSuccessListener { @Suppress("DEPRECATION") activity.startActivityForResult(it, RC_LEADERBOARD) }
        }
    }

    fun showAllLeaderboards() {
        if (!enabled) return
        safely {
            PlayGames.getLeaderboardsClient(activity).allLeaderboardsIntent
                .addOnSuccessListener { @Suppress("DEPRECATION") activity.startActivityForResult(it, RC_LEADERBOARD) }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: Exception) { Log.w(TAG, "Play Games call failed", e) }
    }

    companion object {
        const val RC_LEADERBOARD = 9001
        private const val TAG = "PlayGames"
    }
}
