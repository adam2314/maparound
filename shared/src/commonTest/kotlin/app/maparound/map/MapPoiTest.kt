package app.maparound.map

import app.maparound.search.Wikipedia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapPoiTest {
    @Test
    fun kindsInWords() {
        assertEquals("Peak", PoiKinds.label(mapOf("natural" to "peak", "name" to "Drei Kapuziner")))
        // A pass on a saddle is a pass.
        assertEquals("Mountain pass", PoiKinds.label(mapOf("natural" to "saddle", "mountain_pass" to "yes")))
        assertEquals("Alpine hut", PoiKinds.label(mapOf("tourism" to "alpine_hut")))
        // Values without their own words read as they are.
        assertEquals("Ice cream", PoiKinds.label(mapOf("amenity" to "ice_cream")))
        assertNull(PoiKinds.label(mapOf("name" to "Somewhere")))
        // "An attraction" only when nothing tells more.
        assertEquals("Windmill", PoiKinds.label(mapOf("tourism" to "attraction", "man_made" to "windmill")))
        assertEquals("Attraction", PoiKinds.label(mapOf("tourism" to "attraction")))
    }

    @Test
    fun itsNameInTheUsersLanguage() {
        val mill = MapPoi(31.77, 35.22, mapOf("name" to "טחנת הרוח", "name_en" to "Montefiore Windmill", "man_made" to "windmill"), lang = "en")
        assertEquals("Montefiore Windmill", mill.title)
        assertEquals("טחנת הרוח · Windmill", mill.details)
        assertEquals("טחנת הרוח", mill.copy(lang = "he").title)
        assertEquals("Windmill", mill.copy(lang = "he").details)
    }

    @Test
    fun theMapsAnswerReadOncePerPlaceNearestFirst() {
        val json = """[
            {"lat":47.1,"lon":9.5,"p":{"name":"Far peak","natural":"peak","ele":"2085.4"}},
            {"lat":47.0001,"lon":9.5001,"p":{"name":"Brunnen","amenity":"drinking_water"}},
            {"lat":47.0001,"lon":9.5001,"p":{"name":"Brunnen","amenity":"drinking_water"}},
            {"lat":47.2,"lon":9.5,"p":{"label_of":"area"}},
            {"lat":47.0002,"lon":9.5,"p":{"tourism":"viewpoint"}}
        ]"""
        val pois = MapPoi.parseList(json, 47.0, 9.5)
        assertEquals(listOf("Brunnen", "Viewpoint", "Far peak"), pois.map { it.title })
        assertEquals("Peak · 2085 m", pois[2].details)
        // Unnamed: its kind is the title, not repeated.
        assertNull(pois[1].details)
        assertEquals(emptyList(), MapPoi.parseList("not json", 0.0, 0.0))
    }

    @Test
    fun itsOwnWikipediaArticle() {
        assertEquals("de" to "Drei Schwestern (Rätikon)", MapPoi(0.0, 0.0, mapOf("wikipedia" to "de:Drei Schwestern (Rätikon)")).wikipedia)
        assertNull(MapPoi(0.0, 0.0, mapOf("wikipedia" to "Drei Schwestern")).wikipedia)
        assertNull(MapPoi(0.0, 0.0, mapOf("wikipedia" to "de:")).wikipedia)
    }

    @Test
    fun wikipediaNearby() {
        val json = """{"batchcomplete":"","query":{"geosearch":[
            {"pageid":1,"ns":0,"title":"Vaduz Castle","lat":47.1394,"lon":9.5242,"dist":312.5,"primary":""},
            {"pageid":2,"ns":0,"title":"Vaduz","lat":47.141,"lon":9.521,"dist":120.1,"primary":""}]}}"""
        val articles = Wikipedia.parseNearby(json, "en")
        assertEquals(listOf("Vaduz", "Vaduz Castle"), articles.map { it.title })
        assertEquals("https://en.wikipedia.org/wiki/Vaduz_Castle", articles[1].url)
        assertEquals("https://de.wikipedia.org/wiki/Schloss_Vaduz", Wikipedia.articleUrl("de", "Schloss Vaduz"))
        assertEquals(emptyList(), Wikipedia.parseNearby("{}", "en"))
        assertTrue("gscoord=47.10000%7C9.50000" in Wikipedia.nearbyUrl("en", 47.1, 9.5))

        // The article about a place: named as it is and close by; its own tag first.
        val castle = MapPoi(47.1395, 9.5243, mapOf("name" to "Vaduz Castle", "historic" to "castle"))
        assertEquals("Vaduz Castle", Wikipedia.about(castle, articles)?.title)
        assertNull(Wikipedia.about(castle.copy(lat = 47.0), articles))
        val tagged = castle.copy(tags = castle.tags + ("wikipedia" to "de:Schloss Vaduz"))
        assertEquals("https://de.wikipedia.org/wiki/Schloss_Vaduz", Wikipedia.about(tagged, articles)?.url)

        assertEquals("he", Wikipedia.language("he"))
        assertEquals("pt", Wikipedia.language("pt_BR"))
        assertEquals("en", Wikipedia.language("x!"))
    }
}
