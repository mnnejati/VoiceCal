package ir.appointment.voice.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
        typography = AppTypography,
        content = content
    )
}
