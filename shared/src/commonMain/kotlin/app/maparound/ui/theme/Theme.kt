package app.maparound.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2C8),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    tertiary = Color(0xFF3D6373),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD5AD),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005236),
    onPrimaryContainer = Color(0xFFA6F2C8),
    secondary = Color(0xFFB3CCBE),
    tertiary = Color(0xFFA5CCDF),
)

/** The platform's own colours if it has them (Android 12+: from the wallpaper), else null. */
@Composable
internal expect fun platformColorScheme(dark: Boolean): ColorScheme?

@Composable
fun MapAroundTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = platformColorScheme(dark) ?: if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
