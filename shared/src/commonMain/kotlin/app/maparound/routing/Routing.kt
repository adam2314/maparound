package app.maparound.routing

import app.maparound.i18n.T
import app.maparound.shared.util.toRadians
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Plain Kotlin (no Android): shared by the route planner, track library and unit tests.

/** A point of a route or track. [ele] in metres, [time] in epoch millis, both optional. */
data class GeoPoint(val lat: Double, val lon: Double, val ele: Double? = null, val time: Long? = null)

enum class RouteProfile(val brouterProfile: String?) {
    HIKE("hiking-mountain"),
    BIKE("trekking"),
    CAR("car-vario"),
    /** Straight lines between the tapped points: "draw & measure". */
    LINE(null),
    ;

    /** The name people see, in the app's language. */
    val label: String
        get() = when (this) {
            HIKE -> T.nav.profileHike
            BIKE -> T.nav.profileBike
            CAR -> T.nav.profileCar
            LINE -> T.nav.profileLine
        }
}

data class RouteResult(
    val points: List<GeoPoint>,
    val stats: TrackStats,
    /** Travel time estimated by the routing engine (bike/car), or by the hiking formula. */
    val durationS: Long?,
    val source: Source,
    /** The router's turn instructions (BRouter's turn hints); empty for straight lines. */
    val maneuvers: List<Maneuver> = emptyList(),
) {
    /** [SAVED]: a saved route's line, as it was saved (being edited, until a stop changes). */
    enum class Source { OFFLINE, ONLINE, STRAIGHT, SAVED }
}

class RoutingException(message: String) : Exception(message)

data class TrackStats(
    val distanceM: Double,
    val ascentM: Double?,
    val descentM: Double?,
    val minEleM: Double?,
    val maxEleM: Double?,
    /** Elapsed time between the first and last timestamp (recorded tracks only). */
    val elapsedS: Long?,
) {
    companion object {
        val EMPTY = TrackStats(0.0, null, null, null, null, null)

        /** Climb below this is treated as GPS / elevation-model noise. */
        private const val HYSTERESIS_M = 4.0

        fun of(points: List<GeoPoint>): TrackStats = Adder().apply { points.forEach(::add) }.stats
    }

    /**
     * The stats of points as they come, one at a time (a recording): after each [add], [stats] is
     * [of] all the points so far, without going over them again.
     */
    class Adder {
        private var last: GeoPoint? = null
        private var dist = 0.0
        private var eles = 0
        private var ref = 0.0
        private var up = 0.0
        private var down = 0.0
        private var min = Double.MAX_VALUE
        private var max = -Double.MAX_VALUE
        private var times = 0
        private var firstTime = 0L
        private var lastTime = 0L

        var stats: TrackStats = EMPTY
            private set

        fun add(p: GeoPoint) {
            last?.let { dist += Geo.distance(it, p) }
            last = p
            p.ele?.let { e ->
                if (eles++ == 0) ref = e
                if (e - ref >= HYSTERESIS_M) { up += e - ref; ref = e }
                else if (ref - e >= HYSTERESIS_M) { down += ref - e; ref = e }
                if (e < min) min = e
                if (e > max) max = e
            }
            p.time?.let { t ->
                if (times++ == 0) firstTime = t
                lastTime = t
            }
            stats = TrackStats(
                dist,
                up.takeIf { eles >= 2 }, down.takeIf { eles >= 2 },
                min.takeIf { eles > 0 }, max.takeIf { eles > 0 },
                ((lastTime - firstTime) / 1000).takeIf { times >= 2 },
            )
        }
    }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    /** Great-circle (haversine) distance in metres. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = toRadians(lat1)
        val p2 = toRadians(lat2)
        val dp = p2 - p1
        val dl = toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Adds points so no segment is longer than [maxStepM] (for sampling elevation along a line). */
    fun densify(points: List<GeoPoint>, maxStepM: Double): List<GeoPoint> {
        if (points.size < 2) return points
        val out = ArrayList<GeoPoint>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val steps = max(1, kotlin.math.ceil(distance(a, b) / maxStepM).toInt())
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                out += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
        }
        out += points.last().copy(ele = null, time = null)
        return out
    }

    /**
     * Walking time by the DIN 33466 rule used on European trail signs: 4 km/h on the flat,
     * 300 m/h up, 500 m/h down; the larger of horizontal/vertical time plus half the smaller.
     */
    fun hikingTimeSeconds(distanceM: Double, ascentM: Double?, descentM: Double?): Long {
        val horizontal = distanceM / 4000.0
        val vertical = (ascentM ?: 0.0) / 300.0 + (descentM ?: 0.0) / 500.0
        val hours = max(horizontal, vertical) + min(horizontal, vertical) / 2
        return (hours * 3600).toLong()
    }
}

/** BRouter routing data is split into 5°×5° files named after their south-west corner, e.g. E5_N45. */
object Segments {
    fun nameFor(lat: Double, lon: Double): String {
        val lonBase = floor(lon / 5).toInt() * 5
        val latBase = floor(lat / 5).toInt() * 5
        val ew = if (lonBase < 0) "W${-lonBase}" else "E$lonBase"
        val ns = if (latBase < 0) "S${-latBase}" else "N$latBase"
        return "${ew}_$ns"
    }

    /** All segment names covering a bounding box. */
    fun namesFor(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<String> {
        val out = LinkedHashSet<String>()
        var lat = floor(minLat / 5) * 5
        while (lat <= maxLat) {
            var lon = floor(minLon / 5) * 5
            while (lon <= maxLon) {
                out += nameFor(lat + 0.5, lon + 0.5)
                lon += 5
            }
            lat += 5
        }
        return out.toList()
    }
}
