package app.maparound.tools

import app.maparound.shared.util.localDecimalSeparator
import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import kotlin.test.Test

class MapGridTest {
    /** Around Jerusalem, about 10 km across. */
    private val view = MapGrid.View(31.73, 35.18, 31.82, 35.29)

    /** What a grid writes: its lines' values and its cells' codes. */
    private fun texts(g: MapGrid.Grid) = g.lines.mapNotNull { it.label } + g.labels.map { it.text }

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
        assertTrue("${tiny.lines.size}", tiny.lines.size <= 164)
    }

    @Test
    fun aGridInItsOwnUnits() {
        // UTM 36R at 2 km: eastings like 710, 712 (km), northings 3512…
        val utm = MapGrid.make(CoordFormat.UTM, view, metresPerDp = 15.0)
        assertTrue(texts(utm).joinToString(), texts(utm).any { it == "712" } && texts(utm).any { it == "3514" })
        // The Israeli grid: 222 (km) and so on.
        val itm = MapGrid.make(CoordFormat.ITM, view, metresPerDp = 15.0)
        assertTrue(texts(itm).joinToString(), texts(itm).any { it == "222" })
        // Another country's grid where it doesn't reach: degrees instead.
        val osgb = MapGrid.make(CoordFormat.OSGB, view, metresPerDp = 15.0)
        assertTrue(texts(osgb).joinToString(), texts(osgb).isNotEmpty() && texts(osgb).all { it.endsWith("°") })
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
        assertTrue(texts(dd).joinToString(), texts(dd).any { it == "35.20°" } && texts(dd).any { it == "31.75°" })
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

    @Test
    fun aGridStaysWhileTheMapMovesALittle() {
        val mpd = 15.0
        val g = MapGrid.make(CoordFormat.UTM, view, mpd)
        // A quarter of the view east: the same grid still serves (no new one, nothing jumps).
        val w = view.east - view.west
        val east = MapGrid.View(view.south, view.west + w / 4, view.north, view.east + w / 4)
        assertTrue(MapGrid.fits(g, CoordFormat.UTM, east, mpd))
        // Twice the view away: a new one, its lines at the same round values where they meet.
        val far = MapGrid.View(view.south, view.west + 2 * w, view.north, view.east + 2 * w)
        assertTrue(!MapGrid.fits(g, CoordFormat.UTM, far, mpd))
        val g2 = MapGrid.make(CoordFormat.UTM, far, mpd)
        val shared = texts(g).toSet() intersect texts(g2).toSet()
        assertTrue(shared.joinToString(), shared.isNotEmpty())
        // Zoomed in past a spacing: a new one; a little within the same spacing: the same.
        assertTrue(!MapGrid.fits(g, CoordFormat.UTM, view, mpd / 3))
        assertTrue(MapGrid.fits(g, CoordFormat.UTM, view, mpd * 1.02))
        // Another format: a new one.
        assertTrue(!MapGrid.fits(g, CoordFormat.DD, view, mpd))
    }

    @Test
    fun eachUtmZoneToItsEdge() {
        // Across zones 32 and 33 (12°E) at 46°N, 2 km lines: both zones' grids, each in its own zone.
        val v = MapGrid.View(45.9, 11.85, 46.1, 12.15)
        val g = MapGrid.make(CoordFormat.UTM, v, 15.0, app.maparound.routing.GeoPoint(46.0, 11.95))
        val zones = g.lines.map { l -> l.points.mapNotNull { Utm.zoneOf(it.copy(lon = it.lon.coerceIn(v.west, v.east))) }.toSet() }
        assertTrue(zones.joinToString(), zones.any { it == setOf(32) } && zones.any { it == setOf(33) })
        // Zone 32's eastings near its east edge (~730 km), zone 33's near its west edge (~268 km).
        val t = texts(g).mapNotNull { it.toIntOrNull() }
        assertTrue(t.joinToString(), t.any { it in 725..740 } && t.any { it in 260..275 })
        // Zoomed out over Europe: the zones and bands themselves.
        val europe = MapGrid.make(CoordFormat.UTM, MapGrid.View(36.0, -10.0, 60.0, 30.0), 4_000.0)
        assertTrue(europe.labels.joinToString { it.text }, europe.labels.any { it.text == "32T" } && europe.labels.any { it.text == "35S" })
    }
}

