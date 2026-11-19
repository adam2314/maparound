package app.maparound.three

import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import kotlin.math.floor
import kotlin.test.Test

/** The 3D view's relief from image tiles: their colours as heights, the sea, and heights between pixels. */
class ReliefTest {
    private fun terrarium(h: Double): Int {
        val v = h + 32_768
        val r = floor(v / 256).toInt()
        val g = floor(v).toInt() % 256
        val b = ((v - floor(v)) * 256).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mapbox(h: Double): Int {
        val v = ((h + 10_000) * 10).toInt()
        return (0xFF shl 24) or (v shr 16 and 0xFF shl 16) or (v shr 8 and 0xFF shl 8) or (v and 0xFF)
    }

    private val alps = ReliefTiles.tileAt(46.55, 7.98, 12)

    @Test
    fun coloursAreHeights() {
        val px = IntArray(256 * 256) { terrarium(1234.5) }
        assertEquals(1235, ReliefTiles.decode(px, mapbox = false, tile = alps)[777].toInt())
        val mb = IntArray(256 * 256) { mapbox(4478.0) } // the Matterhorn
        assertEquals(4478, ReliefTiles.decode(mb, mapbox = true, tile = alps)[0].toInt())
    }

    @Test
    fun theSeaIsItsSurfaceButLandBelowSeaLevelStays() {
        // The Mediterranean off Tel Aviv: its floor is drawn as the sea's surface.
        val sea = ReliefTiles.tileAt(32.1, 34.5, 12)
        assertEquals(0, ReliefTiles.decode(IntArray(256 * 256) { terrarium(-850.0) }, false, sea)[100].toInt())
        // The Dead Sea's shore is below sea level, and stays so.
        val deadSea = ReliefTiles.tileAt(31.5, 35.45, 12)
        assertEquals(-420, ReliefTiles.decode(IntArray(256 * 256) { terrarium(-420.0) }, false, deadSea)[100].toInt())
    }

    @Test
    fun heightsBetweenPixels() {
        // Each column one metre higher than the one west of it.
        val h = ReliefTiles.decode(IntArray(256 * 256) { i -> terrarium((i % 256).toDouble()) }, false, alps)
        val lonOfColumn = { c: Double -> (alps.x + c / 256) / (1 shl alps.z) * 360.0 - 180.0 }
        val lat = (alps.north + alps.south) / 2
        assertEquals(10f, ReliefTiles.sample(h, alps, lat, lonOfColumn(10.5)), 0.01f) // a pixel's middle
        assertEquals(10.5f, ReliefTiles.sample(h, alps, lat, lonOfColumn(11.0)), 0.01f) // between two
        assertEquals(0f, ReliefTiles.sample(h, alps, lat, alps.west), 0.01f) // the edge: the edge pixel's
        assertEquals(255f, ReliefTiles.sample(h, alps, lat, alps.east - 1e-9), 0.01f)
        assertTrue(alps.contains(ReliefTiles.tileAt(lat, lonOfColumn(100.0), 12)))
    }

    @Test
    fun zoomsAndAddresses() {
        assertEquals(9, ReliefTiles.zoomFor(TileId(9, 1, 1)))
        assertEquals(ReliefTiles.MAX_ZOOM, ReliefTiles.zoomFor(TileId(16, 1, 1)))
        assertEquals(
            "https://example.org/terrarium/12/2138/1447.png",
            ReliefTiles.url("https://example.org/terrarium/{z}/{x}/{y}.png", TileId(12, 2138, 1447)),
        )
    }
}
