package io.github.zensu357.camswap.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** UI-only theme preference. Does not touch Xposed/module runtime state. */
enum class GhostCamThemeMode { SYSTEM, LIGHT, DARK }

object GhostCamThemePrefs {
    private const val PREFS = "ghostcam_ui"
    private const val KEY_THEME = "theme_mode"

    var mode by mutableStateOf(GhostCamThemeMode.SYSTEM)
        private set

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, GhostCamThemeMode.SYSTEM.name)
        mode = runCatching { GhostCamThemeMode.valueOf(saved ?: GhostCamThemeMode.SYSTEM.name) }
            .getOrDefault(GhostCamThemeMode.SYSTEM)
        initialized = true
    }

    fun set(context: Context, value: GhostCamThemeMode) {
        mode = value
        initialized = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, value.name)
            .apply()
    }
}
