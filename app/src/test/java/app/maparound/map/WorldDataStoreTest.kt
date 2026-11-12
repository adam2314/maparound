package app.maparound.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.Way
import java.io.File

/** The bundled world (assets/world.bin, assets/borders.bin) as the map renderer asks for it. */
class WorldDataStoreTest {
    private val world = WORLD
    private val borders = BORDERS

    /** Andorra's map, as from download.mapsforge.org. */
    private val andorra = BoundingBox(42.3276, 1.312368, 42.75717, 1.887481)

    private fun tile(lat: Double, lon: Double, zoom: Int): Tile {
        val z = zoom.toByte()
        return Tile(MercatorProjection.longitudeToTileX(lon, z), MercatorProjection.latitudeToTileY(lat, z), z, 256)
    }

    private fun store(covered: List<BoundingBox> = listOf(andorra)) =
        WorldDataStore({ world }, { borders }, covered, OfflineMapData.WORLD_MAX_ZOOM)

    private fun MapReadResult.with(key: String, value: String) = ways.filter { w -> w.tags.any { it.key == key && it.value == value } }
    private fun Way.points() = latLongs.sumOf { it.size }

    @Test
    fun readsTheBundledFiles() {
        assertNotNull(world)
        assertNotNull(borders)
        assertTrue("labels ${world!!.labelCount}", world.labelCount > 8_000)
        assertTrue("border points ${borders!!.pointCount}", borders.pointCount in 60_000..90_000)
    }

    @Test
    fun overviewTilesHaveSeaLandAndNames() {
        // Zoom 3 over Europe.
        val r = store().readMapData(tile(48.0, 10.0, 3))
        assertEquals(1, r.with("natural", "sea").size)
        assertTrue(r.with("natural", "nosea").isNotEmpty())
        assertTrue(r.with("natural", "water").isNotEmpty())
        assertTrue(r.with("waterway", "river").isNotEmpty())
        assertTrue(r.with("admin_level", "2").isNotEmpty())
        val names = r.pois.mapNotNull { p -> p.tags.firstOrNull { it.key == "name" }?.value }
        assertTrue(names.toString(), "Germany" in names && "Paris" in names)
        // Thinned for the zoom: the whole tile's coasts in a few thousand points.
        val points = r.ways.sumOf { it.points() }
        assertTrue("$points points", points < 30_000)
        // Every area is a closed ring (mapsforge draws only those as areas).
        for (w in r.with("natural", "nosea")) for (ring in w.latLongs) assertEquals(ring.first(), ring.last())
    }

    @Test
    fun coastsAreDetailedWhenZoomedIn() {
        // Malta at zoom 11, no map installed: the island, cut to the tile, with its outline.
        val r = store(emptyList()).readMapData(tile(35.9, 14.4, 11))
        val land = r.with("natural", "nosea")
        assertTrue(land.isNotEmpty())
        assertTrue("${land.sumOf { it.points() }} points", land.sumOf { it.points() } >= 30)
        // Valletta is in the next tile east.
        assertTrue(store(emptyList()).readMapData(tile(35.9, 14.51, 11)).pois.any { p -> p.tags.any { it.value == "Valletta" } })
    }

    @Test
    fun inlandTilesAreAllLand() {
        // Central Asia at zoom 12: the tile is land (inside the continent's ring), clipped to a box.
        val r = store(emptyList()).readMapData(tile(48.0, 70.0, 12))
        val land = r.with("natural", "nosea")
        assertEquals(1, land.size)
        assertTrue(land[0].points() <= 8)
    }

    @Test
    fun countryMapsKeepTheirAreaWhenZoomedIn() {
        // Inside Andorra's map at zoom 12: nothing from the world (the map draws it all).
        val inside = tile(42.5, 1.55, 12)
        assertFalse(store().supportsTile(inside))
        assertTrue(store().readMapData(inside).ways.isEmpty())
        // On its edge: the world draws outside the map's box. Lines and names stop exactly at it;
        // sea and land reach a couple of pixels in, under the map's own, so no seam shows.
        val edge = tile(42.3276, 1.5, 9)
        val r = store().readMapData(edge)
        assertTrue(r.ways.isNotEmpty())
        fun depth(p: org.mapsforge.core.model.LatLong) = if (!andorra.contains(p)) 0.0 else minOf(
            p.latitude - andorra.minLatitude, andorra.maxLatitude - p.latitude,
            p.longitude - andorra.minLongitude, andorra.maxLongitude - p.longitude,
        )
        val seam = 3 * 360.0 / (256 shl 9) // 3 px at zoom 9
        for (w in r.ways) {
            val isArea = w.tags.any { it.key == "natural" }
            for (ring in w.latLongs) for (p in ring) {
                assertTrue("$p is ${depth(p)}° inside Andorra's map (${w.tags})", depth(p) <= if (isArea) seam else 1e-6)
            }
        }
        assertTrue(r.pois.none { andorra.contains(it.position) })
        // At overview zooms the world draws everywhere, Andorra included.
        val overview = store().readMapData(tile(42.5, 1.55, 6))
        assertTrue(overview.with("admin_level", "2").any { w -> w.latLongs.any { ring -> ring.any { andorra.contains(it) } } })
    }

    @Test
    fun bordersAreJoinedAndContestedOnesMarked() {
        // The Russia–Kazakhstan border comes as few long ways, not one per piece.
        val ways = store().readMapData(tile(51.0, 60.0, 3)).with("admin_level", "2")
        assertTrue("${ways.size} border ways", ways.size in 1..40)
        // The Line of Control in Kashmir.
        assertTrue(store().readMapData(tile(34.5, 74.5, 6)).with("disputed", "yes").isNotEmpty())
    }

    @Test
    fun fastEnoughForTheRenderer() {
        // A screenful of zoom-8 tiles over a long coast (Norway), after a first read.
        val s = store(emptyList())
        val tiles = (0 until 6).flatMap { dx -> (0 until 4).map { dy -> Tile(132 + dx, 70 + dy, 8, 256) } }
        tiles.forEach { s.readMapData(it) }
        val t0 = System.nanoTime()
        tiles.forEach { s.readMapData(it) }
        val ms = (System.nanoTime() - t0) / 1e6 / tiles.size
        assertTrue("$ms ms per tile", ms < 20)
    }

    companion object {
        private val WORLD = WorldData.decode(File("src/main/assets/world.bin").readBytes())
        private val BORDERS = BorderLines.decode(File("src/main/assets/borders.bin").readBytes())
    }
}
