package io.github.zensu357.camswap.ui.theme

import android.content.Context

object GhostCamThemePrefs {
    private const val PREFS = "ghostcam_ui"
    private const val KEY_THEME = "theme_mode"

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, SYSTEM) ?: SYSTEM

    fun set(context: Context, mode: String) {
        val safe = if (mode == LIGHT || mode == DARK || mode == SYSTEM) mode else SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, safe).apply()
    }
}
