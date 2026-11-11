package app.maparound.tools

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reference values from pyproj 3.7 (EPSG), the mgrs library and Google's openlocationcode. */
class CoordsTest {
    private class Ref(val lat: Double, val lon: Double, val zone: Int, val e: Double, val n: Double, val mgrs: String, val olc: String, val itm: Pair<Double, Double>? = null)

    private val refs = mapOf(
        "jerusalem" to Ref(31.7767, 35.2345, 36, 711594.452, 3517858.497, "36RYA1159417858", "8G3QQ6GM+MR", 222303.615 to 631557.918),
        "telaviv" to Ref(32.0853, 34.7818, 36, 668156.435, 3551279.767, "36SXA6815651279", "8G4P3QPJ+4P", 179556.030 to 665855.389),
        "eilat" to Ref(29.5577, 34.9519, 36, 689104.356, 3271365.939, "36RXT8910471365", "7GXPHX52+3Q", 194982.116 to 385574.285),
        "hermon" to Ref(33.4161, 35.8572, 36, 765691.490, 3701067.520, "36SYC6569101067", "8G5QCV84+CV", 280172.020 to 813555.300),
        "sydney" to Ref(-33.8568, 151.2153, 56, 334900.570, 6252288.753, "56HLH3490052288", "4RRH46V8+74"),
        "bergen" to Ref(60.39, 5.32, 32, 297230.220, 6700510.175, "32VKN9723000510", "9FG798RC+22"),
        "svalbard" to Ref(78.22, 15.65, 33, 514813.527, 8683004.153, "33XWG1481383004", "CFCQ6MC2+22"),
        "newyork" to Ref(40.7128, -74.006, 18, 583959.372, 4507350.998, "18TWL8395907350", "87G7PX7V+4J"),
        "gabon" to Ref(0.5, 9.5, 32, 555636.088, 55267.156, "32NNF5563655267", "6FGFGG22+22"),
    )

    private fun near(expected: GeoPoint, actual: GeoPoint?, metres: Double, what: String) {
        assertNotNull("$what: not parsed", actual)
        val d = Geo.distance(expected, actual!!)
        assertTrue("$what: off by ${"%.3f".format(d)} m", d <= metres)
    }

    @Test
    fun utmMatchesProj() {
        for ((name, r) in refs) {
            val u = Utm.from(GeoPoint(r.lat, r.lon))!!
            assertEquals("$name zone", r.zone, u.zone)
            assertEquals("$name easting", r.e, u.easting, 0.005)
            assertEquals("$name northing", r.n, u.northing, 0.005)
            near(GeoPoint(r.lat, r.lon), u.toPoint(), 0.01, "$name inverse")
            near(GeoPoint(r.lat, r.lon), Coords.parse(u.toString())?.point, 1.0, "$name parse ${u}")
        }
        assertNull(Utm.from(GeoPoint(85.0, 0.0)))
    }

    @Test
    fun mgrsMatchesLibrary() {
        for ((name, r) in refs) {
            val s = Coords.format(GeoPoint(r.lat, r.lon), CoordFormat.MGRS)!!
            assertEquals(name, r.mgrs, s.replace(" ", ""))
            near(GeoPoint(r.lat, r.lon), Coords.parse(s)?.point, 1.5, "$name parse $s")
            near(GeoPoint(r.lat, r.lon), Coords.parse(r.mgrs)?.point, 1.5, "$name parse compact")
        }
        // Lower precision: 100 m square, its centre.
        near(GeoPoint(31.7767, 35.2345), Coords.parse("36R YA 115 178")?.point, 80.0, "100 m MGRS")
    }

    @Test
    fun israeliGridMatchesEpsg() {
        for ((name, r) in refs) {
            val itm = Itm.from(GeoPoint(r.lat, r.lon))
            if (r.itm == null) {
                assertNull(name, itm)
                continue
            }
            assertEquals("$name E", r.itm.first, itm!!.first, 0.05)
            assertEquals("$name N", r.itm.second, itm.second, 0.05)
            near(GeoPoint(r.lat, r.lon), Itm.toPoint(r.itm.first, r.itm.second), 0.05, "$name inverse")
        }
        val j = GeoPoint(31.7767, 35.2345)
        near(j, Coords.parse("222304 631558")?.point, 1.0, "ITM e n")
        near(j, Coords.parse("ITM 222304/631558")?.point, 1.0, "ITM e/n")
        near(j, Coords.parse("631558, 222304")?.point, 1.0, "ITM n, e")
        assertEquals(CoordFormat.ITM, Coords.parse("222304 631558")?.format)
    }

