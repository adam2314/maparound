package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.places.Place
import app.maparound.routing.GeoPoint
import app.maparound.search.SearchResult

/** What the shared screens ask of the platform besides data: sharing to other apps. */
interface PlatformActions {
    /** Name, coordinates and map links as text. */
    fun sharePointText(lat: Double, lon: Double, label: String?)

    /** The point as a GPX waypoint file (for Garmin, OsmAnd…). */
    fun sharePointGpx(lat: Double, lon: Double, label: String?)

    /** Places as a GPX file. */
    fun sharePlaces(places: List<Place>, title: String)
}

val LocalPlatform = staticCompositionLocalOf<PlatformActions> { error("No PlatformActions provided") }

/** Place search with the platform's places, offline maps and internet (see SearchEngine). */
interface Searcher {
    fun quick(query: String, near: GeoPoint?): List<SearchResult>

    /** Blocking. */
    fun offline(query: String, center: GeoPoint, isActive: () -> Boolean): List<SearchResult>

    /** Blocking; throws when offline. */
    fun online(query: String, near: GeoPoint?): List<SearchResult>
}

val LocalSearcher = staticCompositionLocalOf<Searcher> { error("No Searcher provided") }

/**
 * A launcher for the platform's file picker. [onPicked] gets the chosen file's bytes, or null
 * when nothing was picked or it couldn't be read.
 */
@Composable
expect fun rememberFilePicker(mimeTypes: List<String>, onPicked: (ByteArray?) -> Unit): () -> Unit

/** Android's back button / gesture while [enabled]; iOS has none (screens show their own back). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
