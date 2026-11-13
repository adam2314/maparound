package app.maparound.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The bundled region outlines (assets/regions.bin, tools/regions/make_regions.py) at known places. */
class MapRegionsAssetTest {
    private val regions = MapRegions.decode(File("src/main/assets/regions.bin").readBytes())!!

    private fun at(lat: Double, lon: Double) = regions.at(lat, lon).map { it.path }

    @Test
    fun theRightMapForAPlace() {
        assertTrue(regions.size > 400)
        assertEquals("europe/liechtenstein", at(47.14, 9.52).first())
        // Munich: Bavaria, then Germany as a whole.
        assertEquals(listOf("europe/germany/bayern", "europe/germany"), at(48.14, 11.58).take(2))
        assertEquals("asia/israel-and-palestine", at(31.77, 35.21).first())
        assertEquals("north-america/us/california", at(37.77, -122.42).first())
        // Mid-Atlantic: none.
        assertTrue(at(30.0, -40.0).isEmpty())
    }
}
