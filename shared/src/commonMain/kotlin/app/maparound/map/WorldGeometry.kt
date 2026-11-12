package app.maparound.map

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Geometry for the built-in world data (WorldData, BorderLines): coordinates are microdegrees in
// IntArrays of (lat, lon) pairs.

/** A box in microdegrees. */
data class GeoBox(val minLat: Int, val minLon: Int, val maxLat: Int, val maxLon: Int) {
    fun intersects(o: GeoBox): Boolean = o.maxLat >= minLat && o.minLat <= maxLat && o.maxLon >= minLon && o.minLon <= maxLon
    fun intersects(aLat: Int, aLon: Int, bLat: Int, bLon: Int): Boolean = bLat >= minLat && aLat <= maxLat && bLon >= minLon && aLon <= maxLon
    fun contains(lat: Int, lon: Int): Boolean = lat in minLat..maxLat && lon in minLon..maxLon
    val isEmpty: Boolean get() = minLat >= maxLat || minLon >= maxLon

    fun grow(dLat: Int, dLon: Int) = GeoBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)

    companion object {
        fun of(coords: IntArray): GeoBox {
            var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
            for (k in coords.indices step 2) {
                a = min(a, coords[k]); c = max(c, coords[k])
                b = min(b, coords[k + 1]); d = max(d, coords[k + 1])
            }
            return GeoBox(a, b, c, d)
        }
    }
}

/** Half a pixel at [zoom] (256 px tiles), in microdegrees of longitude. */
fun halfPixel(zoom: Int): Double = 180_000_000.0 / (256L shl zoom.coerceIn(0, 30))

/** From this zoom the world data's lines and areas are drawn with all their points. */
const val FULL_DETAIL_ZOOM = 10

/**
 * A polyline with copies thinned for the overview zooms (half a pixel), each made the first time
 * it's needed. Filled from the renderer's threads: two may compute the same copy, which is harmless.
 */
open class ThinnedLine(val coords: IntArray) {
    val box: GeoBox = GeoBox.of(coords)
    private val thinned = arrayOfNulls<IntArray>(FULL_DETAIL_ZOOM)

    /** The points to draw at [zoom]: fewer at overview zooms, all from [FULL_DETAIL_ZOOM]. */
    fun coordsAt(zoom: Int): IntArray {
        if (zoom >= FULL_DETAIL_ZOOM) return coords
        val z = max(zoom, 0)
        thinned[z]?.let { return it }
        return douglasPeucker(coords, halfPixel(z)).also { thinned[z] = it }
    }
}

/** Cuts a polyline into pieces of at most [size] points; neighbours share their end point. */
fun cutLine(coords: IntArray, size: Int): List<IntArray> {
    val n = coords.size / 2
    val out = ArrayList<IntArray>()
    var start = 0
    while (start < n - 1) {
        val end = min(start + size - 1, n - 1)
        out += coords.copyOfRange(2 * start, 2 * end + 2)
        start = end
    }
    return out
}

/** Joins consecutive pieces of the same line (sharing an end point) back into one polyline. */
class LineJoiner(private val emit: (key: Any, coords: IntArray) -> Unit) {
    private var key: Any? = null
    private var buf = IntArray(256)
    private var m = 0

    fun add(key: Any, coords: IntArray) {
        val joins = this.key == key && m >= 2 && buf[m - 2] == coords[0] && buf[m - 1] == coords[1]
        if (!joins) flush()
        this.key = key
        for (k in (if (joins) 2 else 0) until coords.size) {
            if (m >= buf.size) buf = buf.copyOf(buf.size * 2)
            buf[m++] = coords[k]
        }
    }

    fun flush() {
        val k = key
        if (k != null && m >= 4) emit(k, buf.copyOf(m))
        key = null
        m = 0
    }
}

/** Reads the packed world files (varints, zigzag deltas). */
internal class PackedReader(private val bytes: ByteArray, var pos: Int) {
    fun byte(): Int = bytes[pos++].toInt() and 0xFF

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    fun int(): Int = varint().toInt()

    fun zigzag(): Int {
        val v = varint()
        return ((v ushr 1) xor -(v and 1)).toInt()
    }

    fun text(): String {
        val n = int()
        return bytes.decodeToString(pos, pos + n).also { pos += n }
    }

