package app.maparound.three

import app.maparound.shared.util.toDegrees
import app.maparound.shared.util.toRadians
import app.maparound.tools.Elevation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Plain Kotlin: the 3D view on the whole globe. Everything is placed on the WGS84 ellipsoid in
// Earth-centred, Earth-fixed metres (ECEF) and drawn relative to the eye, so it's exact from a
// hiking path to the whole planet. Unit-tested.

/** A point or direction in ECEF metres. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Double get() = sqrt(this dot this)
    fun normalized(): Vec3 = length.let { l -> if (l == 0.0) this else Vec3(x / l, y / l, z / l) }
}

object Wgs84 {
    const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)

    /** Mean radius, for horizon and scale estimates. */
    const val R = 6_371_008.8

    fun ecef(lat: Double, lon: Double, h: Double): Vec3 {
        val p = toRadians(lat)
        val l = toRadians(lon)
        val sp = sin(p)
        val n = A / sqrt(1 - E2 * sp * sp)
        return Vec3((n + h) * cos(p) * cos(l), (n + h) * cos(p) * sin(l), (n * (1 - E2) + h) * sp)
    }

    /** The ellipsoid's normal (geodetic up) at lat/lon. */
    fun up(lat: Double, lon: Double): Vec3 {
        val p = toRadians(lat)
        val l = toRadians(lon)
        return Vec3(cos(p) * cos(l), cos(p) * sin(l), sin(p))
    }

    fun east(lon: Double): Vec3 {
        val l = toRadians(lon)
        return Vec3(-sin(l), cos(l), 0.0)
    }

    fun north(lat: Double, lon: Double): Vec3 {
        val p = toRadians(lat)
        val l = toRadians(lon)
        return Vec3(-sin(p) * cos(l), -sin(p) * sin(l), cos(p))
    }

    /** Latitude, longitude (degrees) and height (m) of an ECEF point (Bowring, then one refinement). */
    fun geodetic(v: Vec3): DoubleArray {
        val b = A * (1 - F)
        val ep2 = (A * A - b * b) / (b * b)
        val p = sqrt(v.x * v.x + v.y * v.y)
        val lon = atan2(v.y, v.x)
        var lat = atan2(v.z * A, p * b)
        lat = atan2(v.z + ep2 * b * sin(lat).let { it * it * it }, p - E2 * A * cos(lat).let { it * it * it })
        val sl = sin(lat)
        val n = A / sqrt(1 - E2 * sl * sl)
        val h = if (abs(cos(lat)) > 1e-9) p / cos(lat) - n else abs(v.z) - b
        return doubleArrayOf(toDegrees(lat), toDegrees(lon), h)
    }
}

/**
 * The 3D view's camera: it looks at [lat]/[lon] (at [groundH] metres, exaggerated) from
 * [distance] away, heading [yaw] (0 north) and tilted [pitch] degrees above the horizontal. As it
 * draws back to see the globe, the tilt goes to straight down.
 */
