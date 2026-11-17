package app.maparound.tools

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.math.abs
import kotlin.test.Test

class GridsTest {
    private fun near(expected: GeoPoint, actual: GeoPoint?, metres: Double) {
        assertTrue("$actual", actual != null && Geo.distance(expected, actual) <= metres)
    }

    private fun close(expected: Double, actual: Double, tolerance: Double) = assertTrue("$expected vs $actual", abs(expected - actual) <= tolerance)

    /** Written in [f] and read back: the same place, to [metres]. */
    private fun roundTrip(p: GeoPoint, f: CoordFormat, metres: Double) {
        val text = Coords.format(p, f)
        assertTrue("$f", text != null)
        near(p, Coords.parseAs(text!!, f)?.point, metres)
    }

    @Test
    fun geohash() {
        assertEquals("u4pruydqqvj", Geohash.encode(GeoPoint(57.64911, 10.40744), 11))
        near(GeoPoint(57.64911, 10.40744), Geohash.decode("u4pruydqqvj"), 1.0)
        roundTrip(GeoPoint(31.7767, 35.2345), CoordFormat.GEOHASH, 1.0)
    }

    @Test
    fun georef() {
        // Patuxent River (Wikipedia's example): GJPJ3417.
        assertTrue(Georef.encode(GeoPoint(38.286108, -76.4291704)).startsWith("GJPJ 34"))
        near(GeoPoint(38.2917, -76.425), Georef.decode("GJPJ3417"), 1_500.0)
        roundTrip(GeoPoint(31.7767, 35.2345), CoordFormat.GEOREF, 30.0)
    }

    @Test
    fun webMercatorAndEcef() {
        val (x, y) = WebMercator.from(GeoPoint(45.0, 10.0))!!
        close(1_113_194.91, x, 0.1)
        close(5_621_521.49, y, 0.1)
        roundTrip(GeoPoint(-33.8568, 151.2153), CoordFormat.WEB_MERCATOR, 1.0)
        val (ex, ey, ez) = Ecef.from(GeoPoint(0.0, 0.0))
        close(6_378_137.0, ex, 0.01)
        close(0.0, ey, 0.01)
        close(0.0, ez, 0.01)
        roundTrip(GeoPoint(31.7767, 35.2345), CoordFormat.ECEF, 1.0)
    }

    @Test
    fun britishGrid() {
        // Ben Nevis's summit: NN 16667 71262.
        near(GeoPoint(56.79685, -5.00360), Osgb.parse("NN 16667 71262"), 150.0)
        assertTrue(Osgb.format(GeoPoint(56.79685, -5.00360))!!.startsWith("NN 166"))
        roundTrip(GeoPoint(51.5007, -0.1246), CoordFormat.OSGB, 2.0)
        assertNull(Osgb.format(GeoPoint(31.7767, 35.2345)))
    }

    @Test
    fun nationalGrids() {
        // Their origins.
        val (ie, iN) = IrishTm.from(GeoPoint(53.5, -8.0))!!
        close(600_000.0, ie, 0.01)
        close(750_000.0, iN, 0.01)
        val (rx, ry) = Rd.from(GeoPoint(52.15517440, 5.38720621))!!
        close(155_000.0, rx, 0.01)
        close(463_000.0, ry, 0.01)
        near(GeoPoint(52.15517440, 5.38720621), Rd.toPoint(155_000.0, 463_000.0), 0.01)
        val (lx, ly) = Lambert93.from(GeoPoint(46.5, 3.0))!!
        close(700_000.0, lx, 0.01)
        close(6_600_000.0, ly, 0.01)
        // swisstopo's worked example: 46°2′38.87″N 8°43′49.79″E is 2 699 999.76 / 1 099 999.97.
        val (se, sn) = Lv95.from(GeoPoint(46 + 2 / 60.0 + 38.87 / 3600, 8 + 43 / 60.0 + 49.79 / 3600))!!
        close(2_699_999.76, se, 1.0)
        close(1_099_999.97, sn, 1.0)
        roundTrip(GeoPoint(53.3498, -6.2603), CoordFormat.IRISH_TM, 1.0)
        roundTrip(GeoPoint(52.3702, 4.8952), CoordFormat.RD, 2.0)
        roundTrip(GeoPoint(48.8530, 2.3499), CoordFormat.LAMBERT93, 1.0)
        roundTrip(GeoPoint(46.9480, 7.4474), CoordFormat.LV95, 2.0)
        // LV03 too, when chosen.
        near(Lv95.toPoint(2_600_000.0, 1_200_000.0), Coords.parseAs("600000 200000", CoordFormat.LV95)?.point, 0.01)
    }

    @Test
    fun readsWhatItCanTellApart() {
        assertEquals(CoordFormat.OSGB, Coords.parse("TQ 30080 80820")?.format)
        assertEquals(CoordFormat.LV95, Coords.parse("2'600'000 / 1'200'000")?.format)
        assertEquals(CoordFormat.LAMBERT93, Coords.parse("652381 6862047")?.format)
        assertEquals(CoordFormat.GEOHASH, Coords.parse("u4pruydqqvj")?.format)
        assertEquals(CoordFormat.GEOREF, Coords.parse("GJPJ3417")?.format)
        assertEquals(CoordFormat.ECEF, Coords.parse(Coords.format(GeoPoint(31.7767, 35.2345), CoordFormat.ECEF)!!)?.format)
        // Plain pairs stay the Israeli grid; named, or chosen, they're another.
        assertEquals(CoordFormat.ITM, Coords.parse("155000 463000")?.format)
        assertEquals(CoordFormat.RD, Coords.parse("RD 155000 463000")?.format)
        assertEquals(CoordFormat.RD, Coords.parseAs("155000 463000", CoordFormat.RD)?.format)
        assertEquals(CoordFormat.WEB_MERCATOR, Coords.parse("EPSG:3857 1113195 5621521")?.format)
        // Words and other codes aren't taken for grids.
        assertEquals(CoordFormat.MAIDENHEAD, Coords.parse("KM71")?.format)
        assertEquals(CoordFormat.MAIDENHEAD, Coords.parse("RD12")?.format)
        assertNull(Coords.parse("CAFE"))
        assertNull(Coords.parse("TQ"))
    }
}
