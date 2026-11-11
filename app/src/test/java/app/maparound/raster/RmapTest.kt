package app.maparound.raster

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.tools.Itm
import app.maparound.tools.Utm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RmapTest {
    /** Writes a small RMAP the way TwoNav lays it out (little-endian, 64-bit offsets, negative height). */
    private fun rmap(width: Int, height: Int, tile: Int, levels: List<Pair<Int, Int>>, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = 71 + 8 * levels.size
        // Lay out: header, level headers (+ tables), tiles, text.
        val levelTables = levels.map { (w, h) -> Triple(w, h, ((w + tile - 1) / tile) to ((h + tile - 1) / tile)) }
        var pos = headerSize.toLong()
        val levelOffsets = levelTables.map { (_, _, t) -> pos.also { pos += 16 + 8L * t.first * t.second } }
        val tileData = ArrayList<ByteArray>()
        val tileOffsets = levelTables.mapIndexed { li, (_, _, t) ->
            LongArray(t.first * t.second) { i ->
                val body = "tile-L$li-${i % t.first}-${i / t.first}".toByteArray()
                val rec = le(8 + body.size).putInt(7).putInt(body.size).put(body).array()
                tileData += rec
                pos.also { pos += rec.size }
            }
        }
        val textBytes = text.toByteArray()
        val textOffset = pos
        val h = le(headerSize)
        h.put("CompeGPSRasterImage".toByteArray()).putInt(10).putInt(7).putInt(0)
        h.putInt(width).putInt(-height).putInt(24).putInt(1).putInt(tile).putInt(tile)
        h.putLong(textOffset).putInt(0).putInt(levels.size)
        levelOffsets.forEach { h.putLong(it) }
        out.write(h.array())
        levelTables.forEachIndexed { li, (w, hh, t) ->
            val b = le(16 + 8 * t.first * t.second).putInt(w).putInt(-hh).putInt(t.first).putInt(t.second)
            tileOffsets[li].forEach { b.putLong(it) }
            out.write(b.array())
        }
        tileData.forEach { out.write(it) }
        out.write(le(8).putInt(1).putInt(textBytes.size).array())
        out.write(textBytes)
        return out.toByteArray()
    }

    private fun open(bytes: ByteArray) = RmapFile(
        { pos, len -> bytes.copyOfRange(pos.toInt(), minOf(bytes.size, pos.toInt() + len)) },
        bytes.size.toLong(),
    )

    private fun text(projection: String, datum: String, points: List<String>) =
        (listOf("CompeGPS MAP File", "<Header>", "Version=2", "Projection=$projection", "Coordinates=1", "Datum=$datum", "NumberOfCalibrationPoints=${points.size}", "</Header>") +
            points.mapIndexed { i, p -> "P$i=$p" }).joinToString("\r\n")

    @Test
    fun readsHeaderLevelsAndTiles() {
        // 1000×600 px at 256 px tiles, plus a half-size level.
        val utm = Utm.from(GeoPoint(31.5, 35.3))!!
        val e0 = utm.easting
        val n0 = utm.northing
        val pts = listOf("0,0,N,$e0,$n0", "1000,0,N,${e0 + 5000},$n0", "0,600,N,$e0,${n0 - 3000}")
        val f = open(rmap(1000, 600, 256, listOf(1000 to 600, 500 to 300), text("0,UTM,36,N,", "WGS 84", pts)))
        assertEquals(1000, f.width)
        assertEquals(600, f.height)
        assertEquals(256, f.tileWidth)
        assertEquals(2, f.levels.size)
        assertEquals(4, f.levels[0].xTiles)
        assertEquals(3, f.levels[0].yTiles)
        assertEquals(2.0, f.levels[1].factor, 1e-9)
        assertArrayEquals("tile-L0-2-1".toByteArray(), f.tileBytes(0, 2, 1))
        assertArrayEquals("tile-L1-1-0".toByteArray(), f.tileBytes(1, 1, 0))
        assertNull(f.tileBytes(0, 4, 0))
        // Level choice: zoomed out by 2.3× -> the half-size level; 1× -> full size.
        assertEquals(1, f.bestLevel(2.3))
        assertEquals(0, f.bestLevel(1.0))
        // 5 m per pixel: pixel (500, 300) is the map centre.
        val mid = f.calibration.pixelToLatLon(500.0, 300.0)
        val expected = Utm(36, 'R', e0 + 2500, n0 - 1500).toPoint()
        assertTrue(Geo.distance(mid, expected) < 0.01)
        val px = f.calibration.latLonToPixel(expected.lat, expected.lon)
        assertEquals(500.0, px[0], 1e-3)
        assertEquals(300.0, px[1], 1e-3)
    }

    @Test
    fun israeliGridByNameAndByRange() {
        // A 1:50,000 sheet-like map in ITM, calibrated at points that aren't the corners.
        val pts = listOf("100,100,N,200000,640000", "2900,150,N,214000,639750", "150,3900,N,200250,621000", "2800,3800,N,213500,621500")
        for (proj in listOf("112,Israeli TM Grid,", "999,Some custom grid,")) {
            val c = Calibration.parse(text(proj, "Israeli", pts).replace("\r\n", "\n")) // LF-only text works too
            assertTrue(c.projection.name, c.projection.name.contains("ITM"))
            // A point given in ITM lands where the calibration says.
            val p = Itm.toPoint(214000.0, 639750.0)
            val px = c.latLonToPixel(p.lat, p.lon)
            assertEquals(2900.0, px[0], 0.5)
            assertEquals(150.0, px[1], 0.5)
        }
    }

    @Test
    fun mercatorWithLonLatCalibrationAndErrors() {
        val pts = listOf("0,0,A,35.0,32.0", "2000,0,A,35.2,32.0", "0,2000,A,35.0,31.8", "2000,2000,A,35.2,31.8")
        val c = Calibration.parse(text("2,Mercator,", "WGS 84", pts))
        val mid = c.pixelToLatLon(1000.0, 1000.0)
        assertEquals(35.1, mid.lon, 1e-6)
        assertEquals(31.9, mid.lat, 0.001) // Mercator: the middle row isn't exactly the middle latitude
        val corner = c.latLonToPixel(31.8, 35.2)
        assertEquals(2000.0, corner[0], 0.01)
        assertEquals(2000.0, corner[1], 0.01)
        // Unknown projection with lon/lat corners: shown approximately rather than refused.
        assertTrue(Calibration.parse(text("55,Strange,", "WGS 84", pts)).projection.name.startsWith("Approximate"))
        for (bad in listOf("Not a map at all, just some text bytes padding padding padding padding padding".toByteArray())) {
            val r = runCatching { open(bad) }
            assertTrue(r.exceptionOrNull() is RmapException)
        }
    }
}
