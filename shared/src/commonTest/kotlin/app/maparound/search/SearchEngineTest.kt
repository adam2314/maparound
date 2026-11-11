package app.maparound.search

import app.maparound.places.Place
import app.maparound.places.PlaceCategory
import app.maparound.routing.GeoPoint
import app.maparound.shared.mapsforge.MfTag
import app.maparound.shared.testEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEngineTest {
    private val photon = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[9.5209,47.1410]},
           "properties":{"osm_value":"town","name":"Vaduz","state":"Oberland","country":"Liechtenstein"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[9.53,47.15]},
           "properties":{"osm_value":"house","street":"Städtle","housenumber":"12","city":"Vaduz","country":"Liechtenstein"}},
          {"type":"Feature","geometry":null,"properties":{"name":"no position"}},
          {"type":"Feature","geometry":{"type":"Point","coordinates":[9.0,47.0]},"properties":{"name":null,"city":null}}
        ]}
    """

    private val nominatim = """
        [{"place_id":1,"lat":"31.7767","lon":"35.2345","type":"city","name":"Jerusalem",
          "display_name":"Jerusalem, Jerusalem District, Israel"},
         {"place_id":2,"lat":"31.5","lon":"35.0","type":"peak_area","name":"",
          "display_name":"Har Hevron, Hebron Hills"}]
    """

    @Test
    fun parsesPhoton() {
        val r = SearchEngine.parsePhoton(photon)
        assertEquals(2, r.size)
        assertEquals("Vaduz", r[0].name)
        assertEquals("Town · Oberland, Liechtenstein", r[0].detail)
        assertEquals(47.1410, r[0].lat)
        assertEquals(9.5209, r[0].lon)
        assertEquals("Städtle 12", r[1].name)
        assertEquals("House · Vaduz, Liechtenstein", r[1].detail)
        assertEquals(1, r[1].score)
        assertTrue(SearchEngine.parsePhoton("{}").isEmpty())
    }

    @Test
    fun parsesNominatim() {
        val r = SearchEngine.parseNominatim(nominatim)
        assertEquals(listOf("Jerusalem", "Har Hevron"), r.map { it.name })
        assertEquals("City · Jerusalem District, Israel", r[0].detail)
        assertEquals("Peak area · Hebron Hills", r[1].detail)
        assertEquals(35.2345, r[0].lon)
    }

    @Test
    fun onlineFallsBackToNominatim() {
        val asked = ArrayList<String>()
        val r = SearchEngine.online("Jerusalem", GeoPoint(31.7, 35.2)) { url ->
            asked += url
            if ("photon" in url) throw IllegalStateException("HTTP 502") else nominatim
        }
        assertEquals("Jerusalem", r.first().name)
        assertEquals(
            listOf(
                "https://photon.komoot.io/api/?limit=15&q=Jerusalem&lat=31.70000&lon=35.20000",
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&q=Jerusalem&viewbox=34.2000,32.7000,36.2000,30.7000",
            ),
            asked,
        )
    }

    @Test
    fun namesInTheReadersLanguage() {
        val raw = "Yerushalayim\ren\bJerusalem\rar\bالقدس\rzh\b耶路撒冷"
        assertEquals("Jerusalem", SearchEngine.display(raw, "en"))
        assertEquals("القدس", SearchEngine.display(raw, "AR"))
        assertEquals("耶路撒冷", SearchEngine.display(raw, "zh-TW")) // base language as fallback
        assertEquals("Yerushalayim", SearchEngine.display(raw, "de"))
        assertEquals("Yerushalayim", SearchEngine.display(raw, ""))
        assertEquals(listOf("Yerushalayim", "Jerusalem", "القدس", "耶路撒冷"), SearchEngine.variants(raw))
        assertEquals("Peak · 812 m", SearchEngine.describe(listOf(MfTag("natural", "peak"), MfTag("ele", "812"))))
        assertEquals("Path", SearchEngine.describe(listOf(MfTag("highway", "track"))))
        assertEquals("Shop: Bakery", SearchEngine.describe(listOf(MfTag("shop", "bakery"))))
    }

    @Test
    fun quickFindsCoordinatesLinksAndPlaces() {
        val spring = Place("a", "Ein Prat spring", 31.832, 35.315, category = PlaceCategory.WATER)
        assertEquals(SearchResult.Source.COORDINATES, SearchEngine.quick("31.7767, 35.2345", null, emptyList()).single().source)
        assertEquals("Shared location", SearchEngine.quick("https://www.openstreetmap.org/?mlat=31.1&mlon=35.1", null, emptyList()).single().name)
        val p = SearchEngine.quick("prat", null, listOf(spring)).single()
        assertEquals("a", p.placeId)
        assertEquals("Water", p.detail)
    }

    /** Runs with MAP_FILE=<path to liechtenstein.map> (set in CI, on the JVM and the iOS simulator). */
    @Test
    fun offlineSearchInARealMap() {
        val map = testEnv("MAP_FILE") ?: return
        val vaduz = GeoPoint(47.141, 9.521)
        for (q in listOf("Vaduz", "rhein", "Malbun", "Hotel")) {
            val r = SearchEngine.offline(q, vaduz, listOf(map), "en")
            println("'$q': ${r.size} results: " + r.take(5).joinToString { "${it.name} [${it.detail}] s${it.score}" })
            assertTrue(r.isNotEmpty(), "found $q")
            assertTrue(r.all { it.source == SearchResult.Source.MAP })
        }
        assertEquals("Vaduz", SearchEngine.offline("Vaduz", vaduz, listOf(map), "en").first().name)
        assertTrue(SearchEngine.offline("Vaduz", GeoPoint(31.7, 35.2), listOf(map), "en").isEmpty()) // far away
        assertTrue(SearchEngine.offline("Vaduz", vaduz, listOf("/no/such.map"), "en").isEmpty())
    }
}
