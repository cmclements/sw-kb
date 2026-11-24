package app.satisfyingsand.ui

import android.content.Context

/** User settings, persisted in SharedPreferences. Volumes are 0..100. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sfxVolume: Int
        get() = prefs.getInt(KEY_SFX, 80)
        set(v) = prefs.edit().putInt(KEY_SFX, v.coerceIn(0, 100)).apply()

    /** Stored now; background music itself is still to come. */
    var musicVolume: Int
        get() = prefs.getInt(KEY_MUSIC, 60)
        set(v) = prefs.edit().putInt(KEY_MUSIC, v.coerceIn(0, 100)).apply()

    private companion object {
        const val KEY_SFX = "sfx_volume"
        const val KEY_MUSIC = "music_volume"
    }
}