class GlobeCamera(
    val lat: Double,
    val lon: Double,
    val groundH: Double,
    val distance: Double,
    val yaw: Double,
    val pitch: Double,
) {
    /** The tilt used: towards 90° (looking straight down) between 300 and 3000 km away. */
    val effectivePitch: Double = pitch + (90 - pitch) * smoothstep(300_000.0, 3_000_000.0, distance)

    val target: Vec3 = Wgs84.ecef(lat, lon, groundH)
    val up: Vec3 = Wgs84.up(lat, lon)
    private val east = Wgs84.east(lon)
    private val north = Wgs84.north(lat, lon)

    val eye: Vec3 = run {
        val p = toRadians(effectivePitch)
        val y = toRadians(yaw)
        target + (east * (cos(p) * sin(y)) - north * (cos(p) * cos(y)) + up * sin(p)) * distance
    }

    /** The eye's height above the ellipsoid. */
    val eyeAltitude: Double get() = Wgs84.geodetic(eye)[2]

    /**
     * Ground metres a screen pixel covers at the target, for a vertical field of view [fovDeg]
     * across [viewPx] pixels.
     */
    fun metresPerPixel(fovDeg: Double, viewPx: Double): Double = 2 * distance * tan(toRadians(fovDeg) / 2) / max(viewPx, 1.0)

    /**
     * The view matrix with the eye at the origin (column-major 4×4): things are drawn at their
     * position minus [eye], which keeps float precision near the camera.
     */
    fun viewMatrix(): FloatArray {
        val f = (target - eye).normalized()
        // Straight down, the target's up is parallel to the view: use north as "up" then.
        val upHint = if (abs(f dot up) > 0.999) north else up
        val s = (f cross upHint).normalized()
        val u = s cross f
        return floatArrayOf(
            s.x.toFloat(), u.x.toFloat(), (-f.x).toFloat(), 0f,
            s.y.toFloat(), u.y.toFloat(), (-f.y).toFloat(), 0f,
            s.z.toFloat(), u.z.toFloat(), (-f.z).toFloat(), 0f,
            0f, 0f, 0f, 1f,
        )
    }

    /** Moves the target by screen pixels (a one-finger drag); returns the new lat/lon. */
    fun panned(dxPx: Double, dyPx: Double, fovDeg: Double, viewPx: Double): Pair<Double, Double> {
        val mpp = metresPerPixel(fovDeg, viewPx)
        val y = toRadians(yaw)
        val tilt = max(sin(toRadians(effectivePitch)), 0.35)
        // Right and forward on the ground (east/north components).
        val e = -cos(y) * dxPx * mpp - sin(y) * dyPx * mpp / tilt
        val n = -sin(y) * dxPx * mpp + cos(y) * dyPx * mpp / tilt
        val newLat = (lat + toDegrees(n / Wgs84.R)).coerceIn(-85.0, 85.0)
        var newLon = lon + toDegrees(e / (Wgs84.R * max(cos(toRadians(lat)), 0.01)))
        if (newLon > 180) newLon -= 360
        if (newLon < -180) newLon += 360
        return newLat to newLon
    }

    /** Sine of the horizon's elevation angle seen from the eye (negative: below horizontal). */
    fun horizonSine(): Double {
        val r = Wgs84.R
        val d = r + max(eyeAltitude, 1.0)
        return -sqrt(max(0.0, 1 - (r / d) * (r / d)))
    }

    companion object {
        fun smoothstep(a: Double, b: Double, x: Double): Double {
            val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }
    }
}

object Mat4 {
    /** OpenGL's perspective projection (column-major), vertical field of view in degrees. */
    fun perspective(fovYDeg: Double, aspect: Double, near: Double, far: Double): FloatArray {
        val f = 1.0 / tan(toRadians(fovYDeg) / 2)
        val out = FloatArray(16)
        out[0] = (f / aspect).toFloat()
        out[5] = f.toFloat()
        out[10] = ((far + near) / (near - far)).toFloat()
        out[11] = -1f
        out[14] = (2 * far * near / (near - far)).toFloat()
        return out
    }

