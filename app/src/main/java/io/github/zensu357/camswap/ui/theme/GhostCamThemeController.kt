package io.github.zensu357.camswap.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class GhostCamThemeMode { SYSTEM, LIGHT, DARK }

object GhostCamThemeController {
    private const val PREFS = "ghostcam_ui"
    private const val KEY_THEME = "theme_mode"

    var mode by mutableStateOf(GhostCamThemeMode.SYSTEM)
        private set

    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        mode = runCatching {
            GhostCamThemeMode.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_THEME, GhostCamThemeMode.SYSTEM.name)
                    ?: GhostCamThemeMode.SYSTEM.name
            )
        }.getOrDefault(GhostCamThemeMode.SYSTEM)
        initialized = true
    }

    fun set(context: Context, newMode: GhostCamThemeMode) {
        mode = newMode
        initialized = true
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, newMode.name)
            .apply()
    }
}
