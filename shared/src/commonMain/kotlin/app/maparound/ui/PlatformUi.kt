package app.maparound.ui

import androidx.compose.runtime.Composable

/** Keeps the screen on while this is in the composition (speedometer, navigation). */
@Composable
expect fun KeepScreenOn()
