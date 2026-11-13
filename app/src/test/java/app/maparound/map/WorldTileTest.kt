package app.maparound.map

import app.maparound.three.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled world (assets/world.bin, assets/borders.bin), drawn per tile ([WorldTile]). */
class WorldTileTest {
    private class Recorder : WorldTile.Sink {
        val seas = ArrayList<GeoBox>()
        val land = ArrayList<List<IntArray>>()
        val lakes = ArrayList<List<IntArray>>()
        val rivers = ArrayList<IntArray>()
        val borders = ArrayList<Pair<Boolean, IntArray>>()
        val labels = ArrayList<WorldData.Label>()
        override fun sea(box: GeoBox) { seas += box }
        override fun area(lake: Boolean, outer: List<IntArray>, holes: List<IntArray>) { (if (lake) lakes else land) += outer + holes }
        override fun river(name: String, line: IntArray) { rivers += line }
        override fun border(contested: Boolean, line: IntArray) { borders += contested to line }
        override fun label(label: WorldData.Label) { labels += label }
        fun points() = (land + lakes).sumOf { r -> r.sumOf { it.size / 2 } } + rivers.sumOf { it.size / 2 } + borders.sumOf { it.second.size / 2 }
    }

    /** Andorra's map, as from download.mapsforge.org (microdegrees). */
    private val andorra = GeoBox(42_327_600, 1_312_368, 42_757_170, 1_887_481)

    private fun box(t: TileId) = GeoBox((t.south * 1e6).toInt(), (t.west * 1e6).toInt(), (t.north * 1e6).toInt(), (t.east * 1e6).toInt())
    private fun tile(lat: Double, lon: Double, z: Int) = TileId(z, TileId.xOf(lon, z), TileId.yOf(lat, z))

    private fun draw(lat: Double, lon: Double, z: Int, holes: List<GeoBox> = emptyList()) = Recorder().also { r ->
        val t = tile(lat, lon, z)
        WORLD.draw(box(t), z, holes.filter { it.intersects(box(t)) }, r)
    }

    @Test
    fun readsTheBundledFiles() {
        assertNotNull(WORLD_DATA)
        assertNotNull(BORDERS)
        assertTrue("labels ${WORLD_DATA!!.labelCount}", WORLD_DATA.labelCount > 8_000)
        assertTrue("border points ${BORDERS!!.pointCount}", BORDERS.pointCount in 60_000..90_000)
    }

    @Test
    fun overviewTilesHaveSeaLandAndNames() {
        val r = draw(48.0, 10.0, 3)
        assertEquals(1, r.seas.size)
        assertTrue(r.land.isNotEmpty() && r.lakes.isNotEmpty() && r.rivers.isNotEmpty() && r.borders.isNotEmpty())
        val names = r.labels.map { it.name }
        assertTrue(names.toString(), "Germany" in names && "Paris" in names)
        // Thinned for the zoom: the whole tile's coasts in a few thousand points.
        assertTrue("${r.points()} points", r.points() < 30_000)
    }

    @Test
    fun coastsAreDetailedWhenZoomedIn() {
        val r = draw(35.9, 14.4, 11)
        assertTrue(r.land.isNotEmpty())
        assertTrue("${r.points()} points", r.points() >= 30)
        assertTrue(draw(35.9, 14.51, 11).labels.any { it.name == "Valletta" })
    }

    @Test
    fun inlandTilesAreAllLand() {
        // Central Asia at zoom 12: one piece of land, the tile itself (with the margin).
        val r = draw(48.0, 70.0, 12)
        assertEquals(1, r.land.size)
        assertTrue(r.land[0].sumOf { it.size / 2 } <= 4)
    }

