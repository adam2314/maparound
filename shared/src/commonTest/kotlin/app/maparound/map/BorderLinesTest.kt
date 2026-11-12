package app.maparound.map

import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.Test

class BorderLinesTest {
    /** Writes the borders.bin format (tools/borders/make_borders.py). */
    private fun encode(vararg lines: Pair<Boolean, List<Pair<Double, Double>>>): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(v: Long) {
            var n = v
            while (true) {
                val b = (n and 0x7F).toInt()
                n = n ushr 7
                if (n != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); return }
            }
        }
        fun zigzag(v: Int) = varint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL)
        "MABL".encodeToByteArray().forEach { out += it }
        out += 1
        varint(lines.size.toLong())
        for ((contested, pts) in lines) {
            out += if (contested) 1 else 0
            varint(pts.size.toLong())
            var plat = 0
            var plon = 0
            for ((lat, lon) in pts) {
                val a = kotlin.math.round(lat * 1e5).toInt()
                val b = kotlin.math.round(lon * 1e5).toInt()
                zigzag(a - plat); zigzag(b - plon)
                plat = a; plon = b
            }
        }
        return out.toByteArray()
    }

    @Test
    fun decodesAndCutsIntoPieces() {
        // 150 points along a parallel, and a short contested line in the southern hemisphere.
        val long = (0 until 150).map { 47.0 to 5.0 + it * 0.01 }
        val short = listOf(-33.5 to -70.25, -33.4 to -70.2)
        val lines = BorderLines.decode(encode(false to long, true to short))
        assertNotNull(lines)
        lines!!
        assertEquals(4, lines.pieceCount)   // 64 + 64 + 24 points (sharing the points where they meet), and the short line
        assertEquals(64 + 64 + 24 + 2, lines.pointCount)
        val found = ArrayList<BorderLines.Piece>()
        lines.query(GeoBox(46_000_000, 4_000_000, 48_000_000, 7_000_000)) { found += it }
        assertEquals(3, found.size)
        assertTrue(found.zipWithNext().all { (a, b) ->
            a.coords[a.coords.size - 2] == b.coords[0] && a.coords[a.coords.size - 1] == b.coords[1]
        })
        assertEquals(47_000_000, found[0].coords[0])
        assertEquals(5_000_000, found[0].coords[1])
        assertFalse(found[0].contested)

        val south = ArrayList<BorderLines.Piece>()
        lines.query(GeoBox(-34_000_000, -71_000_000, -33_000_000, -70_000_000)) { south += it }
        assertEquals(1, south.size)
        assertTrue(south[0].contested)
        assertEquals(-70_250_000, south[0].coords[1])
    }

    @Test
    fun thinsForOverviewZooms() {
        // A zigzag 1 km wide: invisible at zoom 3, kept at full detail.
        val zigzag = (0 until 60).map { (if (it % 2 == 0) 10.0 else 10.01) to 20.0 + it * 0.05 }
        val piece = BorderLines.decode(encode(false to zigzag))!!.let { l ->
            var p: BorderLines.Piece? = null
            l.query(GeoBox(-90_000_000, -180_000_000, 90_000_000, 180_000_000)) { p = it }
            p!!
        }
        assertEquals(2, piece.coordsAt(3).size / 2)
        assertEquals(60, piece.coordsAt(FULL_DETAIL_ZOOM).size / 2)
        assertTrue(piece.coordsAt(9).size / 2 > 2)
        // Ends always stay.
        val z3 = piece.coordsAt(3)
        assertEquals(piece.coords[0], z3[0])
        assertEquals(piece.coords[piece.coords.size - 1], z3[z3.size - 1])
    }

    @Test
    fun rejectsOtherFiles() {
        assertNull(BorderLines.decode(ByteArray(0)))
        assertNull(BorderLines.decode("MABL\u0002".encodeToByteArray()))
        assertNull(BorderLines.decode("<?xml version".encodeToByteArray()))
    }
}