    /** a × b (column-major). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
        return out
    }
}

/** A 4×4 column-major matrix times a point (w = 1): clip coordinates x, y, z, w. */
fun clip(m: FloatArray, x: Double, y: Double, z: Double): DoubleArray = doubleArrayOf(
    m[0] * x + m[4] * y + m[8] * z + m[12],
    m[1] * x + m[5] * y + m[9] * z + m[13],
    m[2] * x + m[6] * y + m[10] * z + m[14],
    m[3] * x + m[7] * y + m[11] * z + m[15],
)

object GlobeTileSelector {
    /**
     * Quadtree level of detail over the whole globe: a tile is split while the eye is closer to
     * it than [detail] × its size. Tiles beyond the horizon or outside the view ([viewProj], with
     * the eye at the origin) are left out. Ground up to [maxHeight] metres (exaggerated) counts
     * for both.
     */
    fun select(
        eye: Vec3,
        viewProj: FloatArray,
        maxZoom: Int,
        detail: Double = 1.6,
        maxLeaves: Int = 150,
        maxHeight: Double = 9_000.0,
        rootZoom: Int = 2,
    ): TileSelection {
        val roots = ArrayList<TileId>()
        val n = 1 shl rootZoom
        for (x in 0 until n) for (y in 0 until n) roots += TileId(rootZoom, x, y)

        class Probe(val points: List<Vec3>, val high: List<Vec3>, val ups: List<Vec3>)
        val probes = HashMap<TileId, Probe>()
        fun probe(t: TileId): Probe = probes.getOrPut(t) {
            val lats = doubleArrayOf(t.north, (t.north + t.south) / 2, t.south)
            val lons = doubleArrayOf(t.west, (t.west + t.east) / 2, t.east)
            val low = ArrayList<Vec3>(9)
            val high = ArrayList<Vec3>(9)
            val ups = ArrayList<Vec3>(9)
            for (la in lats) for (lo in lons) {
                low += Wgs84.ecef(la, lo, 0.0)
                high += Wgs84.ecef(la, lo, maxHeight)
                ups += Wgs84.up(la, lo)
            }
            Probe(low, high, ups)
        }

        val g = Wgs84.geodetic(eye)

        /** The tile's ground point nearest below the eye (its lat/lon clamped into the tile). */
        fun nearest(t: TileId): Pair<Vec3, Vec3> {
            val la = g[0].coerceIn(t.south, t.north)
            val lo = g[1].coerceIn(t.west, t.east)
            return Wgs84.ecef(la, lo, 0.0) to Wgs84.up(la, lo)
        }

        fun distance(t: TileId): Double {
            // The eye straight above the tile: its height; otherwise the nearest point.
            if (g[0] in t.south..t.north && g[1] in t.west..t.east) return max(g[2], 1.0)
            return min((nearest(t).first - eye).length, probe(t).points.minOf { (it - eye).length })
        }

        fun visible(t: TileId): Boolean {
            val p = probe(t)
            val (np, nu) = nearest(t)
            // Over the horizon: the eye is below every point's horizon (its tangent plane), also
            // for the points lifted by the highest ground.
            val all = p.points + p.high + np
            val seen = ((eye - np) dot nu) > -HORIZON_SLACK - maxHeight ||
                p.points.indices.any { i -> ((eye - p.points[i]) dot p.ups[i]) > -HORIZON_SLACK || ((eye - p.high[i]) dot p.ups[i]) > -HORIZON_SLACK }
            if (!seen) return false
            // Outside the view: every point beyond the same side of the frustum.
            val c = all.map { clip(viewProj, it.x - eye.x, it.y - eye.y, it.z - eye.z) }
            if (c.all { it[3] <= 0 }) return false
            if (c.all { it[0] < -it[3] }) return false
            if (c.all { it[0] > it[3] }) return false
            if (c.all { it[1] < -it[3] }) return false
            if (c.all { it[1] > it[3] }) return false
            return true
        }

        val leaves = HashSet<TileId>()
        val nodes = HashSet<TileId>()
        var level = roots.filter(::visible)
        nodes += level
        while (level.isNotEmpty()) {
            val next = ArrayList<TileId>()
            for (t in level.sortedBy(::distance)) {
                val split = t.z < maxZoom &&
                    distance(t) < detail * TileId.sizeM(t.z, (t.north + t.south) / 2).coerceAtLeast(TileId.sizeM(t.z, 0.0) * 0.1) &&
                    leaves.size + next.size + 4 <= maxLeaves + level.size
                if (split) {
                    val kids = t.children.filter(::visible)
                    if (kids.isEmpty()) leaves += t else next += kids
                } else {
                    leaves += t
                }
            }
            nodes += next
            level = next
        }
        return TileSelection(roots, leaves, nodes)
    }

    /** Points this far (m) below the eye's line of sight to their horizon still count as seen. */
    private const val HORIZON_SLACK = 2_000.0
}

/** A tile's terrain on the globe: its centre (ECEF) and the vertices around it. */
class GlobeTileMesh(val center: Vec3, val vertices: FloatArray, val n: Int, val hasTerrain: Boolean)

object GlobeMesh {
    /**
     * Floats per vertex: position on the ellipsoid relative to the tile's centre (3), the up
     * direction (3), ground height (1), slope east and north (2: -dh/dx, -dh/dy), u, v (2). The
     * shader puts a vertex at position + up × height × exaggeration.
     */
    const val STRIDE = 11

    /** Grid size for a tile: finer for the big curved tiles of the globe and for nearby detail. */
    fun gridSize(z: Int): Int = if (z >= 12 || z <= 5) 33 else 17

