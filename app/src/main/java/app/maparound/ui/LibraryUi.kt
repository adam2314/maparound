package app.maparound.ui

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.maparound.AppGraph
import app.maparound.map.MapController
import app.maparound.places.Place

// Android-side pieces around the shared places screens (until Routes moves to shared code).

// ---- library: routes or places --------------------------------------------------------------------------------

/** The Library tab: routes & tracks, or saved places. */
@Composable
fun LibraryScreen(hasLocation: Boolean, requestLocation: () -> Unit, snackbar: SnackbarHostState, onShowMap: () -> Unit) {
    var places by rememberSaveable { mutableStateOf(false) }
    val header: @Composable () -> Unit = {
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(selected = !places, onClick = { places = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Routes") }
            SegmentedButton(selected = places, onClick = { places = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Places") }
        }
    }
    if (places) {
        PlacesScreen(hasLocation, snackbar, onShowMap, header)
    } else {
        RoutesScreen(hasLocation, requestLocation, snackbar, onShowMap, header)
    }
}

/** Place markers for the mapsforge map. */
fun placeMapMarkers(places: List<Place>): List<MapController.PlaceMarker> =
    places.map { MapController.PlaceMarker(it.id, it.lat, it.lon, it.name, it.category.emoji, it.category.argb.toInt()) }

/** A point in the user's chosen format (for Android code outside composition). */
fun formatPoint(lat: Double, lon: Double): String = formatPoint(lat, lon, AppGraph.prefs.coordFormat.value)
