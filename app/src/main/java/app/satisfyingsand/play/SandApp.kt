package app.satisfyingsand.play

import android.app.Application
import android.util.Log
import app.satisfyingsand.BuildConfig
import com.google.android.gms.games.PlayGamesSdk

/** Application entry point: Play Games must be initialized here (once per process). */
class SandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.PLAY_GAMES_ENABLED) {
            try { PlayGamesSdk.initialize(this) } catch (e: Exception) { Log.w("PlayGames", "init failed", e) }
        }
    }
}
