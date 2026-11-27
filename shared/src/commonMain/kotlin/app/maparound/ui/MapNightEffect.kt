package app.maparound.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.maparound.data.LocalPrefs
import app.maparound.map.MapNight
import app.maparound.map.MapSurface

/**
 * The map dark or light as chosen in Settings → Screen ([MapNight]): by default with the device's
 * dark mode, as it switches. Its own function: MapScreen must stay small (ART's method limit).
 */
@Composable
fun MapNightEffect(map: MapSurface) {
    val choice by LocalPrefs.current.mapNight.collectAsState()
    val night = choice.dark(isSystemInDarkTheme())
    // As the screen is first composed, before the effects that set the base map start: its first
    // style is already the chosen one. The map makes a new style only when this changes.
    SideEffect { map.night = night }
}
