package io.github.zensu357.camswap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.github.zensu357.camswap.GhostCamLicenseGate

private val GhostRed = Color(0xFFFF2028)
private val GhostBlack = Color(0xFF090909)
private val GhostSurface = Color(0xFF151515)
private val GhostWhite = Color(0xFFFFFFFF)

private val DarkColorScheme = darkColorScheme(
    primary = GhostRed,
    onPrimary = Color.White,
    background = GhostBlack,
    onBackground = GhostWhite,
    surface = GhostSurface,
    onSurface = GhostWhite,
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFFD0D0D0)
)

private val LightColorScheme = lightColorScheme(
    primary = GhostRed,
    onPrimary = Color.White,
    background = GhostWhite,
    onBackground = GhostBlack,
    surface = GhostWhite,
    onSurface = GhostBlack,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF4A4A4A)
)

@Composable
fun CamSwapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    GhostCamThemePrefs.initialize(context)
    val effectiveDark = when (GhostCamThemePrefs.mode) {
        GhostCamThemeMode.SYSTEM -> darkTheme
        GhostCamThemeMode.LIGHT -> false
        GhostCamThemeMode.DARK -> true
    }
    val colorScheme = if (effectiveDark) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !effectiveDark
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !effectiveDark
        }
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography) {
        GhostCamLicenseGate(content)
    }
}
