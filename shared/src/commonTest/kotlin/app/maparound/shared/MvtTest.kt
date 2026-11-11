package app.maparound.shared

import app.maparound.shared.mvt.MvtTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Decodes what we wrote with a tiny protobuf reader and checks it against the MVT spec. */
class MvtTest {
    private class Msg(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun hasMore() = p < end
        fun varint(): Long {
            var v = 0L
            var s = 0
            while (true) {
                val x = b[p++].toLong() and 0xFF
                v = v or ((x and 0x7F) shl s)
                if (x and 0x80 == 0L) return v
                s += 7
            }
        }
        /** (field, wire type, value or sub-message bytes) */
        fun next(): Triple<Int, Int, Any> {
            val key = varint()
            val field = (key shr 3).toInt()
            return when (val wt = (key and 7).toInt()) {
                0 -> Triple(field, wt, varint())
                1 -> Triple(field, wt, (0 until 8).fold(0L) { acc, i -> acc or ((b[p + i].toLong() and 0xFF) shl (8 * i)) }.also { p += 8 })
                2 -> { val n = varint().toInt(); Triple(field, wt, b.copyOfRange(p, p + n).also { p += n }) }
                else -> error("wire type $wt")
            }
        }
    }

    private fun packed(b: ByteArray): List<Long> { val m = Msg(b); val out = ArrayList<Long>(); while (m.hasMore()) out += m.varint(); return out }
    private fun unzig(v: Long) = (v ushr 1) xor -(v and 1)

    @Test
    fun encodesLayersFeaturesAndWinding() {
        val t = MvtTile(14, 9786, 6463) // somewhere in Liechtenstein
        val n = 47.0 + 0.155
        val w = 9.0 + 0.51
        t.layer("poi").point(n - 0.001, w + 0.001, mapOf("name" to "Hut", "ele" to 1200L))
        t.layer("line").line(doubleArrayOf(n - 0.002, w + 0.002, n - 0.004, w + 0.004), mapOf("highway" to "path"))
        // Outer ring given counter-clockwise on screen: must come out clockwise (positive area).
        val ring = doubleArrayOf(n - 0.005, w + 0.005, n - 0.008, w + 0.005, n - 0.008, w + 0.008, n - 0.005, w + 0.008, n - 0.005, w + 0.005)
        t.layer("area").polygon(listOf(ring), mapOf("building" to "yes", "name" to "Hut"))
        val bytes = t.encode()

        val layers = HashMap<String, List<Pair<Int, List<Long>>>>()
        val tile = Msg(bytes)
        while (tile.hasMore()) {
            val (field, _, v) = tile.next()
            assertEquals(3, field)
            val layer = Msg(v as ByteArray)
            var name = ""
            var extent = 0L
            var version = 0L
            val feats = ArrayList<Pair<Int, List<Long>>>()
            val keys = ArrayList<String>()
            while (layer.hasMore()) {
                val (f, _, lv) = layer.next()
                when (f) {
                    1 -> name = (lv as ByteArray).decodeToString()
                    2 -> {
                        val fm = Msg(lv as ByteArray)
                        var type = 0
                        var geom = emptyList<Long>()
                        while (fm.hasMore()) {
                            val (ff, _, fv) = fm.next()
                            if (ff == 3) type = (fv as Long).toInt()
                            if (ff == 4) geom = packed(fv as ByteArray)
                        }
                        feats += type to geom
                    }
                    3 -> keys += (lv as ByteArray).decodeToString()
                    5 -> extent = lv as Long
                    15 -> version = lv as Long
                }
            }
            assertEquals(2L, version)
            assertEquals(4096L, extent)
            assertTrue(keys.toSet().size == keys.size, "keys are unique")
            layers[name] = feats
        }
        assertEquals(setOf("poi", "line", "area"), layers.keys)
        assertEquals(MvtTile.TYPE_POINT, layers["poi"]!![0].first)
        assertEquals(listOf(1L shl 3 or 1), layers["poi"]!![0].second.take(1)) // MoveTo(1)
        val line = layers["line"]!![0]
        assertEquals(MvtTile.TYPE_LINE, line.first)
        assertEquals(2L or (1L shl 3), line.second[3]) // LineTo(1)

        // Polygon: MoveTo(1) x y LineTo(3) … ClosePath(1); outer ring area > 0 in tile coordinates.
        val poly = layers["area"]!![0].second
        assertEquals(1L or (1L shl 3), poly[0])
        assertEquals(2L or (3L shl 3), poly[3])
        assertEquals(7L or (1L shl 3), poly.last())
        val pts = ArrayList<Pair<Long, Long>>()
        var x = 0L
        var y = 0L
        x += unzig(poly[1]); y += unzig(poly[2]); pts += x to y
        for (i in 0 until 3) { x += unzig(poly[4 + 2 * i]); y += unzig(poly[5 + 2 * i]); pts += x to y }
        var area = 0L
        for (i in pts.indices) { val j = (i + 1) % pts.size; area += pts[i].first * pts[j].second - pts[j].first * pts[i].second }
        assertTrue(area > 0, "outer ring is clockwise on screen: $area")
    }
}
