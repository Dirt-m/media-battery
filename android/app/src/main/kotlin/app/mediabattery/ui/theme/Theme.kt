package app.mediabattery.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The extension's palette (popup.css / options.css). Dark only, like the extension.
object SbColors {
    val Bg = Color(0xFF14181E)
    val Surface = Color(0xFF1B2027)
    val SurfaceRaised = Color(0xFF232A32)
    val Ink = Color(0xFFE8EAED)
    val Muted = Color(0xFF9AA3AD)
    val Line = Color(0xFF2B323B)
    val Green = Color(0xFF36C06F)
    val Accent = Color(0xFF2FA45A)
    val Amber = Color(0xFFF0A93B)
    val Red = Color(0xFFEF5350)
}

private val Scheme = darkColorScheme(
    primary = SbColors.Accent,
    onPrimary = Color.White,
    background = SbColors.Bg,
    onBackground = SbColors.Ink,
    surface = SbColors.Surface,
    onSurface = SbColors.Ink,
    surfaceVariant = SbColors.SurfaceRaised,
    onSurfaceVariant = SbColors.Muted,
    outline = SbColors.Line,
    error = SbColors.Red,
)

@Composable
fun MediaBatteryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