    /** A point count and the points as deltas, scaled to microdegrees. */
    fun points(toMicro: Int): IntArray {
        val n = int()
        val out = IntArray(2 * n)
        var lat = 0
        var lon = 0
        for (k in 0 until n) {
            lat += zigzag()
            lon += zigzag()
            out[2 * k] = lat * toMicro
            out[2 * k + 1] = lon * toMicro
        }
        return out
    }
}

/** Douglas–Peucker on (lat, lon) pairs; keeps both ends. */
fun douglasPeucker(coords: IntArray, tolerance: Double): IntArray {
    val n = coords.size / 2
    if (n <= 2) return coords
    val keep = BooleanArray(n)
    keep[0] = true
    keep[n - 1] = true
    val stack = ArrayList<Long>()
    stack += (n - 1).toLong()
    while (stack.isNotEmpty()) {
        val top = stack.removeAt(stack.size - 1)
        val a = (top ushr 32).toInt()
        val b = (top and 0xFFFFFFFFL).toInt()
        if (b - a < 2) continue
        val ay = coords[2 * a].toDouble(); val ax = coords[2 * a + 1].toDouble()
        val by = coords[2 * b].toDouble(); val bx = coords[2 * b + 1].toDouble()
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        var worst = -1.0
        var at = -1
        for (i in a + 1 until b) {
            val py = coords[2 * i].toDouble(); val px = coords[2 * i + 1].toDouble()
            val d = if (len == 0.0) hypot(px - ax, py - ay) else abs(dx * (ay - py) - (ax - px) * dy) / len
            if (d > worst) { worst = d; at = i }
        }
        if (worst > tolerance) {
            keep[at] = true
            stack += (a.toLong() shl 32) or at.toLong()
            stack += (at.toLong() shl 32) or b.toLong()
        }
    }
    val out = IntArray(2 * keep.count { it })
    var j = 0
    for (i in 0 until n) if (keep[i]) {
        out[j++] = coords[2 * i]
        out[j++] = coords[2 * i + 1]
    }
    return out
}

/**
 * A ring (implicitly closed) ready for clipping to boxes: its points, its orientation, and boxes of
 * runs of [RUN] points, so the parts far from a box can be skipped.
 */
class ClipRing(val coords: IntArray) {
    val box = GeoBox.of(coords)

    /** True if it runs counter-clockwise (with east to the right and north up). */
    val counterClockwise: Boolean
    private val runs: IntArray