    /** The same grid and skirt layout as [TileMesh]: its indices fit. */
    fun indices(n: Int): ShortArray = TileMesh.indices(n)

    fun build(dem: Elevation, t: TileId, n: Int = gridSize(t.z)): GlobeTileMesh {
        val m = n + 2
        val my0 = mercY(t.north)
        val my1 = mercY(t.south)
        val step = (my1 - my0) / (n - 1)
        val lonStep = (t.east - t.west) / (n - 1)
        val lats = DoubleArray(m) { r -> latOfMerc(my0 + (r - 1) * step).coerceIn(-89.9, 89.9) }
        val lons = DoubleArray(m) { c -> t.west + (c - 1) * lonStep }
        val h = FloatArray(m * m)
        var lo = Float.MAX_VALUE
        var any = false
        // Big tiles have no terrain worth sampling (and the elevation files only cover small areas).
        val sample = t.z >= 7
        for (r in 0 until m) for (c in 0 until m) {
            val v = if (sample) dem.at(lats[r], lons[c]) else Float.NaN
            h[r * m + c] = v
            if (!v.isNaN()) {
                any = true
                if (v < lo) lo = v
            }
        }
        if (!any) lo = 0f
        for (i in h.indices) if (h[i].isNaN()) h[i] = lo

        val centerLat = latOfMerc((my0 + my1) / 2)
        val center = Wgs84.ecef(centerLat, (t.west + t.east) / 2, 0.0)
        val out = FloatArray(STRIDE * TileMesh.vertexCount(n))
        fun put(i: Int, lat: Double, lon: Double, height: Float, sx: Float, sy: Float, u: Float, v: Float) {
            val p = Wgs84.ecef(lat, lon, 0.0) - center
            val up = Wgs84.up(lat, lon)
            val o = i * STRIDE
            out[o] = p.x.toFloat(); out[o + 1] = p.y.toFloat(); out[o + 2] = p.z.toFloat()
            out[o + 3] = up.x.toFloat(); out[o + 4] = up.y.toFloat(); out[o + 5] = up.z.toFloat()
            out[o + 6] = height
            out[o + 7] = sx; out[o + 8] = sy
            out[o + 9] = u; out[o + 10] = v
        }
        val lonStepM = toRadians(lonStep) * Wgs84.R
        for (r in 0 until n) for (c in 0 until n) {
            val rr = r + 1
            val cc = c + 1
            val coslat = cos(toRadians(lats[rr])).coerceAtLeast(0.01)
            val dx = 2 * lonStepM * coslat
            val dy = toRadians(lats[rr - 1] - lats[rr + 1]) * Wgs84.R
            val dhdx = (h[rr * m + cc + 1] - h[rr * m + cc - 1]) / dx
            val dhdy = (h[(rr - 1) * m + cc] - h[(rr + 1) * m + cc]) / dy
            put(r * n + c, lats[rr], lons[cc], h[rr * m + cc], (-dhdx).toFloat(), (-dhdy).toFloat(), c / (n - 1f), r / (n - 1f))
        }
        // Skirts: copies of the four edges, lowered.
        val drop = (TileId.sizeM(t.z, (t.north + t.south) / 2) * 0.04).toFloat().coerceIn(5f, 50_000f)
        val edges = arrayOf(IntArray(n) { it }, IntArray(n) { (n - 1) * n + it }, IntArray(n) { it * n }, IntArray(n) { it * n + n - 1 })
        var k = n * n
        for (e in edges) for (src in e) {
            val r = src / n
            val c = src % n
            val o = src * STRIDE
            put(k++, lats[r + 1], lons[c + 1], out[o + 6] - drop, out[o + 7], out[o + 8], out[o + 9], out[o + 10])
        }
        return GlobeTileMesh(center, out, n, any)
    }

    private const val RM = 6_378_137.0
    private fun mercY(lat: Double) = RM * ln(tan(PI / 4 + toRadians(lat.coerceIn(-85.0511, 85.0511)) / 2))
    private fun latOfMerc(y: Double) = toDegrees(2 * atan(exp(y / RM)) - PI / 2)

    @Suppress("unused")
    private fun clamp(v: Double, a: Double, b: Double) = min(b, max(a, v))
}
