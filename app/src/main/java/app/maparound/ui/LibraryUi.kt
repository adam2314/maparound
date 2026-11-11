package app.maparound.ui

import app.maparound.AppGraph

// Android-side helpers around the shared places screens.

/** A point in the user's chosen format (for Android code outside composition). */
fun formatPoint(lat: Double, lon: Double): String = formatPoint(lat, lon, AppGraph.prefs.coordFormat.value)
