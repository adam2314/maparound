package app.maparound.tools

import app.maparound.shared.util.localDecimalSeparator
import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import kotlin.test.Test

class MapGridTest {
    /** Around Jerusalem, about 10 km across. */
    private val view = MapGrid.View(31.73, 35.18, 31.82, 35.29)

    @Test
    fun theSpacingFollowsTheZoom() {
        assertEquals(1_000.0, MapGrid.metricStep(900.0))
        assertEquals(2_000.0, MapGrid.metricStep(1_045.0))
        assertEquals(10.0, MapGrid.metricStep(3.0))
        // Zoomed in, closer lines; zoomed out, fewer.
        val near = MapGrid.make(CoordFormat.UTM, view, metresPerDp = 2.0)
        val far = MapGrid.make(CoordFormat.UTM, view, metresPerDp = 20.0)
        assertTrue("${near.lines.size} > ${far.lines.size}", near.lines.size > far.lines.size)
        // Never a crowd of lines.
        val tiny = MapGrid.make(CoordFormat.UTM, MapGrid.View(20.0, 20.0, 50.0, 50.0), metresPerDp = 0.5)
        assertTrue("${tiny.lines.size}", tiny.lines.size <= 82)
    }

    @Test
    fun aGridInItsOwnUnits() {
        // UTM 36R at 2 km: eastings like 710, 712 (km), northings 3512…
        val utm = MapGrid.make(CoordFormat.UTM, view, metresPerDp = 15.0)
        assertTrue(utm.labels.joinToString { it.text }, utm.labels.any { it.text == "712" } && utm.labels.any { it.text == "3514" })
        // The Israeli grid: 222 (km) and so on.
        val itm = MapGrid.make(CoordFormat.ITM, view, metresPerDp = 15.0)
        assertTrue(itm.labels.joinToString { it.text }, itm.labels.any { it.text == "222" })
        // Another country's grid where it doesn't reach: degrees instead.
        val osgb = MapGrid.make(CoordFormat.OSGB, view, metresPerDp = 15.0)
        assertTrue(osgb.labels.joinToString { it.text }, osgb.labels.all { it.text.endsWith("°") })
        val d = localDecimalSeparator()
        assertEquals("711${d}5", MapGrid.km(711_500.0, 500.0))
    }

    @Test
    fun degreesAsTheFormatWritesThem() {
        assertEquals("35°30′E", MapGrid.angle(35.5, 1.0 / 60, CoordFormat.DMS, 'E', 'W'))
        assertEquals("35°00′30″E", MapGrid.angle(35.0 + 30.0 / 3600, 1.0 / 3600, CoordFormat.DMS, 'E', 'W'))
        assertEquals("31°N", MapGrid.angle(31.0, 1.0, CoordFormat.DDM, 'N', 'S'))
        assertEquals("31°27.5′N", MapGrid.angle(31.0 + 27.5 / 60, 0.5 / 60, CoordFormat.DDM, 'N', 'S'))
        assertEquals("35.25°", MapGrid.angle(35.25, 0.05, CoordFormat.DD, 'E', 'W'))
        // About 1 km apart: 0.01°.
        assertEquals(0.01, MapGrid.degreeStep(CoordFormat.DD, 1_000.0))
        val dd = MapGrid.make(CoordFormat.DD, view, metresPerDp = 9.0)
        assertTrue(dd.labels.joinToString { it.text }, dd.labels.any { it.text == "35.20°" } && dd.labels.any { it.text == "31.75°" })
    }

    @Test
    fun codesInTheirCells() {
        // Maidenhead squares (2° × 1°) over Israel, each with its code.
        val m = MapGrid.make(CoordFormat.MAIDENHEAD, MapGrid.View(30.5, 34.0, 33.5, 36.5), metresPerDp = 1_000.0)
        assertTrue("M " + m.labels.joinToString { it.text }, m.labels.any { it.text == "KM71" })
        // Geohash cells about 20 km tall: four characters.
        val g = MapGrid.make(CoordFormat.GEOHASH, view, metresPerDp = 45.0)
        assertTrue("G " + g.labels.joinToString { it.text }, g.labels.isNotEmpty() && g.labels.all { it.text.length == 4 })
        // Plus Codes at 0.05° (about 5 km): six characters.
        val p = MapGrid.make(CoordFormat.PLUS, view, metresPerDp = 40.0)
        assertTrue("P " + p.labels.joinToString { it.text } + " lines " + p.lines.size, p.labels.isNotEmpty() && p.labels.all { it.text.length == 6 })
    }
}
