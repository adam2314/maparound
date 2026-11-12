package app.maparound.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The world's country borders, from Natural Earth's 1:10m land boundary lines (public domain,
 * naturalearthdata.com), packed into `borders.bin` by tools/borders/make_borders.py.
 *
 * The offline maps have borders only where a country map is installed, and the world overview map
 * has none: these lines fill the overview zooms and the rest of the world. Accurate to a few
 * hundred metres, so the country maps' own borders are used where those are installed.
 *
 * Lines are cut into pieces of at most [PIECE] points, so a query only returns what's near, and
 * each piece is thinned for the overview zooms (Douglas–Peucker) the first time it's needed.
 */
class BorderLines private constructor(private val pieces: List<Piece>) {

    /** Part of a border line; coordinates in microdegrees as (lat, lon) pairs. */
    class Piece internal constructor(
        /** The line it's part of; pieces of a line are consecutive and share their end points. */
        val line: Int,
        /** A disputed border, line of control or an undefined frontier. */
        val contested: Boolean,
        val coords: IntArray,
    ) {
        val minLat: Int
        val minLon: Int
        val maxLat: Int
        val maxLon: Int

        init {
            var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
            for (k in coords.indices step 2) {
                a = min(a, coords[k]); c = max(c, coords[k])
                b = min(b, coords[k + 1]); d = max(d, coords[k + 1])
            }
            minLat = a; minLon = b; maxLat = c; maxLon = d
        }

        // Thinned copies per overview zoom. Filled on first use from the renderer's threads: two
        // threads may compute the same copy, which is harmless.
        private val thinned = arrayOfNulls<IntArray>(FULL_DETAIL_ZOOM)

        /** The points to draw at [zoom]: fewer at overview zooms, all from [FULL_DETAIL_ZOOM]. */
        fun coordsAt(zoom: Int): IntArray {
            if (zoom >= FULL_DETAIL_ZOOM) return coords
            val z = max(zoom, 0)
            thinned[z]?.let { return it }
            // Half a pixel at this zoom (256 px tiles), in microdegrees of longitude.
            val tolerance = 180_000_000.0 / (256L shl z)
            return simplify(coords, tolerance).also { thinned[z] = it }
        }

        fun intersects(minLat: Int, minLon: Int, maxLat: Int, maxLon: Int): Boolean =
            this.maxLat >= minLat && this.minLat <= maxLat && this.maxLon >= minLon && this.minLon <= maxLon
    }

    val pieceCount: Int get() = pieces.size
    val pointCount: Int get() = pieces.sumOf { it.coords.size / 2 }

    /** Pieces whose box meets the given one (microdegrees), in line order. */
    fun query(minLat: Int, minLon: Int, maxLat: Int, maxLon: Int, visit: (Piece) -> Unit) {
        for (p in pieces) if (p.intersects(minLat, minLon, maxLat, maxLon)) visit(p)
    }

    companion object {
        /** Most points in one piece. */
        const val PIECE = 64

        /** From this zoom the lines are drawn with all their points. */
        const val FULL_DETAIL_ZOOM = 10

        /** Reads `borders.bin`; null if it isn't a borders file. */
        fun decode(bytes: ByteArray): BorderLines? {
            if (bytes.size < 5 || bytes.decodeToString(0, 4) != "MABL" || bytes[4].toInt() != 1) return null
            var pos = 5
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
            fun zigzag(): Int { val v = varint(); return ((v ushr 1) xor -(v and 1)).toInt() }

            val pieces = ArrayList<Piece>()
            val lineCount = varint().toInt()
            for (line in 0 until lineCount) {
                val contested = bytes[pos++].toInt() == 1
                val n = varint().toInt()
                val coords = IntArray(2 * n)
                var lat = 0
                var lon = 0
                for (k in 0 until n) {
                    lat += zigzag()
                    lon += zigzag()
                    // 1e-5 degrees in the file, microdegrees here.
                    coords[2 * k] = lat * 10
                    coords[2 * k + 1] = lon * 10
                }
                // Pieces overlap by one point so the line stays joined.
                var start = 0
                while (start < n - 1) {
                    val end = min(start + PIECE - 1, n - 1)
                    pieces += Piece(line, contested, coords.copyOfRange(2 * start, 2 * end + 2))
                    start = end
                }
            }
            return BorderLines(pieces)
        }

        /** Douglas–Peucker on (lat, lon) pairs; keeps both ends. */
        internal fun simplify(coords: IntArray, tolerance: Double): IntArray {
            val n = coords.size / 2
            if (n <= 2) return coords
            val keep = BooleanArray(n)
            keep[0] = true
            keep[n - 1] = true
            val stack = ArrayList<Long>()
            stack += (0L shl 32) or (n - 1).toLong()
            while (stack.isNotEmpty()) {
                val top = stack.removeAt(stack.size - 1)
                val a = (top ushr 32).toInt()
                val b = (top and 0xFFFFFFFFL).toInt()
                if (b - a < 2) continue
                val ay = coords[2 * a].toDouble(); val ax = coords[2 * a + 1].toDouble()
                val by = coords[2 * b].toDouble(); val bx = coords[2 * b + 1].toDouble()
                val dx = bx - ax
                val dy = by - ay
                val len = kotlin.math.sqrt(dx * dx + dy * dy)
                var worst = -1.0
                var at = -1
                for (i in a + 1 until b) {
                    val py = coords[2 * i].toDouble(); val px = coords[2 * i + 1].toDouble()
                    val d = if (len == 0.0) {
                        kotlin.math.hypot(px - ax, py - ay)
                    } else {
                        abs(dx * (ay - py) - (ax - px) * dy) / len
                    }
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
    }
}
