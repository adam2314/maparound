package app.maparound.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** iOS has no system colour scheme for apps: MapAround's own green. */
@Composable
internal actual fun platformColorScheme(dark: Boolean): ColorScheme? = null
