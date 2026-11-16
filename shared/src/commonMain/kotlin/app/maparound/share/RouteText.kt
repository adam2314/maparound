package app.maparound.share

import app.maparound.i18n.T
import app.maparound.routing.RouteProfile
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import app.maparound.ui.Fmt
import kotlin.math.roundToInt

/**
 * The text sent with a shared route or track: distance, climb, a MapAround link with the line
 * itself ([TrackLink]: opens in the web app or the app) and links that open it elsewhere.
 */
object RouteText {
    /** A planned route that other planners can recalculate (BRouter-Web, Google Maps). */
    fun hasRouteLink(track: Track): Boolean = track.waypoints.size >= 2 && track.kind != TrackKind.DRAWN

    /** Anything with a line can go as a link: MapAround's track link at least. */
    fun hasLink(track: Track): Boolean = track.points.size >= 2 || hasRouteLink(track)

    /** Name, distance and climb: "Wadi Qelt · 12.4 km · ↑ 450 m". */
    fun summary(track: Track): String = buildString {
        val s = track.stats
        append(track.name).append(" · ").append(Fmt.distance(s.distanceM.toFloat()))
        s.ascentM?.let { append(" · ↑ ").append(it.roundToInt()).append(" ").append(T.common.unitM) }
    }

    fun of(track: Track): String = buildString {
        appendLine(summary(track))
        TrackLink.of(track)?.let { appendLine(T.map.openInMapAround(it)) }
        if (hasRouteLink(track)) {
            SharedLinks.brouterWebLink(track.waypoints, track.profile)?.let { appendLine(T.map.openTheRoute(it)) }
            SharedLinks.googleDirectionsLink(track.waypoints, track.profile ?: RouteProfile.HIKE)?.let { appendLine("Google Maps: $it") }
        } else {
            track.points.firstOrNull()?.let { appendLine(T.map.routeStartsAt(SharedLinks.googleLink(it.lat, it.lon))) }
        }
        append(T.map.gpxAttached)
    }
}
