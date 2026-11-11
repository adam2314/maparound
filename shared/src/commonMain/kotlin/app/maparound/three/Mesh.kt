package app.maparound.three

import app.maparound.shared.util.toRadians
import kotlin.math.abs
import kotlin.math.cos

// Plain Kotlin: geometry for the 3D view (terrain grid, building extrusion, polygon triangulation).
// Unit-tested. Coordinates are metres east (x) and north (y) of the area's centre, z up.

/** Maps lat/lon to local metres around a centre (good for areas of a few tens of km). */
class LocalFrame(val centerLat: Double, val centerLon: Double) {
    private val mPerDegLat = 111_132.954 - 559.822 * cos(2 * toRadians(centerLat))
    private val mPerDegLon = 111_412.84 * cos(toRadians(centerLat))
    fun x(lon: Double) = ((lon - centerLon) * mPerDegLon).toFloat()
    fun y(lat: Double) = ((lat - centerLat) * mPerDegLat).toFloat()
    fun lon(x: Double) = centerLon + x / mPerDegLon
    fun lat(y: Double) = centerLat + y / mPerDegLat
}

/** A building footprint in local metres (outer ring first, then holes) and its height above ground. */
class Footprint(val rings: List<FloatArray>, val heightM: Float)

object Buildings {
    /** Floats per vertex in [extrude]'s output: x, y, ground, offset above ground, nx, ny, nz. */
    const val STRIDE = 7

    /**
     * Walls and flat roofs as plain triangles. Each building stands on the lowest ground under
     * its corners; the shader puts a vertex at ground × exaggeration + offset.
     */
    fun extrude(footprints: List<Footprint>, ground: (Float, Float) -> Float): FloatArray {
        val out = FloatArrayBuilder()
        for (f in footprints) {
            val outer = f.rings.firstOrNull() ?: continue
            if (outer.size < 6) continue
            var g = Float.MAX_VALUE
            for (i in outer.indices step 2) g = minOf(g, ground(outer[i], outer[i + 1]))
            if (g == Float.MAX_VALUE || g.isNaN()) g = 0f
            val top = f.heightM
            for (ring in f.rings) {
                val n = ring.size / 2
                if (n < 3) continue
                val ccw = signedArea(ring) > 0
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = ring[2 * i]; val ay = ring[2 * i + 1]
                    val bx = ring[2 * j]; val by = ring[2 * j + 1]
                    var nx = by - ay
                    var ny = -(bx - ax)
                    if (!ccw) { nx = -nx; ny = -ny } // outward for the outer ring
                    val len = kotlin.math.sqrt(nx * nx + ny * ny)
                    if (len < 1e-4f) continue
                    nx /= len; ny /= len
                    out.vertex(ax, ay, g, 0f, nx, ny, 0f); out.vertex(bx, by, g, 0f, nx, ny, 0f); out.vertex(bx, by, g, top, nx, ny, 0f)
                    out.vertex(ax, ay, g, 0f, nx, ny, 0f); out.vertex(bx, by, g, top, nx, ny, 0f); out.vertex(ax, ay, g, top, nx, ny, 0f)
                }
            }
            for (t in triangulate(outer)) {
                out.vertex(outer[2 * t], outer[2 * t + 1], g, top, 0f, 0f, 1f)
            }
        }
        return out.toArray()
    }

    /** Twice the signed area: positive for counter-clockwise rings. */
    fun signedArea(ring: FloatArray): Float {
        val n = ring.size / 2
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1]
        }
        return a
    }

    /**
     * Ear clipping: triangles (as vertex indices, three per triangle, counter-clockwise) covering
     * a simple polygon given as x,y pairs. A closing point equal to the first is ignored.
     */
    fun triangulate(ring: FloatArray): IntArray {
        var n = ring.size / 2
        if (n >= 2 && ring[0] == ring[2 * n - 2] && ring[1] == ring[2 * n - 1]) n--
        if (n < 3) return IntArray(0)
        val ccw = signedArea(ring.copyOf(2 * n)) > 0
        val v = ArrayList<Int>(n)
        for (i in 0 until n) v += if (ccw) i else n - 1 - i
        val out = ArrayList<Int>(3 * (n - 2))
        fun x(i: Int) = ring[2 * i]
        fun y(i: Int) = ring[2 * i + 1]
        fun cross(a: Int, b: Int, c: Int) = (x(b) - x(a)) * (y(c) - y(a)) - (y(b) - y(a)) * (x(c) - x(a))
        fun inside(p: Int, a: Int, b: Int, c: Int) =
            cross(a, b, p) >= 0 && cross(b, c, p) >= 0 && cross(c, a, p) >= 0
        var guard = 0
        var i = 0
        while (v.size > 3 && guard < 4 * n * n) {
            guard++
            val m = v.size
            val a = v[(i + m - 1) % m]
            val b = v[i % m]
            val c = v[(i + 1) % m]
            var ear = cross(a, b, c) > 0
            if (ear) {
                for (p in v) {
                    if (p == a || p == b || p == c) continue
                    if (inside(p, a, b, c)) { ear = false; break }
                }
            }
            if (ear) {
                out += a; out += b; out += c
                v.removeAt(i % m)
                i = if (i % m == 0) 0 else (i % m) - 1
            } else {
                i = (i + 1) % m
            }
        }
        if (v.size == 3 && cross(v[0], v[1], v[2]) > 0) { out += v[0]; out += v[1]; out += v[2] }
        return out.toIntArray()
    }

    /** Height in metres from OSM tags: height, else levels × 3 m, else a guess from the building type. */
    fun heightOf(tags: Map<String, String>): Float {
        tags["height"]?.let { h -> Regex("""^\s*([0-9]+(?:\.[0-9]+)?)""").find(h)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it.coerceIn(2f, 600f) } }
        val levels = tags["building:levels"]?.toFloatOrNull()
        val roof = tags["roof:levels"]?.toFloatOrNull() ?: 0f
        if (levels != null) return (levels * 3f + roof * 1.5f + 1f).coerceIn(2f, 600f)
        return when (tags["building"] ?: tags["building:part"]) {
            "garage", "garages", "shed", "carport", "roof", "hut", "cabin", "kiosk" -> 3f
            "house", "detached", "semidetached_house", "terrace", "bungalow", "farm" -> 7f
            "apartments", "residential" -> 12f
            "commercial", "office", "retail", "hotel", "hospital", "school", "university" -> 14f
            "industrial", "warehouse", "hangar" -> 9f
            "church", "cathedral", "mosque", "synagogue" -> 15f
            else -> 7f
        }
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 14)
        private var n = 0
        fun vertex(x: Float, y: Float, g: Float, off: Float, nx: Float, ny: Float, nz: Float) {
            if (n + STRIDE > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x; a[n++] = y; a[n++] = g; a[n++] = off; a[n++] = nx; a[n++] = ny; a[n++] = nz
        }
        fun toArray() = a.copyOf(n)
    }
}
