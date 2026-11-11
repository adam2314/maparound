package app.maparound.ui

import app.maparound.AppGraph
import app.maparound.map.MapController
import app.maparound.places.Place

// Android-side helpers around the shared places screens.

/** Place markers for the mapsforge map. */
fun placeMapMarkers(places: List<Place>): List<MapController.PlaceMarker> =
    places.map { MapController.PlaceMarker(it.id, it.lat, it.lon, it.name, it.category.emoji, it.category.argb.toInt()) }

/** A point in the user's chosen format (for Android code outside composition). */
fun formatPoint(lat: Double, lon: Double): String = formatPoint(lat, lon, AppGraph.prefs.coordFormat.value)
