package ir.appointment.voice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val LightColors = lightColorScheme(
    primary = VividPurple,
    onPrimary = SurfaceWhite,
    secondary = AccentTeal,
    background = BackgroundLight,
    surface = SurfaceWhite,
    error = DangerRed,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

private val DarkColors = darkColorScheme(
    primary = VividPurple,
    onPrimary = SurfaceWhite,
    secondary = AccentTeal,
    background = DarkBackground,
    surface = DarkSurface,
    error = DangerRed,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary
)

private val AppTypography = Typography()

@Composable
fun AppointmentVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography
    ) {
        // All content in this app is Persian, so it must always read right-to-left —
        // regardless of the phone's system UI language (which many users, especially
        // testers, run in English). Without this, Row/Icon/Alignment ordering follows
        // the SYSTEM locale's direction instead of the CONTENT's actual direction.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
