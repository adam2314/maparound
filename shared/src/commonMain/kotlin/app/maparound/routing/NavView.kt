package app.maparound.routing

import app.maparound.sensors.GeoFix
import kotlin.math.log2
import kotlin.math.max

// Plain Kotlin: how the map looks while navigating. Unit-tested.

/**
 * Navigation's view of the map: tilted, the way ahead up, our position low in the view, and the
 * faster we go the further out (and so the further ahead) it shows. Fed each fix; keeps the zoom
 * and the heading smooth, so the map doesn't jump with every fix.
 */
class NavView {
    /** The heading shown, degrees from north (the way ahead is up). */
    var bearing: Double = 0.0
        private set

    /** The zoom shown (the app's: MapPos's); NaN before the first fix. */
    var zoom: Double = Double.NaN
        private set

    /**
     * The view for [fix] on a route for [profile], [ahead] the route's direction just ahead of us
     * (null when not known): the course while moving, the route's way when slow (a walker's GPS
     * course wanders), else as it was.
     */
    fun update(fix: GeoFix, profile: RouteProfile, ahead: Double?) {
        val first = zoom.isNaN()
        val speedKmh = (fix.speedMps ?: 0f) * 3.6
        val course = fix.bearingDeg?.toDouble()?.takeIf { speedKmh >= MOVING_KMH }
        val target = course ?: ahead
        if (target != null) bearing = if (first) target else turnToward(bearing, target, if (course != null) 0.6 else 0.35)
        val z = zoomFor(speedKmh, profile)
        zoom = if (first) z else zoom + (z - zoom) * 0.3
    }

    /**
     * [update] for navigation's [state]: its profile, and the route's way ahead while we're on it
     * (from its start before the first fix has placed us on it).
     */
    fun update(fix: GeoFix, state: NavigationSession.State) {
        val p = state.progress
        val along = if (p == null) 0.0 else p.alongM.takeIf { !p.offRoute }
        val ahead = along?.let { a -> state.route?.points?.let { bearingAhead(it, a) } }
        update(fix, state.profile, ahead)
    }

    /** A new navigation: north up until it knows better, and the next fix sets the view at once. */
    fun reset() {
        zoom = Double.NaN
        bearing = 0.0
    }

    companion object {
        /** From this speed the GPS course is the heading. */
        const val MOVING_KMH = 4.0

        /** How far the map tilts, degrees from straight down. */
        const val PITCH = 55.0

        /** Where our position is, from the top of the view (0) to the bottom (1). */
        const val ANCHOR = 0.75

        /**
         * The zoom for a speed: walking close in (17.5; driving 17 at the most), then half a zoom
         * out each time the speed doubles past 5 km/h (16 at 40 km/h, 15.5 at 80), never further
         * out than 15.
         */
        fun zoomFor(speedKmh: Double, profile: RouteProfile): Double {
            val closest = if (profile == RouteProfile.CAR) 17.0 else 17.5
            return (17.5 - 0.5 * log2(max(speedKmh, 5.0) / 5.0)).coerceIn(15.0, closest)
        }

        /** [from] a part of the way to [to], the short way round. */
        fun turnToward(from: Double, to: Double, part: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180) d -= 360
            if (d < -180) d += 360
            return ((from + d * part) % 360.0 + 360.0) % 360.0
        }

        /**
         * The route's direction from [alongM] metres along [points] to [aheadM] metres further on,
         * degrees from north; null when the route is shorter than a metre from there on.
         */
        fun bearingAhead(points: List<GeoPoint>, alongM: Double, aheadM: Double = 30.0): Double? {
            val from = pointAt(points, alongM) ?: return null
            val to = pointAt(points, alongM + aheadM) ?: return null
            return if (Geo.distance(from, to) > 1.0) Guidance.bearing(from, to) else null
        }

        /** The point [m] metres along [points] (the last one past the end). */
        private fun pointAt(points: List<GeoPoint>, m: Double): GeoPoint? {
            if (points.isEmpty()) return null
            var walked = 0.0
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val len = Geo.distance(a, b)
                if (len > 0 && walked + len >= m) {
                    val t = ((m - walked) / len).coerceIn(0.0, 1.0)
                    return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                }
                walked += len
            }
            return points.last()
        }
    }
}