    @Test
    fun plusCodesMatchLibrary() {
        for ((name, r) in refs) {
            assertEquals(name, r.olc, Coords.format(GeoPoint(r.lat, r.lon), CoordFormat.PLUS))
            near(GeoPoint(r.lat, r.lon), Coords.parse(r.olc)?.point, 12.0, "$name decode")
        }
        // Short code + place name, recovered near a reference point (as openlocationcode does).
        near(GeoPoint(31.7767, 35.2345), Coords.parse("GM+MR Jerusalem", GeoPoint(31.78, 35.22))?.point, 12.0, "short code")
        near(GeoPoint(31.7799375, 35.2124375), Coords.parse("Q6H6+XX", GeoPoint(31.78, 35.22))?.point, 1.0, "recoverNearest")
        assertNull(Coords.parse("Q6H6+XX")) // a short code needs a reference
    }

    @Test
    fun maidenhead() {
        assertEquals("KM71os", Coords.format(GeoPoint(31.7767, 35.2345), CoordFormat.MAIDENHEAD))
        assertEquals("FN20xr", Coords.format(GeoPoint(40.7128, -74.006), CoordFormat.MAIDENHEAD))
        near(GeoPoint(31.7767, 35.2345), Coords.parse("KM71os")?.point, 5_000.0, "6-char")
        near(GeoPoint(31.5, 35.0), Coords.parse("km71")?.point, 1.0, "4-char centre")
    }

    @Test
    fun degreesInManySpellings() {
        val j = GeoPoint(31.7767, 35.2345)
        val ok = listOf(
            "31.7767, 35.2345", "31.7767 35.2345", "31.7767N 35.2345E", "N31.7767 E35.2345",
            "N 31 46.602 E 35 14.070", "N31° 46.602′ E35° 14.070′", "31°46'36.1\"N 35°14'04.2\"E",
            "31° 46' 36.12\" N, 35° 14' 04.2\" E", "E35.2345 N31.7767", "lat 31.7767 lon 35.2345",
            "31 46 36.1 35 14 4.2",
        )
        for (s in ok) near(j, Coords.parse(s)?.point, 5.0, s)
        assertEquals(CoordFormat.DDM, Coords.parse("N 31 46.602 E 35 14.070")?.format)
        assertEquals(CoordFormat.DMS, Coords.parse("31°46'36.1\"N 35°14'04.2\"E")?.format)
        near(GeoPoint(-33.8568, 151.2153), Coords.parse("33°51'24.5\"S 151°12'55.1\"E")?.point, 5.0, "south east")
        near(GeoPoint(40.7128, -74.006), Coords.parse("40.7128, -74.006")?.point, 1.0, "negative lon")
        for (bad in listOf("hello", "31.7767", "95.0 10.0", "31 75 10 35 10 10", "N31 N35")) {
            assertNull(bad, Coords.parse(bad))
        }
    }

    @Test
    fun formattingRoundTrips() {
        val p = GeoPoint(31.7767, 35.2345)
        assertEquals("31.776700, 35.234500", Coords.format(p, CoordFormat.DD))
        assertEquals("N31° 46.602′ E35° 14.070′", Coords.format(p, CoordFormat.DDM))
        assertEquals("31°46′36.1″N 35°14′04.2″E", Coords.format(p, CoordFormat.DMS))
        assertEquals("36R 711594 3517858", Coords.format(p, CoordFormat.UTM))
        assertEquals("36R YA 11594 17858", Coords.format(p, CoordFormat.MGRS))
        assertEquals("222304 631558", Coords.format(p, CoordFormat.ITM))
        // Every format reads back what it writes.
        for (f in CoordFormat.entries) {
            val s = Coords.format(p, f)!!
            val back = Coords.parse(s, reference = p)
            near(p, back?.point, if (f == CoordFormat.MAIDENHEAD) 5_000.0 else 8.0, "$f $s")
            assertEquals("$f $s", f, back!!.format)
        }
        // Seconds that round up carry into the minutes.
        assertEquals("31°47′00.0″N 35°00′00.0″E", Coords.format(GeoPoint(31.0 + 46.0 / 60 + 59.99 / 3600, 35.0), CoordFormat.DMS))
    }
}
