package app.maparound.map

import app.maparound.search.Commons
import app.maparound.search.Wikipedia
import kotlinx.coroutines.test.runTest
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
        runTest {
            val castle = MapPoi(47.13, 9.52, mapOf("name" to "Schloss Vaduz", "wikipedia" to "de:Schloss Vaduz"))
            assertEquals("https://de.wikipedia.org/wiki/Schloss_Vaduz", Wikipedia.articleOf(castle, "de")?.url)
            assertNull(Wikipedia.linkFor(MapPoi(0.0, 0.0, mapOf("name" to "x")), "en"))
        }
    }

    @Test
    fun photosAroundAndTheirCredit() {
        val json = """{"batchcomplete":"","query":{"pages":{
            "99451070":{"pageid":99451070,"ns":6,"title":"File:Schloss Vaduz. Ansicht von Norden.jpg","index":0,
                "coordinates":[{"lat":47.139603,"lon":9.524427,"primary":"","globe":"earth"}],
                "imagerepository":"local","imageinfo":[{"thumburl":"https://upload.wikimedia.org/n/330px-N.jpg","thumbwidth":320,"thumbheight":213,
                "url":"https://upload.wikimedia.org/n/N.jpg","descriptionurl":"https://commons.wikimedia.org/wiki/File:N.jpg","mime":"image/jpeg"}]},
            "99451068":{"pageid":99451068,"ns":6,"title":"File:Nearest.jpg","index":-1,
                "imageinfo":[{"thumburl":"https://upload.wikimedia.org/x/330px-Nearest.jpg","mime":"image/jpeg"}]},
            "5":{"pageid":5,"ns":6,"title":"File:Map.svg","index":1,"imageinfo":[{"thumburl":"https://upload.wikimedia.org/m.png","mime":"image/svg+xml"}]}}}}"""
        val photos = Commons.parseNearby(json)
        assertEquals(listOf("File:Nearest.jpg", "File:Schloss Vaduz. Ansicht von Norden.jpg"), photos.map { it.file })
        assertEquals(47.139603, photos[1].lat)
        assertNull(photos[0].lat)
        assertEquals("Schloss Vaduz. Ansicht von Norden", photos[1].name)
        assertEquals(emptyList(), Commons.parseNearby("{}"))
        assertTrue("ggsnamespace=6" in Commons.nearbyUrl(47.1, 9.5) && "ggscoord=47.10000%7C9.50000" in Commons.nearbyUrl(47.1, 9.5))

        val details = """{"query":{"pages":{"-1":{"ns":6,"title":"File:Vaduz Castle.jpg","missing":"","known":"","imagerepository":"shared",
            "imageinfo":[{"thumburl":"https://upload.wikimedia.org/v/1280px-V.jpg","url":"https://upload.wikimedia.org/v/V.jpg",
            "descriptionurl":"https://commons.wikimedia.org/wiki/File:Vaduz_Castle.jpg",
            "extmetadata":{"ImageDescription":{"value":"Digital copy of print taken in 2001.","source":"commons-desc-page"},
            "Artist":{"value":"<a rel=\"nofollow\" class=\"external text\" href=\"https://www.flickr.com/people/1\">Gary Todd</a> from Xinzheng, China","source":"commons-desc-page"},
            "LicenseShortName":{"value":"CC BY-SA 4.0","source":"commons-desc-page","hidden":""}}}]}}}}"""
        val d = Commons.parseDetails(details)!!
        assertEquals("https://upload.wikimedia.org/v/1280px-V.jpg", d.image)
        assertEquals("https://commons.wikimedia.org/wiki/File:Vaduz_Castle.jpg", d.pageUrl)
        assertEquals("Gary Todd from Xinzheng, China", d.author)
        assertEquals("CC BY-SA 4.0", d.license)
        assertEquals("Digital copy of print taken in 2001.", d.description)
        assertNull(Commons.parseDetails("{}"))
        assertEquals("Tom & Jerry's \"cat\"", Commons.plainText("<b>Tom</b> &amp; Jerry&#39;s &quot;cat&quot;"))
    }

    @Test
    fun wikipediaNearby() {
        val json = """{"batchcomplete":"","query":{"pages":{
            "6962997":{"pageid":6962997,"ns":0,"title":"Vaduz Castle","index":0,
                "coordinates":[{"lat":47.13944444,"lon":9.52444444,"primary":"","globe":"earth"}],
                "thumbnail":{"source":"https://upload.wikimedia.org/a/160px-Vaduz_Schloss.jpg","width":160,"height":100},
                "pageimage":"Vaduz_Schloss.jpg"},
            "2":{"pageid":2,"ns":0,"title":"Vaduz","index":-1,"coordinates":[{"lat":47.141,"lon":9.521}]},
            "3":{"pageid":3,"ns":0,"title":"Nowhere"}}}}"""
        val articles = Wikipedia.parseNearby(json, "en", 47.1408, 9.5212)
        assertEquals(listOf("Vaduz", "Vaduz Castle"), articles.map { it.title })
        assertEquals("https://en.wikipedia.org/wiki/Vaduz_Castle", articles[1].url)
        assertEquals("File:Vaduz_Schloss.jpg", articles[1].picture?.file)
        assertEquals("en.wikipedia.org", articles[1].picture?.site)
        assertNull(articles[0].picture)
        assertTrue(articles[0].distanceM < 50)
        assertEquals("https://de.wikipedia.org/wiki/Schloss_Vaduz", Wikipedia.articleUrl("de", "Schloss Vaduz"))
        assertEquals(emptyList(), Wikipedia.parseNearby("{}", "en", 0.0, 0.0))
        assertTrue("ggscoord=47.10000%7C9.50000" in Wikipedia.nearbyUrl("en", 47.1, 9.5))
        val picture = """{"query":{"normalized":[{"from":"Vaduz_Castle","to":"Vaduz Castle"}],"pages":{"6962997":{"pageid":6962997,"ns":0,
            "title":"Vaduz Castle","thumbnail":{"source":"https://upload.wikimedia.org/b/500px-V.jpg","width":480,"height":300},"pageimage":"V.jpg"}}}}"""
        assertEquals(Commons.Photo("File:V.jpg", "https://upload.wikimedia.org/b/500px-V.jpg", site = "de.wikipedia.org"), Wikipedia.parsePicture(picture, "de"))
        assertNull(Wikipedia.parsePicture("""{"query":{"pages":{"1":{"title":"No picture"}}}}""", "en"))

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
