package app.maparound.routing

import app.maparound.shared.util.toRadians
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Reshaping a planned route by hand. A long press near a stop picks it up, and one near the line
 * picks up a new stop on that leg. While the finger moves, the legs next to the stop are drawn as
 * straight lines to it, and the route is calculated again when it is put down.
 */
object RouteEdit {
    /** How far from a stop's centre a finger still grabs it, in dp (the marker is 26 dp across). */
    const val STOP_REACH_DP = 24.0

    /** How far from the route line a finger still grabs it, in dp. */
    const val LINE_REACH_DP = 16.0

    private const val METRES_PER_DEGREE = 111_195.0

    /** A stop picked up: where it goes in the stop list, whether it's a new one, and where it starts. */
    data class Grab(val index: Int, val inserted: Boolean, val at: GeoPoint)

    /** The point of a line nearest to another, on the segment from line[segment] to line[segment + 1]. */
    data class Hit(val segment: Int, val point: GeoPoint, val distanceM: Double)

    /**
     * What a long press at [p] picks up: the nearest stop within reach, else a new stop on the
     * drawn [line] (between the stops of the leg it's on), else nothing.
     */
    fun grab(p: GeoPoint, stops: List<GeoPoint>, line: List<GeoPoint>, metresPerDp: Double): Grab? {
        val nearest = stops.indices.minByOrNull { Geo.distance(p, stops[it]) }
        if (nearest != null && Geo.distance(p, stops[nearest]) <= STOP_REACH_DP * metresPerDp) {
            return Grab(nearest, inserted = false, at = stops[nearest])
        }
        if (stops.size < 2) return null
        val hit = nearestOnLine(line, p) ?: return null
        if (hit.distanceM > LINE_REACH_DP * metresPerDp) return null
        return Grab(legOf(hit.segment, Guidance.stopIndices(line, stops)) + 1, inserted = true, at = hit.point)
    }

    /** The leg (0 = from the first stop to the second) that segment [segment] of the line belongs to. */
    fun legOf(segment: Int, stopIndices: List<Int>): Int {
        var leg = 0
        for (k in 0 until stopIndices.size - 1) if (stopIndices[k] <= segment) leg = k
        return leg
    }

    /** The nearest point of [line] to [p] (flat-earth maths around [p]: fine at finger scale). */
    fun nearestOnLine(line: List<GeoPoint>, p: GeoPoint): Hit? {
        if (line.size < 2) return null
        val kx = METRES_PER_DEGREE * cos(toRadians(p.lat))
        val ky = METRES_PER_DEGREE
        var best: Hit? = null
        for (i in 0 until line.lastIndex) {
            val ax = (line[i].lon - p.lon) * kx
            val ay = (line[i].lat - p.lat) * ky
            val dx = (line[i + 1].lon - p.lon) * kx - ax
            val dy = (line[i + 1].lat - p.lat) * ky - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val qx = ax + t * dx
            val qy = ay + t * dy
            val d = sqrt(qx * qx + qy * qy)
            if (best == null || d < best.distanceM) best = Hit(i, GeoPoint(p.lat + qy / ky, p.lon + qx / kx), d)
        }
        return best
    }

    /**
     * The line to draw while the stop picked up by [grab] is at [at]: [line] as it was routed,
     * with the legs on either side of that stop replaced by straight lines to it. [stopIndices]
     * are where the stops were on [line] before the grab.
     */
    fun preview(line: List<GeoPoint>, stopIndices: List<Int>, grab: Grab, at: GeoPoint): List<GeoPoint> {
        val before = grab.index - 1 // the stop before it, numbered as before the grab
        val after = if (grab.inserted) grab.index else grab.index + 1 // and the one after it
        val out = ArrayList<GeoPoint>()
        if (before >= 0) out.addAll(line.subList(0, stopIndices[before] + 1))
        out += at
        if (after <= stopIndices.lastIndex) out.addAll(line.subList(stopIndices[after], line.size))
        return out
    }

    /** [stops] with the stop picked up by [grab] at [at]. */
    fun place(stops: List<GeoPoint>, grab: Grab, at: GeoPoint): List<GeoPoint> =
        stops.toMutableList().apply { if (grab.inserted) add(grab.index, at) else set(grab.index, at) }
}
