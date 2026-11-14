package app.maparound.stats

import app.maparound.i18n.I18n
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data view's data: Israel's grid, the open data's tables, Our World in Data's charts, the classes. */
class StatsTest {
    @BeforeTest
    fun english() {
        I18n.apply("en")
    }

    private fun near(expected: Double, actual: Double, tolerance: Double) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, got $actual")

    @Test
    fun israelsGridToLatitudeAndLongitude() {
        // The grid's origin is where it's defined to be.
        val (lat0, lon0) = Itm.toLatLon(219529.584, 626907.390)
        near(31.7343936111, lat0, 1e-7)
        near(35.2045169444, lon0, 1e-7)
        // The CBS's coordinates: Dafna (the Hula Valley) and Bet Uzzi'el (by Ramla).
        val (lat, lon) = assertNotNull(Itm.fromCbs(259917792803))
        near(33.23, lat, 0.01)
        near(35.64, lon, 0.01)
        val (lat2, lon2) = assertNotNull(Itm.fromCbs(191143642000))
        near(31.87, lat2, 0.01)
        near(34.91, lon2, 0.01)
        assertNull(Itm.fromCbs(0))
        assertNull(Itm.fromCbs(12345))
    }

    @Test
    fun localitiesFromTheOpenDataPortal() {
        val places = IsraelLocalities.records(
            """{"result":{"records":[
              {"שם יישוב":"דפנה","סמל יישוב":302,"תעתיק":"DAFNA","שם מחוז":"הצפון","דת יישוב":1,
               "סך הכל אוכלוסייה 2023 - ארעי":1077,"ערבים - ארעי":1,"שנת ייסוד":"1939.0",
               "צורת יישוב שוטפת - ארעי":330,"קואורדינטות":259917792803,"גובה ממוצע עודכון בתאריך 12/02/2025":140,
               "שם יישוב באנגלית":"Dafna"},
              {"שם יישוב":"אזור תעשייה","סמל יישוב":9000,"סך הכל אוכלוסייה 2023 - ארעי":null,"קואורדינטות":191143642000},
              {"שם יישוב":"בלי מקום","סמל יישוב":9001,"סך הכל אוכלוסייה 2023 - ארעי":500,"קואורדינטות":null}
            ]}}""",
        )
        val ages = IsraelLocalities.records(
            """{"result":{"records":[{"סמל_ישוב":"302","סהכ":"1126","גיל_0_5":"77","גיל_6_18":"231","גיל_19_45":"430",
               "גיל_46_55":"152","גיל_56_64":"62","גיל_65_פלוס":"174"}]}}""",
        )
        val clusters = IsraelLocalities.records("""{"result":{"records":[{"LOCALITY SYMBOL":302,"ESHKOL 2019":8}]}}""")
        // Places without people or a place on the map are left out.
        val l = IsraelLocalities.parse(places, ages, clusters).single()
        assertEquals(302, l.code)
        assertEquals("Dafna", l.nameEn)
        assertEquals(1077, l.population)
        assertEquals(1939, l.founded)
        assertEquals(140, l.altitudeM)
        assertEquals(8, l.cluster)
        assertEquals(listOf(77, 231, 430, 152, 62, 174), l.ages)
        assertEquals(3, LocalityIndicator.typeOf(l.form)) // a kibbutz

        // The newest table of a dataset: the first with data.
        assertEquals(
            "b",
            IsraelLocalities.newestTable("""{"result":{"resources":[{"id":"a","datastore_active":false},{"id":"b","datastore_active":true},{"id":"c","datastore_active":true}]}}"""),
        )
        assertNull(IsraelLocalities.newestTable("not json"))
    }

    @Test
    fun countriesLatestFiguresWithoutProjections() {
        val csv = """
            entity,code,year,median_age__estimates,median_age__projected
            Israel,ISR,2020,29.9,
            Israel,ISR,2023,30.1,
            Israel,ISR,2050,,34.0
            World,OWID_WRL,2023,30.5,
            "Bonaire, Sint Eustatius and Saba",BES,2023,40.2,
            Europe,,2023,42.0,
        """.trimIndent()
        val f = CountryStats.parse(csv, lastYear = 2026)
        assertEquals(Figure(30.1, 2023), f["ISR"])
        assertEquals(40.2, f["BES"]?.value) // a quoted name with a comma
        assertFalse("OWID_WRL" in f)
        assertEquals(2, f.size)
    }

    @Test
    fun classesAndTheMapsLayers() {
        assertEquals(0, CountryIndicator.POPULATION.classOf(500_000.0))
        assertEquals(6, CountryIndicator.POPULATION.classOf(1.4e9))
        assertEquals("under 1.0 million", CountryIndicator.POPULATION.classes.first().label)
        assertEquals(7, CountryIndicator.POPULATION.classes.size)

        val l = Locality(1, "א", "A", 31.8, 35.2, 4_000, 400, "ירושלים", 370, 1, 1982, 750, 9, listOf(10, 20, 30, 10, 10, 20))
        assertEquals(1, LocalityIndicator.SIZE.classOf(l)) // 1,000–4,999
        assertEquals(4, LocalityIndicator.ELDERLY.classOf(l)) // 20%
        assertEquals(2, LocalityIndicator.CHILDREN.classOf(l)) // 30%: the third class, 30–35%
        assertEquals(1, LocalityIndicator.ARABS.classOf(l)) // 10%
        assertEquals(4, LocalityIndicator.TYPE.classOf(l)) // a community settlement
        assertEquals(2, LocalityIndicator.FOUNDED.classOf(l)) // 1967–1989
        assertEquals(3, LocalityIndicator.ALTITUDE.classOf(l)) // 500–799 m
        assertEquals(4, LocalityIndicator.CLUSTER.classOf(l)) // 9–10
        assertEquals(0, LocalityIndicator.DISTRICT.classOf(l))
        // The CBS spells it "אזור יהודה ושומרון"; the district's name in English either way.
        assertEquals(6, LocalityIndicator.DISTRICT.classOf(l.copy(district = "אזור יהודה ושומרון")))
        assertEquals("Judea and Samaria", app.maparound.i18n.T.stats.district("אזור יהודה ושומרון"))
        assertNull(LocalityIndicator.ELDERLY.classOf(l.copy(ages = null)))

        val shown = StatsLayers.localities(listOf(l), LocalityIndicator.SIZE, emptySet())
        assertTrue("\"tap\":\"data:l:1\"" in shown && "\"color\":\"#fdcc8a\"" in shown, shown)
        assertFalse("data:l:1" in StatsLayers.localities(listOf(l), LocalityIndicator.SIZE, setOf(1)))
        // No ages: "no data" (-1), hidden with it.
        assertFalse("data:l:1" in StatsLayers.localities(listOf(l.copy(ages = null)), LocalityIndicator.ELDERLY, setOf(-1)))

        val countries = StatsLayers.countries(
            mapOf("ISR" to """{"type":"Point","coordinates":[35,31]}"""), CountryIndicator.POPULATION, mapOf("ISR" to Figure(9.8e6, 2023)), emptySet(),
        )
        assertTrue("\"tap\":\"data:c:ISR\"" in countries && "\"color\":\"#7fcdbb\"" in countries, countries)
    }
}
