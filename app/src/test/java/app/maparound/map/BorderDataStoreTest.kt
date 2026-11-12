package app.maparound.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import java.io.File

/** The bundled borders (assets/borders.bin) as the map renderer asks for them. */
class BorderDataStoreTest {
    private val lines = BorderLines.decode(File("src/main/assets/borders.bin").readBytes())

    /** Andorra's map, as from download.mapsforge.org. */
    private val andorra = BoundingBox(42.3276, 1.312368, 42.75717, 1.887481)

    private fun tile(lat: Double, lon: Double, zoom: Int): Tile {
        val z = zoom.toByte()
        return Tile(MercatorProjection.longitudeToTileX(lon, z), MercatorProjection.latitudeToTileY(lat, z), z, 256)
    }

    private fun store(overviewMaxZoom: Int = OfflineMapData.WORLD_MAX_ZOOM, covered: List<BoundingBox> = listOf(andorra)) =
        BorderDataStore({ lines }, covered, overviewMaxZoom, andorra)

    @Test
    fun theWholeWorldIsThere() {
        assertNotNull(lines)
        assertTrue("points ${lines!!.pointCount}", lines.pointCount in 60_000..90_000)
        // One zoom-1 tile per quarter of the world; every inhabited one has borders.
        val ways = (0..1).flatMap { x -> (0..1).map { y -> store().readMapData(Tile(x, y, 1, 256)).ways.size } }
        assertTrue("$ways", ways.all { it > 0 })
    }

    @Test
    fun overviewZoomsDrawEverywhereWithFewPoints() {
        // The Pyrenees at zoom 5, Andorra included: France–Spain and Andorra's borders.
        val ways = store().readMapData(tile(42.6, 1.5, 5)).ways
        assertTrue(ways.isNotEmpty())
        val w = ways.first()
        assertEquals("administrative", w.tags.first { it.key == "boundary" }.value)
        assertEquals("2", w.tags.first { it.key == "admin_level" }.value)
        val points = ways.sumOf { it.latLongs.sumOf { s -> s.size } }
        assertTrue("$points points", points < 2_000)
    }

    @Test
    fun detailZoomsLeaveTheCountryMapsTheirOwnBorders() {
        // On the Andorra–Spain border at zoom 12: with Andorra's map installed, its own (exact)
        // border is drawn and none of these; without it, these.
        val onBorder = tile(42.44, 1.46, 12)
        assertTrue(store(covered = emptyList()).readMapData(onBorder).ways.isNotEmpty())
        assertTrue(store().readMapData(onBorder).ways.isEmpty())
        // The Spanish–French border far from it still comes.
        val away = store().readMapData(tile(43.0, -1.0, 9))
        assertTrue(away.ways.isNotEmpty())
        // Without a world map the country maps are drawn at every zoom, with borders from zoom 4:
        // from there, none of these in Andorra's map area. With a world map, up to zoom 7.
        fun pointsInAndorra(s: BorderDataStore) = s.readMapData(tile(42.5, 1.45, 6)).ways
            .sumOf { w -> w.latLongs.sumOf { seg -> seg.count { andorra.contains(it) } } }
        assertEquals(0, pointsInAndorra(store(BorderDataStore.MAP_BORDERS_FROM_ZOOM - 1)))
        assertTrue(pointsInAndorra(store()) > 0)
    }

    @Test
    fun contestedLinesAreMarked() {
        // The Line of Control in Kashmir.
        val ways = store().readMapData(tile(34.5, 74.5, 6)).ways
        assertTrue(ways.any { w -> w.tags.any { it.key == "disputed" && it.value == "yes" } })
    }

    @Test
    fun linesStayJoinedAcrossPieces() {
        // The Russia–Kazakhstan border is thousands of km long: at zoom 3 it comes as few ways.
        val ways = store().readMapData(tile(51.0, 60.0, 3)).ways
        val pieces = ArrayList<BorderLines.Piece>()
        val bb = tile(51.0, 60.0, 3).boundingBox
        lines!!.query((bb.minLatitude * 1e6).toInt(), (bb.minLongitude * 1e6).toInt(), (bb.maxLatitude * 1e6).toInt(), (bb.maxLongitude * 1e6).toInt()) { pieces += it }
        assertTrue("${ways.size} ways for ${pieces.size} pieces", ways.size * 3 < pieces.size)
    }
}
