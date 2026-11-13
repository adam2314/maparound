package app.maparound.routing

import app.maparound.i18n.T
import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import java.io.File

/**
 * Routes on the device with the embedded BRouter engine.
 *
 * @param segmentsDir folder with downloaded `*.rd5` routing data (5°×5° per file)
 * @param profilesDir folder with `lookups.dat` and the `*.brf` profiles
 */
class OfflineRouter(private val segmentsDir: File, private val profilesDir: File) {

    fun hasSegment(name: String): Boolean = File(segmentsDir, "$name.rd5").isFile

    /** Routing data files the waypoints need but that are not downloaded. */
    fun missingSegments(points: List<GeoPoint>): List<String> =
        points.map { Segments.nameFor(it.lat, it.lon) }.distinct().filterNot(::hasSegment)

    /** Blocking; run off the main thread. BRouter's engine is not meant to run twice at once. */
    @Synchronized
    fun route(waypoints: List<GeoPoint>, profile: RouteProfile, timeoutMs: Long = 60_000): RouteResult {
        val brf = profile.brouterProfile ?: throw IllegalArgumentException("$profile is not routed")
        require(waypoints.size >= 2) { "Need a start and an end point" }
        val profileFile = File(profilesDir, "$brf.brf")
        if (!profileFile.isFile) throw RoutingException(T.nav.routingProfileMissing(brf))

        val rc = RoutingContext()
        rc.localFunction = profileFile.absolutePath
        // Turn hints for navigation. The profiles say "auto" (1), which keeps this setting.
        rc.turnInstructionMode = TURN_HINTS_LOCUS
        val nodes = waypoints.mapIndexed { i, p ->
            OsmNodeNamed().apply {
                name = when (i) {
                    0 -> "from"
                    waypoints.lastIndex -> "to"
                    else -> "via$i"
                }
                ilon = ((p.lon + 180.0) * 1_000_000.0 + 0.5).toInt()
                ilat = ((p.lat + 90.0) * 1_000_000.0 + 0.5).toInt()
            }
        }
        val engine = RoutingEngine(null, null, segmentsDir, nodes, rc)
        engine.quite = true
        engine.doRun(timeoutMs)
        engine.errorMessage?.let { throw RoutingException(friendly(it)) }
        val track = engine.foundTrack ?: throw RoutingException(T.nav.noRouteFound)

        val points = track.nodes.map { n ->
            GeoPoint(
                lat = n.iLat / 1_000_000.0 - 90.0,
                lon = n.iLon / 1_000_000.0 - 180.0,
                ele = if (n.sElev == Short.MIN_VALUE) null else n.elev,
            )
        }
        val stats = TrackStats.of(points).copy(distanceM = track.distance.toDouble())
        val duration = if (profile == RouteProfile.HIKE) {
            Geo.hikingTimeSeconds(stats.distanceM, stats.ascentM, stats.descentM)
        } else {
            track.totalSeconds.toLong().takeIf { it > 0 }
        }
        // BRouter's turn hints are package-private; its own GeoJSON writer is the public way to them,
        // in the same form the brouter.de server sends.
        val turns = try {
            Guidance.brouterHints(FormatJson(rc).format(track))
        } catch (e: Exception) {
            emptyList()
        }
        return RouteResult(points, stats, duration, RouteResult.Source.OFFLINE, turns)
    }

    companion object {
        /** BRouter's "locus" turn style: keeps exits left/right apart from keep left/right. */
        const val TURN_HINTS_LOCUS = 2
    }

    private fun friendly(message: String): String = when {
        "not mapped" in message || "datafile" in message -> T.nav.pointOutsideRoutingData
        "target island" in message || "no track found" in message.lowercase() -> T.nav.noRouteBetweenPoints
        else -> message
    }
}
