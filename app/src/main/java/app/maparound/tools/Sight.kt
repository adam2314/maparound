package app.maparound.tools

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Plain Kotlin: bearings, line of sight and viewsheds over an elevation model. Unit-tested.

/** Ground height in metres at a position, NaN where unknown. */
fun interface Elevation {
    fun at(lat: Double, lon: Double): Float
}

object Geodesy {
    private const val R = 6_371_008.8

    /** Initial great-circle bearing from a to b, degrees 0..360 (true north). */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The point [distanceM] from [from] along [bearingDeg] (true). */
    fun destination(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / R
        val t = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(from.lat)
        val l1 = Math.toRadians(from.lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(t))
        val l2 = l1 + atan2(sin(t) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return GeoPoint(Math.toDegrees(p2), (Math.toDegrees(l2) + 540) % 360 - 180)
    }

    /** Points along the great circle from a to b, about every [stepM] (for drawing long lines). */
    fun greatCircle(a: GeoPoint, b: GeoPoint, stepM: Double = 2_000.0): List<GeoPoint> {
        val d = Geo.distance(a, b)
        val n = ceil(d / stepM).toInt().coerceIn(1, 500)
        val brg = bearing(a, b)
        return (0..n).map { i -> if (i == n) b else destination(a, brg, d * i / n) }
    }

    /** NATO mils (6400 per circle), used on many military and some hiking compasses. */
    fun mils(deg: Double): Int = ((deg / 360.0 * 6400).roundToInt()) % 6400

    /**
     * How much lower a point [distanceM] away appears because of the Earth's curvature, minus
     * the usual atmospheric refraction (k = 0.13). ~6.8 m at 10 km, ~68 m at 32 km.
     */
    fun curvatureDrop(distanceM: Double): Double = distanceM * distanceM / (2 * R) * (1 - 0.13)

    /**
     * Distances to sample terrain along a sight line: every [stepM] (about the terrain data's
     * resolution), starting one step out, ending with [totalM]. Sampling finer than the data
     * would treat the interpolated, rounded-off summit next to the observer as real ground and
     * hide whole valleys; this is the usual GIS convention.
     */
    fun sightSteps(totalM: Double, stepM: Double): DoubleArray {
        val step = maxOf(stepM, totalM / 8_000) // cap the work on very long lines
        val n = ceil(totalM / step).toInt().coerceAtLeast(1)
        return DoubleArray(n) { i -> if (i == n - 1) totalM else (i + 1) * step }
    }

    // Web Mercator, for grids that line up with map pixels.
    fun mercX(lon: Double) = R * Math.toRadians(lon)
    fun mercY(lat: Double) = R * ln(tan(PI / 4 + Math.toRadians(lat) / 2))
    fun lonOf(x: Double) = Math.toDegrees(x / R)
    fun latOf(y: Double) = Math.toDegrees(2 * atan(exp(y / R)) - PI / 2)
}

/**
 * The highest ground within [radiusM] of [p] (rings every 10 m, every 15°), or null without data.
 * Terrain data rounds summits off, so a point tapped "on the top" often sits a few metres below a
 * nearby crest that then blocks half the view.
 */
fun highestNear(dem: Elevation, p: GeoPoint, radiusM: Double = 100.0): Pair<GeoPoint, Double>? {
    var best: GeoPoint? = null
    var bestZ = Double.NEGATIVE_INFINITY
    var r = 10.0
    while (r <= radiusM) {
        for (k in 0 until 24) {
            val q = Geodesy.destination(p, k * 15.0, r)
            val z = dem.at(q.lat, q.lon).toDouble()
            if (!z.isNaN() && z > bestZ) { bestZ = z; best = q }
        }
        r += 10.0
    }
    return best?.let { it to bestZ }
}

/** Result of a line-of-sight check from an observer at a to a target at b. */
class LineOfSight(
    val distanceM: Double,
    /** Profile samples from a to b. */
    val samples: List<Sample>,
    /** Absolute heights of the observer's eye and the target's top. */
    val eyeM: Double,
    val targetTopM: Double,
    val targetVisible: Boolean,
    /** The terrain point that blocks the view most, when it is blocked. */
    val obstacle: Sample?,
    /**
     * Smallest gap between the direct sight line and the terrain (m): positive = clearance,
     * negative = how far the obstacle sticks up into the line.
     */
    val marginM: Double,
) {
    class Sample(val distanceM: Double, val lat: Double, val lon: Double, val groundM: Double, val visible: Boolean)

    companion object {
        /**
         * @param observerHeightM eye height above ground at a (1.7 m standing, ~2 m in a car)
         * @param targetHeightM height above ground of what we look for at b
         */
        fun compute(dem: Elevation, a: GeoPoint, b: GeoPoint, observerHeightM: Double, targetHeightM: Double, stepM: Double = 25.0): LineOfSight? {
            val dist = Geo.distance(a, b)
            if (dist < 1) return null
            val raw = (doubleArrayOf(0.0) + Geodesy.sightSteps(dist, stepM)).map { d ->
                val t = d / dist
                val lat = a.lat + (b.lat - a.lat) * t
                val lon = a.lon + (b.lon - a.lon) * t
                Triple(d, lat, lon) to dem.at(lat, lon).toDouble()
            }
            if (raw.first().second.isNaN() || raw.last().second.isNaN()) return null
            val eye = raw.first().second + observerHeightM
            var maxSlope = Double.NEGATIVE_INFINITY
            var worst: Pair<Int, Double>? = null // index, height above sight line
            val samples = ArrayList<Sample>(raw.size)
            val targetGround = raw.last().second
            val targetTop = targetGround + targetHeightM
            val sightSlope = (targetTop - Geodesy.curvatureDrop(dist) - eye) / dist
            for ((i, r) in raw.withIndex()) {
                val (d, lat, lon) = r.first
                val ground = r.second
                if (i == 0) {
                    samples += Sample(0.0, lat, lon, ground, true)
                    continue
                }
                val z = (if (ground.isNaN()) Double.NEGATIVE_INFINITY else ground) - Geodesy.curvatureDrop(d)
                val slope = (z - eye) / d
                // A point on the ground is visible if nothing nearer rises above the line to it.
                samples += Sample(d, lat, lon, ground, slope >= maxSlope)
                if (i < raw.lastIndex) {
                    maxSlope = max(maxSlope, slope)
                    val above = z - (eye + sightSlope * d)
                    if (worst == null || above > worst.second) worst = i to above
                }
            }
            val visible = sightSlope >= maxSlope
            return LineOfSight(
                distanceM = dist,
                samples = samples,
                eyeM = eye,
                targetTopM = targetTop,
                targetVisible = visible,
                obstacle = worst?.takeIf { !visible }?.let { samples[it.first] },
                marginM = worst?.second?.let { -it } ?: 0.0,
            )
        }
    }
}

/**
 * Which ground around an observer can be seen, on a square grid in Web Mercator (so it lines up
 * with map pixels). Cells outside the circle are not computed.
 */
class Viewshed(
    val center: GeoPoint,
    val radiusM: Double,
    val size: Int,
    /** Grid bounds in Web Mercator metres. */
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    /** Row-major, row 0 = north: 1 visible, 0 hidden, -1 outside the circle or no data. */
    val cells: ByteArray,
) {
    val north: Double get() = Geodesy.latOf(maxY)
    val south: Double get() = Geodesy.latOf(minY)
    val west: Double get() = Geodesy.lonOf(minX)
    val east: Double get() = Geodesy.lonOf(maxX)

    /** Share of the computed ground that is visible, 0..1. */
    val visibleFraction: Double
        get() {
            var v = 0
            var all = 0
            for (c in cells) if (c >= 0) { all++; if (c.toInt() == 1) v++ }
            return if (all == 0) 0.0 else v.toDouble() / all
        }

    companion object {
        /**
         * Radial sweep: a ray from the observer to every border cell. Each ray samples the terrain
         * every [sampleM] (about the terrain data's resolution, so narrow crests still block the
         * view even when the grid is coarser) and keeps the steepest angle so far; ground is
         * visible where its angle (+ target height) reaches that horizon.
         */
        fun compute(
            dem: Elevation,
            center: GeoPoint,
            radiusM: Double,
            observerHeightM: Double,
            targetHeightM: Double,
            size: Int = 400,
            sampleM: Double = 25.0,
            cancelled: () -> Boolean = { false },
        ): Viewshed? {
            val n = size or 1 // odd, so the observer sits in the middle of a cell
            // Mercator stretches distances by 1/cos(lat); size the grid in Mercator metres.
            val scale = 1 / cos(Math.toRadians(center.lat))
            val cx = Geodesy.mercX(center.lon)
            val cy = Geodesy.mercY(center.lat)
            val half = radiusM * scale
            val cell = 2 * half / n
            val groundCell = cell / scale // true metres per cell
            val eyeGround = dem.at(center.lat, center.lon)
            if (eyeGround.isNaN()) return null
            val eye = eyeGround + observerHeightM
            val mid = n / 2
            val cells = ByteArray(n * n) { -1 }
            cells[mid * n + mid] = 1
            val maxT = mid + 0.5 // circle radius in cells
            // Same sampling as a single line of sight, so both tools agree (and at least one
            // sample per cell, so small circles have no holes).
            val steps = Geodesy.sightSteps(maxT * groundCell, minOf(sampleM, groundCell))

            fun ray(tr: Int, tc: Int) {
                val dr = (tr - mid).toDouble()
                val dc = (tc - mid).toDouble()
                val len = sqrt(dr * dr + dc * dc)
                val ur = dr / len
                val uc = dc / len
                var maxSlope = Double.NEGATIVE_INFINITY
                for (d in steps) {
                    val t = d / groundCell
                    val fr = mid + ur * t
                    val fc = mid + uc * t
                    val g = dem.at(Geodesy.latOf(cy + half - (fr + 0.5) * cell), Geodesy.lonOf(cx - half + (fc + 0.5) * cell))
                    if (g.isNaN()) return
                    val z = g - Geodesy.curvatureDrop(d)
                    val slope = (z - eye) / d
                    val seen = (z + targetHeightM - eye) / d >= maxSlope
                    val r = fr.roundToInt()
                    val c = fc.roundToInt()
                    if (r in 0 until n && c in 0 until n) {
                        val idx = r * n + c
                        // A cell counts as visible if any part of it is seen.
                        if (seen) cells[idx] = 1 else if (cells[idx] < 0) cells[idx] = 0
                    }
                    if (slope > maxSlope) maxSlope = slope
                }
            }
            for (i in 0 until n) {
                if (i % 16 == 0 && cancelled()) return null
                ray(0, i); ray(n - 1, i); ray(i, 0); ray(i, n - 1)
            }
            // Cells just outside the circle that a ray clipped: drop them for a clean edge.
            for (r in 0 until n) for (c in 0 until n) {
                val rr = (r - mid).toDouble()
                val cc = (c - mid).toDouble()
                if (rr * rr + cc * cc > maxT * maxT) cells[r * n + c] = -1
            }
            return Viewshed(center, radiusM, n, cx - half, cy - half, cx + half, cy + half, cells)
        }
    }
}