    init {
        val n = coords.size / 2
        var area = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            area += coords[2 * i + 1].toDouble() * coords[2 * j] - coords[2 * j + 1].toDouble() * coords[2 * i]
        }
        counterClockwise = area > 0
        val count = (n + RUN - 1) / RUN
        runs = IntArray(4 * count)
        for (r in 0 until count) {
            val from = r * RUN
            val to = min(n, from + RUN)
            var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
            for (i in from until to) {
                a = min(a, coords[2 * i]); c = max(c, coords[2 * i])
                b = min(b, coords[2 * i + 1]); d = max(d, coords[2 * i + 1])
            }
            runs[4 * r] = a; runs[4 * r + 1] = b; runs[4 * r + 2] = c; runs[4 * r + 3] = d
        }
    }

    /**
     * The ring cut to [clip]: the pieces of it inside, each a ring in the same direction (empty if
     * none). Where it leaves the box and comes back, the pieces follow the box's edge between, so
     * they're proper rings with no zero-width corridors along the edge.
     */
    fun clip(clip: GeoBox): List<IntArray> {
        if (!box.intersects(clip)) return emptyList()
        if (clip.minLat <= box.minLat && clip.minLon <= box.minLon && clip.maxLat >= box.maxLat && clip.maxLon >= box.maxLon) {
            return listOf(coords)
        }
        val pts = nearPoints(clip)
        val n = pts.size / 2
        // Start at a point outside, so every piece inside begins where the ring enters the box.
        var start = -1
        for (i in 0 until n) {
            val lat = pts[2 * i]
            val lon = pts[2 * i + 1]
            if (lat < clip.minLat || lat > clip.maxLat || lon < clip.minLon || lon > clip.maxLon) { start = i; break }
        }
        if (start < 0) return listOf(coords)

        val chains = ArrayList<IntArray>()
        var cur: IntBuffer? = null
        for (k in 0 until n) {
            val i = (start + k) % n
            val j = (i + 1) % n
            val aLat = pts[2 * i].toDouble(); val aLon = pts[2 * i + 1].toDouble()
            val bLat = pts[2 * j].toDouble(); val bLon = pts[2 * j + 1].toDouble()
            val t = liangBarsky(aLat, aLon, bLat, bLon, clip) ?: continue
            val t0 = t.first
            val t1 = t.second
            if (cur == null) {
                if (t1 <= t0) continue // only touches the box
                cur = IntBuffer()
                cur.add(snap(aLat + (bLat - aLat) * t0, clip.minLat, clip.maxLat), snap(aLon + (bLon - aLon) * t0, clip.minLon, clip.maxLon))
            }
            cur.add(snap(aLat + (bLat - aLat) * t1, clip.minLat, clip.maxLat), snap(aLon + (bLon - aLon) * t1, clip.minLon, clip.maxLon))
            if (t1 < 1.0) {
                chains += cur.toArray()
                cur = null
            }
        }

        if (chains.isEmpty()) {
            // Not crossing the box: the box is wholly inside the ring, or wholly outside.
            val cLat = (clip.minLat.toLong() + clip.maxLat) / 2
            val cLon = (clip.minLon.toLong() + clip.maxLon) / 2
            return if (contains(pts, cLat.toDouble(), cLon.toDouble())) listOf(rect(clip, counterClockwise)) else emptyList()
        }

        // Join the pieces: from where one leaves the box, follow the box's edge (in the ring's
        // direction, so the inside stays on the same side) to the next place the ring enters.
        val w = (clip.maxLon.toLong() - clip.minLon).toDouble()
        val h = (clip.maxLat.toLong() - clip.minLat).toDouble()
        val perimeter = 2 * (w + h)
        fun pos(lat: Int, lon: Int): Double = when {
            lat == clip.minLat -> (lon.toLong() - clip.minLon).toDouble()
            lon == clip.maxLon -> w + (lat.toLong() - clip.minLat)
            lat == clip.maxLat -> w + h + (clip.maxLon.toLong() - lon)
            else -> 2 * w + h + (clip.maxLat.toLong() - lat)
        }
        val corners = doubleArrayOf(0.0, w, w + h, 2 * w + h)
        val cornerPts = arrayOf(
            intArrayOf(clip.minLat, clip.minLon), intArrayOf(clip.minLat, clip.maxLon),
            intArrayOf(clip.maxLat, clip.maxLon), intArrayOf(clip.maxLat, clip.minLon),
        )
        fun ahead(from: Double, to: Double): Double {
            val d = if (counterClockwise) to - from else from - to
            return ((d % perimeter) + perimeter) % perimeter
        }
        val entry = DoubleArray(chains.size) { pos(chains[it][0], chains[it][1]) }
        val exit = DoubleArray(chains.size) { val c = chains[it]; pos(c[c.size - 2], c[c.size - 1]) }
        val used = BooleanArray(chains.size)
        val out = ArrayList<IntArray>()
        for (s in chains.indices) {
            if (used[s]) continue
            val ring = IntBuffer()
            var c = s
            while (true) {
                used[c] = true
                val pts2 = chains[c]
                for (k in pts2.indices step 2) ring.addUnlessSame(pts2[k], pts2[k + 1])
                // The next entry along the edge; closing back at the first piece ends the ring.
                var best = -1
                var bestD = Double.MAX_VALUE
                for (k in chains.indices) {
                    if (used[k] && k != s) continue
                    val d = ahead(exit[c], entry[k])
                    if (d < bestD) { bestD = d; best = k }
                }
                if (best < 0) break
                // The box's corners passed on the way.
                val passed = (0 until 4).map { it to ahead(exit[c], corners[it]) }
                    .filter { it.second > 0.0 && it.second < bestD }
                    .sortedBy { it.second }
                for ((corner, _) in passed) ring.addUnlessSame(cornerPts[corner][0], cornerPts[corner][1])
                if (best == s) break
                c = best
            }
            if (ring.size >= 3) out += ring.toArray()
        }
        return out
    }

    /**
     * The ring's points, with runs wholly on one outer side of the box replaced by their two ends:
     * only the edges to their neighbours can cross it, and the line between the ends stays on the
     * same side. The ring's part inside the box is unchanged.
     */
    private fun nearPoints(clip: GeoBox): IntArray {
        val n = coords.size / 2
        val buf = IntBuffer(min(coords.size, 4 * runs.size / 4 + 64))
        for (r in 0 until runs.size / 4) {
            val from = r * RUN
            val to = min(n, from + RUN)
            val outside = runs[4 * r + 2] < clip.minLat || runs[4 * r] > clip.maxLat ||
                runs[4 * r + 3] < clip.minLon || runs[4 * r + 1] > clip.maxLon
            if (outside && to - from > 2) {
                buf.add(coords[2 * from], coords[2 * from + 1])
                buf.add(coords[2 * (to - 1)], coords[2 * (to - 1) + 1])
            } else {
                for (i in from until to) buf.add(coords[2 * i], coords[2 * i + 1])
            }
        }
        return buf.toArray()
    }

    companion object {
        const val RUN = 32

        /** The part of segment a→b inside the box, as (t0, t1), or null. */
        private fun liangBarsky(aLat: Double, aLon: Double, bLat: Double, bLon: Double, c: GeoBox): Pair<Double, Double>? {
            val dLat = bLat - aLat
            val dLon = bLon - aLon
            var t0 = 0.0
            var t1 = 1.0
            val ps = doubleArrayOf(-dLon, dLon, -dLat, dLat)
            val qs = doubleArrayOf(aLon - c.minLon, c.maxLon - aLon, aLat - c.minLat, c.maxLat - aLat)
            for (k in 0 until 4) {
                val p = ps[k]
                val q = qs[k]
                if (p == 0.0) {
                    if (q < 0) return null
                } else {
                    val r = q / p
                    if (p < 0) {
                        if (r > t1) return null
                        if (r > t0) t0 = r
                    } else {
                        if (r < t0) return null
                        if (r < t1) t1 = r
                    }
                }
            }
            return t0 to t1
        }

        /** Rounds a coordinate, onto the box's edge when it's within rounding of it. */
        private fun snap(v: Double, lo: Int, hi: Int): Int {
            val r = kotlin.math.round(v).toLong()
            return r.coerceIn(lo.toLong(), hi.toLong()).toInt()
        }

        /** Even–odd point in polygon. */
        private fun contains(p: IntArray, lat: Double, lon: Double): Boolean {
            val n = p.size / 2
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val yi = p[2 * i].toDouble(); val xi = p[2 * i + 1].toDouble()
                val yj = p[2 * j].toDouble(); val xj = p[2 * j + 1].toDouble()
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }

        private fun rect(b: GeoBox, counterClockwise: Boolean): IntArray {
            val ccw = intArrayOf(b.minLat, b.minLon, b.minLat, b.maxLon, b.maxLat, b.maxLon, b.maxLat, b.minLon)
            return if (counterClockwise) ccw else intArrayOf(b.minLat, b.minLon, b.maxLat, b.minLon, b.maxLat, b.maxLon, b.minLat, b.maxLon)
        }
    }
}

