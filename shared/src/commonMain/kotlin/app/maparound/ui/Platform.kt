package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.places.Place
import app.maparound.routing.GeoPoint
import app.maparound.search.SearchResult
import app.maparound.tracks.Track

/** What the shared screens ask of the platform besides data: sharing to and opening other apps. */
interface PlatformActions {
    /** Any text, through the platform's share sheet. */
    fun shareText(text: String, title: String)

    /** Name, coordinates and map links as text. */
    fun sharePointText(lat: Double, lon: Double, label: String?)

    /** The point as a GPX waypoint file (for Garmin, OsmAnd…). */
    fun sharePointGpx(lat: Double, lon: Double, label: String?)

    /** Opens the point in another map or navigation app. */
    fun openPointElsewhere(lat: Double, lon: Double, label: String?)

    /** Places as a GPX file. */
    fun sharePlaces(places: List<Place>, title: String)

    /** A route or track as its GPX file ([gpxPath] if it is saved) plus text with links. */
    fun shareRoute(track: Track, gpxPath: String?)

    /** Only the text with links (no file). */
    fun shareRouteLink(track: Track)

    /** A text file (e.g. the list of map sources) through the share sheet, to save or send. */
    fun shareTextFile(fileName: String, mimeType: String, content: String, title: String)
}

val LocalPlatform = staticCompositionLocalOf<PlatformActions> { error("No PlatformActions provided") }

/** Starts and stops GPS track recording (state in TrackRecorder). */
interface RecordingControl {
    /** An action that starts recording, asking for the permissions it needs first. */
    @Composable
    fun rememberStarter(hasLocation: Boolean, requestLocation: () -> Unit): () -> Unit

    fun stop()
}

val LocalRecordingControl = staticCompositionLocalOf<RecordingControl> { error("No RecordingControl provided") }

/** Place search with the platform's places, offline maps and internet (see SearchEngine). */
interface Searcher {
    fun quick(query: String, near: GeoPoint?): List<SearchResult>

    /** Blocking. */
    fun offline(query: String, center: GeoPoint, isActive: () -> Boolean): List<SearchResult>

    /** Blocking; throws when offline. */
    fun online(query: String, near: GeoPoint?): List<SearchResult>
}

val LocalSearcher = staticCompositionLocalOf<Searcher> { error("No Searcher provided") }

/** A file the user picked: its name (if the platform tells) and contents. */
class PickedFile(val name: String?, val bytes: ByteArray)

/**
 * A launcher for the platform's file picker. [onPicked] gets the chosen file, or null when
 * nothing was picked or it couldn't be read.
 */
@Composable
expect fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit

/** Android's back button / gesture while [enabled]; iOS has none (screens show their own back). */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
