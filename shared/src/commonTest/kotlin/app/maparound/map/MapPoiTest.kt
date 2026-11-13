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
        assertEquals("Q666946", MapPoi(0.0, 0.0, mapOf("wikidata" to "Q666946")).wikidata)
        assertEquals("Q1", MapPoi(0.0, 0.0, mapOf("wikidata" to "Q1;Q2")).wikidata)
        assertNull(MapPoi(0.0, 0.0, mapOf("wikidata" to "666946")).wikidata)
        assertTrue(MapPoi(0.0, 0.0, mapOf("wikidata" to "Q666946")).hasArticle)
        assertTrue(!MapPoi(0.0, 0.0, mapOf("name" to "x")).hasArticle)
    }

    @Test
    fun itsArticleInTheUsersLanguage() {
        val json = """{"entities":{"Q666946":{"type":"item","id":"Q666946","sitelinks":{
            "dewiki":{"site":"dewiki","title":"Schloss Vaduz","badges":[]},
            "enwiki":{"site":"enwiki","title":"Vaduz Castle","badges":[]},
            "commonswiki":{"site":"commonswiki","title":"Category:Vaduz Castle","badges":[]},
            "dewikivoyage":{"site":"dewikivoyage","title":"Vaduz","badges":[]},
            "zh_yuewiki":{"site":"zh_yuewiki","title":"華杜茲城堡","badges":[]}}}}}"""
        val links = Wikipedia.parseSitelinks(json)
        assertEquals(mapOf("de" to "Schloss Vaduz", "en" to "Vaduz Castle", "zh-yue" to "華杜茲城堡"), links)
        assertEquals(emptyMap(), Wikipedia.parseSitelinks("{\"entities\":{\"Q1\":{\"missing\":\"\"}}}"))
        assertTrue("ids=Q666946" in Wikipedia.sitelinksUrl("Q666946"))

        val own = "de" to "Schloss Vaduz"
        // In the user's language first, then its own tag's, then English, then the one with its name.
        assertEquals("en" to "Vaduz Castle", Wikipedia.pick(links, "en", own, "Schloss Vaduz"))
        assertEquals(own, Wikipedia.pick(links, "he", own, "Schloss Vaduz"))
        assertEquals("en" to "Vaduz Castle", Wikipedia.pick(links, "he", null, null))
        assertEquals("de" to "Schloss Vaduz", Wikipedia.pick(links - "en", "he", null, "Schloss Vaduz"))
        assertNull(Wikipedia.pick(emptyMap(), "en", null, "x"))
        // Not a bot's stub when a written article is there.
        val peak = mapOf("arz" to "جبل درى ستشويستيرن", "ceb" to "Drei Schwestern (tumoy sa bukid sa Ostriya)", "de" to "Drei Schwestern (Berg)", "fr" to "Drei Schwestern")
        assertEquals("de" to "Drei Schwestern (Berg)", Wikipedia.pick(peak, "he", null, "Drei Schwestern"))
        assertEquals("ceb" to "X", Wikipedia.pick(mapOf("ceb" to "X"), "he", null, null))

        // Its tag already in the user's language: no need to ask.
        val castle = MapPoi(47.13, 9.52, mapOf("name" to "Schloss Vaduz", "wikipedia" to "de:Schloss Vaduz"))
        assertEquals("https://de.wikipedia.org/wiki/Schloss_Vaduz", Wikipedia.articleOf(castle, "de")?.url)
        assertNull(Wikipedia.linkFor(MapPoi(0.0, 0.0, mapOf("name" to "x")), "en"))
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
        assertEquals("he", Wikipedia.language("iw"))
        assertEquals("no", Wikipedia.language("nb"))
    }
}
