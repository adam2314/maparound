package app.maparound.share

import app.maparound.routing.RouteProfile
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import app.maparound.ui.Fmt
import kotlin.math.roundToInt

/** The text sent with a shared route: distance, climb and links that open it elsewhere. */
object RouteText {
    fun hasRouteLink(track: Track): Boolean = track.waypoints.size >= 2 && track.kind != TrackKind.DRAWN

    fun of(track: Track): String = buildString {
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
}
