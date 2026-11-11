package app.maparound.three

import app.maparound.tools.Elevation
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

// Plain Kotlin: which map tiles to show in the streamed 3D view, and their terrain meshes. Unit-tested.

/** A Web Mercator (XYZ) tile. */
data class TileId(val z: Int, val x: Int, val y: Int) {
    val north: Double get() = latOf(y, z)
    val south: Double get() = latOf(y + 1, z)
    val west: Double get() = lonOf(x, z)
    val east: Double get() = lonOf(x + 1, z)
    val parent: TileId? get() = if (z == 0) null else TileId(z - 1, x shr 1, y shr 1)
    val children: List<TileId>
        get() = listOf(TileId(z + 1, 2 * x, 2 * y), TileId(z + 1, 2 * x + 1, 2 * y), TileId(z + 1, 2 * x, 2 * y + 1), TileId(z + 1, 2 * x + 1, 2 * y + 1))

    /** Whether [other] lies inside this tile (or is this tile). */
    fun contains(other: TileId): Boolean = other.z >= z && (other.x shr (other.z - z)) == x && (other.y shr (other.z - z)) == y

    fun ancestorAt(zoom: Int): TileId = if (zoom >= z) this else TileId(zoom, x shr (z - zoom), y shr (z - zoom))

    companion object {
        fun lonOf(x: Int, z: Int) = x / (1 shl z).toDouble() * 360.0 - 180.0
        fun latOf(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / (1 shl z)))))
        fun xOf(lon: Double, z: Int) = floor((lon + 180) / 360 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
        fun yOf(lat: Double, z: Int): Int {
            val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)
        }
        /** Ground size of a tile at a latitude, metres. */
        fun sizeM(z: Int, lat: Double) = 40_075_016.686 * cos(Math.toRadians(lat)) / (1 shl z)
    }
}

/** The tiles to show: [leaves] at the wanted detail, plus every ancestor in [nodes] (drawn while children load). */
class TileSelection(val roots: List<TileId>, val leaves: Set<TileId>, val nodes: Set<TileId>)

