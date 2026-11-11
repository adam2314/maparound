package app.maparound.terrain

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Contour tracing (marching squares) over a regular height grid, with the per-cell segments
 * joined into continuous polylines so the renderer can draw smooth curves and place labels.
 */
object Contours {

    /** A traced line in grid coordinates: x = column, y = row (row 0 = first/top row). */
    class Line(val level: Int, val xy: FloatArray, val closed: Boolean) {
        val pointCount: Int get() = xy.size / 2
    }

    /**
     * @param grid heights, row-major, [rows] × [cols]; NaN marks missing data.
     * @param interval vertical distance between contour levels in metres.
     * @param skipZero leave out the 0 m level: it only traces the (flattened) sea surface.
     *   Negative levels are kept for land below sea level such as the Dead Sea basin.
     */
    fun trace(grid: FloatArray, cols: Int, rows: Int, interval: Int, skipZero: Boolean = true): List<Line> {
        require(grid.size == cols * rows && cols >= 2 && rows >= 2)
        val byLevel = HashMap<Int, SegmentBuffer>()

        for (i in 0 until rows - 1) {
            for (j in 0 until cols - 1) {
                val v0 = grid[i * cols + j]            // top-left
                val v1 = grid[i * cols + j + 1]        // top-right
                val v2 = grid[(i + 1) * cols + j + 1]  // bottom-right
                val v3 = grid[(i + 1) * cols + j]      // bottom-left
                if (v0.isNaN() || v1.isNaN() || v2.isNaN() || v3.isNaN()) continue
                val lo = minOf(minOf(v0, v1), minOf(v2, v3))
                val hi = maxOf(maxOf(v0, v1), maxOf(v2, v3))
                var level = (ceil(lo / interval) * interval).toInt()
                while (level <= hi) {
                    if (!(skipZero && level == 0)) {
                        // Offset avoids degenerate cases where integer heights equal the level.
                        val t = level + 0.01f
                        val idx = (if (v0 > t) 1 else 0) or (if (v1 > t) 2 else 0) or
                            (if (v2 > t) 4 else 0) or (if (v3 > t) 8 else 0)
                        if (idx != 0 && idx != 15) {
                            val buf = byLevel.getOrPut(level) { SegmentBuffer() }
                            emitCell(buf, idx, i, j, cols, v0, v1, v2, v3, t)
                        }
                    }
                    level += interval
                }
            }
        }
        val out = ArrayList<Line>()
        byLevel.forEach { (level, buf) -> join(buf, level, out) }
        return out
    }

    // Edge ids: horizontal edge (row i, between col j and j+1) = 2*(i*cols+j);
    //           vertical edge (col j, between row i and i+1)   = 2*(i*cols+j)+1.
    private const val T = 0
    private const val R = 1
    private const val B = 2
    private const val L = 3

    private fun emitCell(
        buf: SegmentBuffer, idx: Int, i: Int, j: Int, cols: Int,
        v0: Float, v1: Float, v2: Float, v3: Float, t: Float,
    ) {
        fun seg(a: Int, b: Int) = buf.add(
            edgeId(a, i, j, cols), edgeX(a, j, v0, v1, v2, v3, t), edgeY(a, i, v0, v1, v2, v3, t),
            edgeId(b, i, j, cols), edgeX(b, j, v0, v1, v2, v3, t), edgeY(b, i, v0, v1, v2, v3, t),
        )
        when (idx) {
            1, 14 -> seg(T, L)
            2, 13 -> seg(T, R)
            4, 11 -> seg(R, B)
            8, 7 -> seg(B, L)
            3, 12 -> seg(L, R)
            6, 9 -> seg(T, B)
            5 -> { // top-left and bottom-right high: saddle, resolve with the cell centre
                if ((v0 + v1 + v2 + v3) / 4f > t) { seg(T, R); seg(B, L) } else { seg(T, L); seg(R, B) }
            }
            10 -> { // top-right and bottom-left high
                if ((v0 + v1 + v2 + v3) / 4f > t) { seg(T, L); seg(R, B) } else { seg(T, R); seg(B, L) }
            }
        }
    }

    private fun edgeId(edge: Int, i: Int, j: Int, cols: Int): Int = when (edge) {
        T -> 2 * (i * cols + j)
        B -> 2 * ((i + 1) * cols + j)
        L -> 2 * (i * cols + j) + 1
        else -> 2 * (i * cols + j + 1) + 1 // R
    }