    @Test
    fun countryMapsKeepTheirArea() {
        // A tile inside Andorra's map: nothing from the world.
        val inside = draw(42.5, 1.55, 12, listOf(andorra))
        assertTrue(inside.seas.isEmpty() && inside.land.isEmpty() && inside.borders.isEmpty() && inside.labels.isEmpty())
        // On its edge: lines and names stop exactly at it; sea and land reach a couple of pixels in.
        val r = draw(42.3276, 1.5, 9, listOf(andorra))
        assertTrue(r.borders.isNotEmpty() || r.land.isNotEmpty())
        fun depth(lat: Int, lon: Int) = if (!andorra.contains(lat, lon)) 0 else minOf(lat - andorra.minLat, andorra.maxLat - lat, lon - andorra.minLon, andorra.maxLon - lon)
        val seam = (3 * 360.0 / (256 shl 9) * 1e6).toInt()
        for (line in r.rivers + r.borders.map { it.second }) for (k in line.indices step 2) assertTrue(depth(line[k], line[k + 1]) <= 1)
        for (rings in r.land) for (ring in rings) for (k in ring.indices step 2) assertTrue(depth(ring[k], ring[k + 1]) <= seam)
        assertTrue(r.labels.none { andorra.contains(it.lat, it.lon) })
    }

    @Test
    fun bordersAreJoinedAndContestedOnesMarked() {
        assertTrue(draw(51.0, 60.0, 3).borders.size in 1..40)
        assertTrue(draw(34.5, 74.5, 6).borders.any { it.first })
    }

    @Test
    fun bordersStayInAMapWithoutItsOwn() {
        // Israel and Palestine's map: its border lines don't say they're countries' (admin_level=2),
        // so the world's stay inside its region, the West Bank's and Gaza's too, while close enough.
        val region = REGIONS!!.byFileName("asia__israel-and-palestine.map")!!
        val cover = Coverage(region.box, region)
        fun bordersIn(z: Int, own: List<IntArray>?, lat: Double = 31.9, lon: Double = 35.1) = Recorder().also { r ->
            val t = tile(lat, lon, z)
            WORLD.draw(box(t), z, listOf(region.box), r, covers = listOf(cover), ownBorders = own)
        }.borders.filter { (_, l) -> (0 until l.size / 2).any { cover.contains(l[2 * it], l[2 * it + 1]) } }
        assertTrue("the map's own are drawn, the world's go", bordersIn(9, null).isEmpty())
        val kept = bordersIn(9, emptyList())
        assertTrue("${kept.size} borders", kept.isNotEmpty())
        assertTrue("the West Bank's are contested", kept.any { it.first })
        // Close in, on one of them: too coarse to show.
        val on = kept.first { it.first }.second.let { l -> l[l.size / 2 / 2 * 2] / 1e6 to l[l.size / 2 / 2 * 2 + 1] / 1e6 }
        assertTrue("there at zoom 13", bordersIn(13, emptyList(), on.first, on.second).isNotEmpty())
        assertTrue("too coarse at 14", bordersIn(14, emptyList(), on.first, on.second).isEmpty())
        // Where the map has its own, the world's go there.
        val own = kept.map { it.second }
        assertTrue(bordersIn(9, own).isEmpty())
    }

    @Test
    fun fastEnoughForTheRenderer() {
        // A screenful of zoom-8 tiles over a long coast (Norway), after a first read.
        val tiles = (0 until 6).flatMap { dx -> (0 until 4).map { dy -> TileId(8, 132 + dx, 70 + dy) } }
        tiles.forEach { WORLD.draw(box(it), 8, emptyList(), Recorder()) }
        val t0 = System.nanoTime()
        tiles.forEach { WORLD.draw(box(it), 8, emptyList(), Recorder()) }
        val ms = (System.nanoTime() - t0) / 1e6 / tiles.size
        assertTrue("$ms ms per tile", ms < 20)
    }

    companion object {
        private val WORLD_DATA = WorldData.decode(File("src/main/assets/world.bin").readBytes())
        private val BORDERS = BorderLines.decode(File("src/main/assets/borders.bin").readBytes())
        private val WORLD = WorldTile({ WORLD_DATA }, { BORDERS })
        private val REGIONS = MapRegions.decode(File("src/main/assets/regions.bin").readBytes())
    }
}