/** A growable list of (lat, lon) pairs. */
internal class IntBuffer(capacity: Int = 32) {
    private var a = IntArray(max(capacity, 4))
    private var m = 0

    /** Points in it. */
    val size: Int get() = m / 2

    fun add(lat: Int, lon: Int) {
        if (m + 2 > a.size) a = a.copyOf(a.size * 2)
        a[m++] = lat
        a[m++] = lon
    }

    fun addUnlessSame(lat: Int, lon: Int) {
        if (m >= 2 && a[m - 2] == lat && a[m - 1] == lon) return
        add(lat, lon)
    }

    fun toArray(): IntArray = a.copyOf(m)
}

/** The parts of a polyline outside [box] (it cut out), each at least 2 points. */
fun cutOutBox(coords: IntArray, box: GeoBox): List<IntArray> {
    if (!GeoBox.of(coords).intersects(box)) return listOf(coords)
    val out = ArrayList<IntArray>()
    var cur = IntBuffer()
    fun flush() {
        if (cur.size >= 2) out += cur.toArray()
        cur = IntBuffer()
    }
    for (i in 0 until coords.size / 2 - 1) {
        val aLat = coords[2 * i].toDouble(); val aLon = coords[2 * i + 1].toDouble()
        val bLat = coords[2 * i + 2].toDouble(); val bLon = coords[2 * i + 3].toDouble()
        val dLat = bLat - aLat
        val dLon = bLon - aLon
        // The part inside the box (Liang–Barsky), if any.
        var t0 = 0.0
        var t1 = 1.0
        var hits = true
        val ps = doubleArrayOf(-dLon, dLon, -dLat, dLat)
        val qs = doubleArrayOf(aLon - box.minLon, box.maxLon - aLon, aLat - box.minLat, box.maxLat - aLat)
        for (k in 0 until 4) {
            val p = ps[k]
            val q = qs[k]
            if (p == 0.0) {
                if (q < 0) { hits = false; break }
            } else {
                val r = q / p
                if (p < 0) { if (r > t1) { hits = false; break }; if (r > t0) t0 = r }
                else { if (r < t0) { hits = false; break }; if (r < t1) t1 = r }
            }
        }
        if (!hits || t1 <= t0) {
            cur.addUnlessSame(coords[2 * i], coords[2 * i + 1])
            cur.addUnlessSame(coords[2 * i + 2], coords[2 * i + 3])
            continue
        }
        if (t0 > 0.0) {
            cur.addUnlessSame(coords[2 * i], coords[2 * i + 1])
            cur.addUnlessSame(kotlin.math.round(aLat + dLat * t0).toInt(), kotlin.math.round(aLon + dLon * t0).toInt())
        }
        flush()
        if (t1 < 1.0) {
            cur.add(kotlin.math.round(aLat + dLat * t1).toInt(), kotlin.math.round(aLon + dLon * t1).toInt())
            cur.addUnlessSame(coords[2 * i + 2], coords[2 * i + 3])
        }
    }
    flush()
    return out
}

