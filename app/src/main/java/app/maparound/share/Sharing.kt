package app.maparound.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.maparound.routing.RouteProfile
import app.maparound.tracks.Gpx
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import app.maparound.ui.Fmt
import java.io.File
import kotlin.math.roundToInt

/** Sending points and routes to other apps. */
object Sharing {

    /** Name, coordinates, Google Maps and OpenStreetMap links: opens for anyone, in any app. */
    fun sharePointText(context: Context, lat: Double, lon: Double, label: String?) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, SharedLinks.pointText(lat, lon, label))
            .putExtra(Intent.EXTRA_SUBJECT, label ?: "Location")
        start(context, Intent.createChooser(send, "Share location"))
    }

    /** A GPX file with one waypoint, for GPS devices and apps like OsmAnd or Garmin. */
    fun sharePointGpx(context: Context, lat: Double, lon: Double, label: String?) {
        val file = File(sharedDir(context), slug(label ?: "location") + ".gpx")
        file.bufferedWriter().use { Gpx.writeWaypoint(label, lat, lon, it) }
        shareFile(context, file, "application/gpx+xml", SharedLinks.pointText(lat, lon, label), label ?: "Location")
    }

    /** Hands the point to another map or navigation app (geo: link). */
    fun openPointElsewhere(context: Context, lat: Double, lon: Double, label: String?) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(SharedLinks.geoUri(lat, lon, label)))
        start(context, Intent.createChooser(view, "Open with"))
    }

    /**
     * A route or track: the GPX file plus text with links. Planned routes get a BRouter-Web link
     * (same route, recalculated in any browser) and Google Maps directions.
     */
    fun shareRoute(context: Context, track: Track, gpxFile: File? = null) {
        val file = gpxFile?.takeIf { it.isFile } ?: File(sharedDir(context), slug(track.name) + ".gpx").also { f ->
            f.bufferedWriter().use { Gpx.write(track, it) }
        }
        shareFile(context, file, "application/gpx+xml", routeText(track), track.name)
    }

    /** Saved places as one GPX file of waypoints (opens in OsmAnd, Garmin, Gaia, Google Earth, …). */
    fun sharePlaces(context: Context, places: List<app.maparound.places.Place>, title: String) {
        val file = File(sharedDir(context), slug(title) + ".gpx")
        file.bufferedWriter(Charsets.UTF_8).use { app.maparound.places.Places.writeGpx(places, it, title) }
        val text = if (places.size == 1) {
            SharedLinks.pointText(places[0].lat, places[0].lon, places[0].name)
        } else {
            "$title: ${places.size} places (GPX attached)"
        }
        shareFile(context, file, "application/gpx+xml", text, title)
    }

    /** Only the links, for chat apps that drop attachments' text. */
    fun shareRouteLink(context: Context, track: Track) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, routeText(track))
            .putExtra(Intent.EXTRA_SUBJECT, track.name)
        start(context, Intent.createChooser(send, "Share route link"))
    }

    fun hasRouteLink(track: Track): Boolean = track.waypoints.size >= 2 && track.kind != TrackKind.DRAWN

    fun routeText(track: Track): String = buildString {
        val s = track.stats
        append(track.name).append(" · ").append(Fmt.distance(s.distanceM.toFloat()))
        s.ascentM?.let { append(" · ↑ ").append(it.roundToInt()).append(" m") }
        appendLine()
        if (hasRouteLink(track)) {
            SharedLinks.brouterWebLink(track.waypoints, track.profile)?.let { appendLine("Open the route: $it") }
            SharedLinks.googleDirectionsLink(track.waypoints, track.profile ?: RouteProfile.HIKE)?.let { appendLine("Google Maps: $it") }
        } else {
            track.points.firstOrNull()?.let { appendLine("Start: ${SharedLinks.googleLink(it.lat, it.lon)}") }
        }
        append("(GPX file attached, if your app received it)")
    }

    private fun shareFile(context: Context, file: File, mime: String, text: String, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(send, "Share").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun sharedDir(context: Context): File = File(context.cacheDir, "shared").apply {
        mkdirs()
        // Keep the folder small: files only need to live until the receiving app has read them.
        listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3_600_000L }?.forEach { it.delete() }
    }

    private fun slug(name: String) =
        name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(' ', '_').take(50).ifEmpty { "shared" }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can handle this", Toast.LENGTH_SHORT).show()
        }
    }
}
