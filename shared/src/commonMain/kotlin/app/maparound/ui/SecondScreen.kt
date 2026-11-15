package app.maparound.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * A second screen the map can be shown on, full size, while the phone keeps its own: a monitor or
 * TV by cable, or a wireless display. Where the platform has one (Android); null elsewhere.
 */
interface SecondScreen {
    /** The connected screen's name, or null when there's none. */
    val display: StateFlow<String?>

    /** Whether the map is on it. */
    val showing: StateFlow<Boolean>

    fun show(on: Boolean)
}

val LocalSecondScreen = staticCompositionLocalOf<SecondScreen?> { null }