    private fun frac(a: Float, b: Float, t: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

    private fun edgeX(edge: Int, j: Int, v0: Float, v1: Float, v2: Float, v3: Float, t: Float): Float = when (edge) {
        T -> j + frac(v0, v1, t)
        B -> j + frac(v3, v2, t)
        L -> j.toFloat()
        else -> (j + 1).toFloat()
    }

    private fun edgeY(edge: Int, i: Int, v0: Float, v1: Float, v2: Float, v3: Float, t: Float): Float = when (edge) {
        T -> i.toFloat()
        B -> (i + 1).toFloat()
        L -> i + frac(v0, v3, t)
        else -> i + frac(v1, v2, t)
    }

    /** Growable parallel arrays of segments for one level. */
    private class SegmentBuffer {
        var n = 0
        var ea = IntArray(64); var eb = IntArray(64)
        var pts = FloatArray(256) // ax, ay, bx, by per segment

        fun add(a: Int, ax: Float, ay: Float, b: Int, bx: Float, by: Float) {
            if (n == ea.size) {
                ea = ea.copyOf(n * 2); eb = eb.copyOf(n * 2); pts = pts.copyOf(n * 8)
            }
            ea[n] = a; eb[n] = b
            pts[4 * n] = ax; pts[4 * n + 1] = ay; pts[4 * n + 2] = bx; pts[4 * n + 3] = by
            n++
        }
    }

    /** Chains segments that share a grid edge into polylines. */
    private fun join(buf: SegmentBuffer, level: Int, out: MutableList<Line>) {
        val n = buf.n
        // Each edge crossing is shared by at most two segments (the two cells around the edge).
        val first = HashMap<Int, Int>(n * 2)
        val second = HashMap<Int, Int>(n / 2)
        fun link(edge: Int, s: Int) {
            if (edge in first) second[edge] = s else first[edge] = s
        }
        for (s in 0 until n) {
            link(buf.ea[s], s); link(buf.eb[s], s)
        }
        fun other(edge: Int, s: Int): Int {
            val a = first[edge] ?: return -1
            if (a != s) return a
            return second[edge] ?: -1
        }

        val used = BooleanArray(n)
        val forward = FloatList()
        val backward = FloatList()
        for (start in 0 until n) {
            if (used[start]) continue
            used[start] = true
            forward.clear(); backward.clear()
            forward.add(buf.pts[4 * start], buf.pts[4 * start + 1])
            forward.add(buf.pts[4 * start + 2], buf.pts[4 * start + 3])

            // Walk forward from the B end.
            var edge = buf.eb[start]
            var s = start
            var closed = false
            while (true) {
                val next = other(edge, s)
                if (next < 0) break
                if (next == start) { closed = true; break }
                if (used[next]) break
                used[next] = true
                val (nextEdge, x, y) = farEnd(buf, next, edge)
                forward.add(x, y)
                edge = nextEdge
                s = next
            }
            // Walk backward from the A end (open lines only).
            if (!closed) {
                edge = buf.ea[start]
                s = start
                while (true) {
                    val next = other(edge, s)
                    if (next < 0 || used[next]) break
                    used[next] = true
                    val (nextEdge, x, y) = farEnd(buf, next, edge)
                    backward.add(x, y)
                    edge = nextEdge
                    s = next
                }
            }
            val xy = FloatArray(backward.size + forward.size + if (closed) 2 else 0)
            var k = 0
            var b = backward.size - 2
            while (b >= 0) { xy[k++] = backward[b]; xy[k++] = backward[b + 1]; b -= 2 }
            for (f in 0 until forward.size) xy[k++] = forward[f]
            if (closed) { xy[k++] = forward[0]; xy[k] = forward[1] }
            if (xy.size >= 4) out += Line(level, xy, closed)
        }
    }

    private data class End(val edge: Int, val x: Float, val y: Float)

    /** The end of segment [s] that is not on [viaEdge]. */
    private fun farEnd(buf: SegmentBuffer, s: Int, viaEdge: Int): End =
        if (buf.ea[s] == viaEdge) End(buf.eb[s], buf.pts[4 * s + 2], buf.pts[4 * s + 3])
        else End(buf.ea[s], buf.pts[4 * s], buf.pts[4 * s + 1])

    private class FloatList {
        private var a = FloatArray(64)
        var size = 0
            private set

        fun add(x: Float, y: Float) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x; a[size++] = y
        }

        operator fun get(i: Int) = a[i]
        fun clear() { size = 0 }
    }

    /** Contour spacing (minor, major) in metres for a zoom level, similar to printed topo maps. */
    fun intervalsFor(zoom: Int): Pair<Int, Int> = when {
        zoom <= 11 -> 100 to 500
        zoom == 12 -> 50 to 250
        zoom <= 14 -> 20 to 100
        else -> 10 to 50
    }

    @Suppress("unused")
    internal fun levelsBetween(lo: Float, hi: Float, interval: Int): IntRange =
        (ceil(lo / interval) * interval).toInt()..(floor(hi / interval) * interval).toInt()
}
