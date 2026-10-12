package com.linmjt.whisperofsol.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── Christmas-themed primary aliases (kept for backward compatibility) ─
// Kept at file top-level so older call-sites don't break.
val ChristmasRed    = Red700
val ChristmasGreen  = Green700
val ChristmasGold   = Gold500
val MidnightBlue    = Midnight800
val SnowWhite       = Snow50

// ─── Light ColorScheme ────────────────────────────────────────────────
// Daytime Christmas morning — snow backgrounds, festive red CTAs.
private val LightColors = lightColorScheme(

    primary             = Red700,
    onPrimary           = Snow50,
    primaryContainer    = Red100,
    onPrimaryContainer  = Red900,

    secondary           = Green700,
    onSecondary         = Snow50,
    secondaryContainer  = Green100,
    onSecondaryContainer = Green900,

    background           = Snow50,
    onBackground        = Midnight900,

    surface             = Snow50,
    onSurface           = Midnight900,
    surfaceVariant      = Snow200,
    onSurfaceVariant    = Snow700,
    surfaceTint         = Red700,

    tertiary            = Gold500,
    onTertiary          = Midnight900,
    tertiaryContainer   = Gold200,
    onTertiaryContainer = Gold700,

    error               = Red500,
    onError             = Snow50,
    errorContainer      = Red100,
    onErrorContainer    = Red900,

    outline             = Snow300,
    outlineVariant      = Snow200,

    inverseSurface      = Midnight800,
    inverseOnSurface    = Snow100,
    inversePrimary      = Red300,

    scrim               = OverlayBlack,
)

// ─── Dark ColorScheme ─────────────────────────────────────────────────
// Christmas Eve — deep midnight blue surface, gold pops on dark.
private val DarkColors = darkColorScheme(

    primary             = Red400,                   // lighter red pops on dark
    onPrimary           = Snow900,
    primaryContainer    = Red800,
    onPrimaryContainer  = Red100,

    secondary           = Green400,
    onSecondary         = Snow900,
    secondaryContainer  = Green800,
    onSecondaryContainer = Green100,

    background          = Midnight900,
    onBackground        = Snow50,

    surface             = Midnight800,
    onSurface           = Snow100,
    surfaceVariant      = Midnight700,
    onSurfaceVariant    = Midnight200,
    surfaceTint         = Red400,

    tertiary            = Gold300,
    onTertiary          = Midnight900,
    tertiaryContainer   = Gold700,
    onTertiaryContainer = Gold100,

    error               = Red400,
    onError             = Snow900,
    errorContainer      = Red800,
    onErrorContainer    = Red100,

    outline             = Midnight400,
    outlineVariant      = Midnight700,

    inverseSurface      = Snow100,
    inverseOnSurface    = Midnight800,
    inversePrimary      = Red700,

    scrim               = OverlayBlack,
)

/**
 * WhisperOfSolTheme — top-level theme wrapper.
 *
 * Inherits system dark mode preference by default; surfaces are edge-to-edge
 * so the Christmas gradient bleeds into the status bar area. Status-bar
 * appearance is automatically flipped to match the surface brightness.
 */
@Composable
fun WhisperOfSolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            window?.let {
                it.statusBarColor = Color.Transparent.toArgb()
                it.navigationBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = WhisperOfSolTypography,
        shapes      = WhisperOfSolShapes,
        content     = content
    )
}

/**
 * Brand helpers — central access to the three brand colors regardless of theme.
 * Components that need to stay on-brand (logo, gradients) reference these
 * rather than colorScheme.primary / secondary to keep visual identity stable
 * across light & dark modes.
 */
object WhisperOfSolBrand {
    val red:   Color @Composable get() = Red700
    val green: Color @Composable get() = Green700
    val gold:  Color @Composable get() = Gold500
}