object TileSelector {
    /**
     * Quadtree level of detail: a tile is split while the eye is closer to it than [detail] × its
     * size, so nearby ground gets small detailed tiles and the distance large coarse ones. Tiles
     * farther than [rangeM] from the target, or well behind the eye, are left out.
     */
    fun select(
        frame: LocalFrame,
        eyeX: Double, eyeY: Double, eyeZ: Double,
        targetX: Double, targetY: Double, groundZ: Double,
        rangeM: Double,
        maxZoom: Int,
        detail: Double = 1.4,
        maxLeaves: Int = 110,
    ): TileSelection {
        val north = frame.lat(targetY + rangeM)
        val south = frame.lat(targetY - rangeM)
        val west = frame.lon(targetX - rangeM)
        val east = frame.lon(targetX + rangeM)
        // Roots: the zoom where a tile is about the size of the view range.
        var zr = 2
        while (zr < maxZoom && TileId.sizeM(zr + 1, frame.centerLat) > rangeM) zr++
        val roots = ArrayList<TileId>()
        for (x in TileId.xOf(west, zr)..TileId.xOf(east, zr)) for (y in TileId.yOf(north, zr)..TileId.yOf(south, zr)) roots += TileId(zr, x, y)

        val fx = targetX - eyeX
        val fy = targetY - eyeY
        val flen = sqrt(fx * fx + fy * fy).coerceAtLeast(1e-6)
        val leaves = HashSet<TileId>()
        val nodes = HashSet<TileId>()

        fun distance(t: TileId): Double {
            val x0 = frame.x(t.west).toDouble()
            val x1 = frame.x(t.east).toDouble()
            val y0 = frame.y(t.south).toDouble()
            val y1 = frame.y(t.north).toDouble()
            val dx = max(0.0, max(x0 - eyeX, eyeX - x1))
            val dy = max(0.0, max(y0 - eyeY, eyeY - y1))
            val dz = eyeZ - groundZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun visible(t: TileId): Boolean {
            val x0 = frame.x(t.west).toDouble()
            val x1 = frame.x(t.east).toDouble()
            val y0 = frame.y(t.south).toDouble()
            val y1 = frame.y(t.north).toDouble()
            // Within range of the target…
            val dx = max(0.0, max(x0 - targetX, targetX - x1))
            val dy = max(0.0, max(y0 - targetY, targetY - y1))
            if (dx * dx + dy * dy > rangeM * rangeM) return false
            // …and not entirely behind the eye (any corner in front, or just behind, counts).
            val corners = arrayOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)
            val margin = min(max(x1 - x0, y1 - y0), 500.0)
            return corners.any { (cx, cy) -> ((cx - eyeX) * fx + (cy - eyeY) * fy) / flen > -margin }
        }

        // Breadth-first so the leaf budget refines the nearest tiles first.
        var level = roots.filter(::visible)
        nodes += level
        while (level.isNotEmpty()) {
            val next = ArrayList<TileId>()
            val sorted = level.sortedBy(::distance)
            for (t in sorted) {
                val split = t.z < maxZoom &&
                    distance(t) < detail * TileId.sizeM(t.z, (t.north + t.south) / 2) &&
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
}

/** A tile's terrain: an n×n grid, plus a "skirt" hanging down from its edges that hides cracks between tiles of different detail. */
object TileMesh {
    /** Floats per vertex: x, y, z, nx, ny, nz (unnormalised, see below), u, v. */
    const val STRIDE = 8

    fun vertexCount(n: Int) = n * n + 4 * n

    /**
     * Heights from [dem] (NaN → the lowest known height in the tile, or 0). Normals are stored as
     * (-dh/dx, -dh/dy, 1) so the shader can apply a vertical exaggeration; heights one step
     * outside the tile are sampled so normals match across tile edges.
     */
    fun build(dem: Elevation, t: TileId, frame: LocalFrame, n: Int): Pair<FloatArray, Boolean> {
        val m = n + 2 // one extra sample on each side
        val my0 = mercY(t.north)
        val my1 = mercY(t.south)
        val step = (my1 - my0) / (n - 1)
        val lonStep = (t.east - t.west) / (n - 1)
        val h = FloatArray(m * m)
        val px = FloatArray(m)
        val py = FloatArray(m)
        var lo = Float.MAX_VALUE
        var any = false
        for (r in 0 until m) {
            val lat = latOfMerc(my0 + (r - 1) * step)
            py[r] = frame.y(lat)
            for (c in 0 until m) {
                val lon = t.west + (c - 1) * lonStep
                if (r == 0) px[c] = frame.x(lon)
                val v = dem.at(lat, lon)
                h[r * m + c] = v
                if (!v.isNaN()) {
                    any = true
                    if (v < lo) lo = v
                }
            }
        }
        if (!any) lo = 0f
        for (i in h.indices) if (h[i].isNaN()) h[i] = lo
        val out = FloatArray(STRIDE * vertexCount(n))
        fun put(i: Int, x: Float, y: Float, z: Float, nx: Float, ny: Float, u: Float, v: Float) {
            val o = i * STRIDE
            out[o] = x; out[o + 1] = y; out[o + 2] = z; out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = 1f; out[o + 6] = u; out[o + 7] = v
        }
        for (r in 0 until n) for (c in 0 until n) {
            val rr = r + 1
            val cc = c + 1
            val dx = px[cc + 1] - px[cc - 1]
            val dy = py[rr - 1] - py[rr + 1]
            val dhdx = (h[rr * m + cc + 1] - h[rr * m + cc - 1]) / dx
            val dhdy = (h[(rr - 1) * m + cc] - h[(rr + 1) * m + cc]) / dy
            put(r * n + c, px[cc], py[rr], h[rr * m + cc], -dhdx, -dhdy, c / (n - 1f), r / (n - 1f))
        }
        // Skirts: copies of the four edges, lowered.
        val drop = (TileId.sizeM(t.z, (t.north + t.south) / 2) * 0.04).toFloat().coerceAtLeast(5f)
        val edges = arrayOf(
            IntArray(n) { it }, // top row
            IntArray(n) { (n - 1) * n + it }, // bottom row
            IntArray(n) { it * n }, // left column
            IntArray(n) { it * n + n - 1 }, // right column
        )
        var k = n * n
        for (e in edges) for (src in e) {
            val o = src * STRIDE
            put(k++, out[o], out[o + 1], out[o + 2] - drop, out[o + 3], out[o + 4], out[o + 6], out[o + 7])
        }
        return out to any
    }

    /** Triangle indices for [build]'s layout (the same for every tile of size n). */
    fun indices(n: Int): ShortArray {
        val list = ArrayList<Int>(6 * (n - 1) * (n - 1) + 4 * 6 * (n - 1))
        for (r in 0 until n - 1) for (c in 0 until n - 1) {
            val a = r * n + c
            val b = a + 1
            val d = a + n
            val e = d + 1
            list += listOf(a, d, b, b, d, e)
        }
        val skirtBase = n * n
        val edges = arrayOf(IntArray(n) { it }, IntArray(n) { (n - 1) * n + it }, IntArray(n) { it * n }, IntArray(n) { it * n + n - 1 })
        edges.forEachIndexed { ei, e ->
            val s0 = skirtBase + ei * n
            for (i in 0 until n - 1) {
                list += listOf(e[i], s0 + i, e[i + 1], e[i + 1], s0 + i, s0 + i + 1)
            }
        }
        return ShortArray(list.size) { list[it].toShort() }
    }

    private const val R = 6_378_137.0
    private fun mercY(lat: Double) = R * ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0511, 85.0511)) / 2))
    private fun latOfMerc(y: Double) = Math.toDegrees(2 * atan(kotlin.math.exp(y / R)) - PI / 2)

    @Suppress("unused")
    private fun clamp(v: Double, a: Double, b: Double) = min(b, max(a, v))
}