/** The parts of a polyline inside [clip] (Liang–Barsky per segment), each at least 2 points. */
fun clipLine(coords: IntArray, clip: GeoBox): List<IntArray> {
    val out = ArrayList<IntArray>()
    var cur = IntArray(16)
    var m = 0
    fun add(lat: Int, lon: Int) {
        if (m >= 2 && cur[m - 2] == lat && cur[m - 1] == lon) return
        if (m + 2 > cur.size) cur = cur.copyOf(cur.size * 2)
        cur[m++] = lat; cur[m++] = lon
    }
    fun flush() {
        if (m >= 4) out += cur.copyOf(m)
        m = 0
    }
    for (i in 0 until coords.size / 2 - 1) {
        val aLat = coords[2 * i].toDouble(); val aLon = coords[2 * i + 1].toDouble()
        val bLat = coords[2 * i + 2].toDouble(); val bLon = coords[2 * i + 3].toDouble()
        val dLat = bLat - aLat
        val dLon = bLon - aLon
        var t0 = 0.0
        var t1 = 1.0
        var visible = true
        for ((p, q) in arrayOf(
            -dLon to aLon - clip.minLon, dLon to clip.maxLon - aLon,
            -dLat to aLat - clip.minLat, dLat to clip.maxLat - aLat,
        )) {
            if (p == 0.0) {
                if (q < 0) { visible = false; break }
            } else {
                val r = q / p
                if (p < 0) { if (r > t1) { visible = false; break } else if (r > t0) t0 = r }
                else { if (r < t0) { visible = false; break } else if (r < t1) t1 = r }
            }
        }
        if (!visible) { flush(); continue }
        if (t0 > 0.0) flush()
        add((aLat + dLat * t0).toInt(), (aLon + dLon * t0).toInt())
        add((aLat + dLat * t1).toInt(), (aLon + dLon * t1).toInt())
        if (t1 < 1.0) flush()
    }
    flush()
    return out
}

/** [box] minus the [holes]: rectangles covering the rest (none if it's all covered). */
fun subtractBoxes(box: GeoBox, holes: List<GeoBox>): List<GeoBox> {
    var parts = listOf(box)
    for (h in holes) {
        if (!h.intersects(box)) continue
        val next = ArrayList<GeoBox>()
        for (r in parts) {
            if (!r.intersects(h) || h.maxLat <= r.minLat || h.minLat >= r.maxLat || h.maxLon <= r.minLon || h.minLon >= r.maxLon) {
                next += r
                continue
            }
            // Below and above the hole across the whole width, then left and right of it between.
            if (h.minLat > r.minLat) next += GeoBox(r.minLat, r.minLon, h.minLat, r.maxLon)
            if (h.maxLat < r.maxLat) next += GeoBox(h.maxLat, r.minLon, r.maxLat, r.maxLon)
            val lo = max(r.minLat, h.minLat)
            val hi = min(r.maxLat, h.maxLat)
            if (h.minLon > r.minLon) next += GeoBox(lo, r.minLon, hi, h.minLon)
            if (h.maxLon < r.maxLon) next += GeoBox(lo, h.maxLon, hi, r.maxLon)
        }
        parts = next.filterNot { it.isEmpty }
    }
    return parts
}
