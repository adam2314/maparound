package app.maparound.routing

import app.maparound.i18n.T
import app.maparound.sensors.GeoFix
import app.maparound.shared.util.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Follows a route with position fixes: where on the route we are, the next maneuver and how far
 * away it is, what to say and when, leaving the route and arriving. Plain logic with no clock or
 * threads: [NavigationSession] feeds it the device's fixes.
 *
 * @param waypoints the planner points this route visits; the first is where it starts
 * @param stopNumbers numbers of the stops between the first and last waypoint (see [Maneuver.stop])
 * @param fromHere the route starts at our position (a recalculated route), so we are on it at once
 */
class Navigator(
    val route: RouteResult,
    val profile: RouteProfile,
    waypoints: List<GeoPoint>,
    stopNumbers: List<Int> = List((waypoints.size - 2).coerceAtLeast(0)) { it + 1 },
    private val fromHere: Boolean = false,
) {
    data class Progress(
        /** Our position moved onto the route. */
        val onRoute: GeoPoint,
        /** Distance travelled along the route, metres. */
        val alongM: Double,
        /** How far the fix is from the route, metres. */
        val offsetM: Double,
        val next: Maneuver?,
        val distanceToNextM: Double,
        /** The maneuver after [next] when it follows closely (shown as "then …"). */
        val then: Maneuver?,
        val remainingM: Double,
        val remainingS: Long?,
        /** We have been away from the route for a few fixes in a row. */
        val offRoute: Boolean,
        /** We have been on the route at least once (a route that starts elsewhere: not yet). */
        val joined: Boolean,
        val arrived: Boolean,
        /** How many of the route's stops are behind us. */
        val stopsPassed: Int,
        /** To say now, or null. */
        val speech: String?,
    )

    /** Distances and timings for one way of travelling. */
    private class Tuning(
        val offRouteM: Double, val offRouteFixes: Int, val arriveM: Double, val passM: Double,
        /** "In 1 km…", "In 300 m…", "Turn left": the distance, or the speed × seconds if larger. */
        val farM: Double, val farS: Double, val nearM: Double, val nearS: Double, val nowM: Double, val nowS: Double,
        /** A maneuver this close after the next one is announced with it. */
        val thenM: Double,
        /** Below this speed (m/s) the direction of travel is too noisy to use. */
        val headingSpeed: Double,
    )

    private val tuning = when (profile) {
        RouteProfile.CAR -> Tuning(40.0, 3, 40.0, 10.0, 1000.0, 50.0, 300.0, 18.0, 50.0, 5.0, 150.0, 3.0)
        RouteProfile.BIKE -> Tuning(30.0, 3, 25.0, 8.0, 500.0, 40.0, 150.0, 15.0, 25.0, 4.0, 60.0, 2.0)
        RouteProfile.HIKE -> Tuning(30.0, 4, 20.0, 6.0, 250.0, 0.0, 80.0, 0.0, 20.0, 0.0, 40.0, 0.8)
        RouteProfile.LINE -> Tuning(60.0, 4, 25.0, 8.0, 250.0, 0.0, 80.0, 0.0, 25.0, 0.0, 40.0, 0.8)
    }

    private val pts = route.points
    private val cum = Guidance.cumulative(pts)
    val lengthM: Double = cum.lastOrNull() ?: 0.0

    /** Everything to announce, in order along the route; the last is the arrival. */
    val maneuvers: List<Maneuver> = Guidance.maneuvers(pts, route.maneuvers, waypoints, stopNumbers)

    private var along = 0.0
    private var joined = fromHere
    private var next = 0
    private var offCount = 0
    private var arrived = false
    private var greeted = false
    private val said = IntArray(maneuvers.size)

    /** Where the maneuver is along the route, metres. */
    fun alongOf(m: Maneuver): Double = cum[m.index.coerceIn(0, cum.lastIndex)]

    fun update(fix: GeoFix): Progress {
        val p = GeoPoint(fix.lat, fix.lon)
        if (pts.size < 2) {
            return Progress(p, 0.0, 0.0, null, 0.0, null, 0.0, 0, false, joined, true, 0, null)
        }
        val speed = fix.speedMps?.toDouble()?.takeIf { it >= 0 }
        val heading = fix.bearingDeg?.toDouble()?.takeIf { speed != null && speed >= tuning.headingSpeed }
        val match = locate(p, heading, speed)
        val limit = tuning.offRouteM + min((fix.accuracyM ?: 10f).toDouble(), 50.0)
        if (match.distance <= limit) {
            joined = true
            offCount = 0
            along = match.along
        } else {
            offCount++
        }
        val offRoute = offCount >= tuning.offRouteFixes

        // Maneuvers we have driven past (the arrival is only reached by arriving).
        while (next < maneuvers.lastIndex && along >= alongOf(maneuvers[next]) + tuning.passM) next++
        val stopsPassed = maneuvers.take(next).count { it.stop != null }

        val remaining = max(0.0, lengthM - along)
        var speech: String? = null
        if (!arrived && joined && !offRoute &&
            (remaining <= tuning.arriveM || (Geo.distance(p, pts.last()) <= tuning.arriveM && along > lengthM / 2))
        ) {
            arrived = true
            next = maneuvers.lastIndex
            speech = Guidance.now(maneuvers.last())
        }

        val m = maneuvers.getOrNull(next)
        val toNext = m?.let { max(0.0, alongOf(it) - along) } ?: 0.0
        val following = maneuvers.getOrNull(next + 1)?.takeIf { m != null && alongOf(it) - alongOf(m) <= tuning.thenM }
        if (speech == null && !arrived && m != null && !offRoute && joined) {
            speech = announce(next, toNext, speed ?: 0.0, following)
        }
        val remainingS = route.durationS?.let { if (lengthM > 0) (it * remaining / lengthM).toLong() else 0L }
        return Progress(
            match.point, along, match.distance, m, toNext, following?.takeIf { !arrived },
            remaining, remainingS, offRoute, joined, arrived, stopsPassed, speech,
        )
    }

    private fun announce(i: Int, distance: Double, speed: Double, following: Maneuver?): String? {
        val m = maneuvers[i]
        val now = max(tuning.nowM, speed * tuning.nowS)
        val near = max(tuning.nearM, speed * tuning.nearS)
        val far = max(tuning.farM, speed * tuning.farS)
        val then = following?.let { ", " + Guidance.then(it) } ?: ""
        if (!greeted) {
            greeted = true
            // First words of a route: how far to go, or the first maneuver if it is close.
            return when {
                distance <= now && m.turn != Turn.ARRIVE -> { said[i] = ALL; muteAhead(i, following); Guidance.now(m) + then }
                distance <= near -> { said[i] = FAR or NEAR; Guidance.ahead(m, distance) + then }
                distance <= far * 1.5 -> { said[i] = FAR; Guidance.ahead(m, distance) }
                else -> T.nav.continueFor(Guidance.spokenDistance(distance))
            }
        }
        val s = said[i]
        return when {
            distance <= now && (s and NOW) == 0 -> {
                said[i] = ALL
                muteAhead(i, following)
                if (m.turn == Turn.ARRIVE) null else Guidance.now(m) + then
            }
            distance <= near && (s and NEAR) == 0 -> {
                said[i] = s or NEAR or FAR
                // Too close to the "now" call to be worth saying separately.
                if (distance <= now * 1.6) null else Guidance.ahead(m, distance) + then
            }
            distance <= far && (s and FAR) == 0 -> {
                said[i] = s or FAR
                if (distance <= near * 1.6) null else Guidance.ahead(m, distance)
            }
            else -> null
        }
    }

    /** The maneuver after [i] was just announced with it: only its own "now" call is left. */
    private fun muteAhead(i: Int, following: Maneuver?) {
        if (following != null && i + 1 < said.size) said[i + 1] = said[i + 1] or FAR or NEAR
    }

    private class Match(val point: GeoPoint, val along: Double, val distance: Double)

    /**
     * The closest point of the route, looking a little behind and ahead of where we were (the
     * whole route until we have joined it). The direction of travel breaks ties between two
     * passes along the same road, as on an out-and-back hike.
     */
    private fun locate(p: GeoPoint, heading: Double?, speed: Double?): Match {
        val from: Int
        val to: Int
        if (!joined) {
            from = 0
            to = pts.lastIndex - 1
        } else {
            val back = along - 50.0
            val ahead = along + max(400.0, (speed ?: 0.0) * 60.0)
            from = (cum.indexOfFirst { it >= back } - 1).coerceIn(0, pts.lastIndex - 1)
            val last = cum.indexOfFirst { it > ahead }
            to = if (last < 0) pts.lastIndex - 1 else last.coerceIn(from, pts.lastIndex - 1)
        }
        val kx = 111_320.0 * cos(toRadians(p.lat))
        val ky = 110_540.0
        var best: Match? = null
        var bestCost = Double.MAX_VALUE
        for (i in from..to) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val bx = (b.lon - p.lon) * kx
            val by = (b.lat - p.lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val d = sqrt(cx * cx + cy * cy)
            val segAlong = cum[i] + t * (cum[i + 1] - cum[i])
            var cost = d
            if (heading != null && len2 > 1.0 && abs(Guidance.angle(Guidance.bearing(a, b), heading)) > 100) cost += 40.0
            cost += if (joined) 0.05 * abs(segAlong - along) else 0.002 * segAlong
            if (cost < bestCost) {
                bestCost = cost
                best = Match(
                    GeoPoint(a.lat + t * (b.lat - a.lat), a.lon + t * (b.lon - a.lon)),
                    segAlong, d,
                )
            }
        }
        return best!!
    }

    private companion object {
        const val FAR = 1
        const val NEAR = 2
        const val NOW = 4
        const val ALL = FAR or NEAR or NOW
    }
}
