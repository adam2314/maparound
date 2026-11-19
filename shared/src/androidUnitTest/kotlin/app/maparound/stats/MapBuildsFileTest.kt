package app.maparound.stats

import app.maparound.map.MapRegions
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The map builds on the app's regions.bin (tools/regions/make_regions.py): which regions are drawn,
 * and that working it out stays quick (the web app does it when the layer is first on).
 */
class MapBuildsFileTest {
    private val regions by lazy { MapRegions.decode(File("../app/src/main/assets/regions.bin").readBytes())!! }

    @Test fun theFinestRegionsAreDrawn() {
        val start = System.nanoTime()
        val shown = MapBuilds.shown(regions.all).map { it.path }.toSet()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("${shown.size} of ${regions.size} regions drawn, in $ms ms")
        assertTrue(ms < 5_000, "$ms ms")
        // Countries and groups whose parts are maps of their own: shown through them.
        for (whole in listOf("europe/france", "europe/germany", "europe/alps", "europe/great-britain", "europe/united-kingdom/england", "north-america/us-west", "north-america/us-pacific", "asia/japan")) {
            assertFalse(whole in shown, whole)
        }
        for (part in listOf("europe/france/alsace", "europe/germany/bayern", "europe/united-kingdom/england/cheshire", "north-america/us/california")) {
            assertTrue(part in shown, part)
        }
        // A country with a small one in it is still drawn; so are the small ones.
        for (p in listOf("europe/switzerland", "europe/liechtenstein", "europe/italy/nord-ovest", "asia/israel-and-palestine", "australia-oceania/new-zealand")) {
            assertTrue(p in shown, p)
        }
    }
}
