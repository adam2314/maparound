package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

class TrackLinkTest {
    /** A winding hike near Ein Gedi: [n] points, about 3 m apart, climbing. */
    private fun hike(n: Int, heights: Boolean = true) = List(n) { i ->
        val t = i / 40.0
        GeoPoint(31.4600 + i * 2e-5 + 3e-4 * sin(t), 35.3800 + i * 1e-5 + 3e-4 * cos(t), if (heights) -400.0 + i * 0.3 else null)
    }

    @Test
    fun aRouteAndBack() {
        val points = hike(120)
        val link = TrackLink.of("Nahal David", points)!!
        assertTrue(link, link.startsWith("https://maparound.app/app/#track="))
        val back = TrackLink.parse(link)!!
        assertEquals("Nahal David", back.name)
        assertEquals(points.size, back.points.size)
        for ((a, b) in points.zip(back.points)) {
            assertTrue("$a $b", abs(a.lat - b.lat) < 1e-5 && abs(a.lon - b.lon) < 1e-5)
            assertTrue("$a $b", abs(a.ele!! - b.ele!!) <= 0.5)
        }
    }

    @Test
    fun withoutHeightsAndInText() {
        val points = hike(30, heights = false)
        val link = TrackLink.of("Ein Gedi / עין גדי & more", points)!!
        // In a message, with the map's own address part around it, and the name in Hebrew.
        val back = SharedLinks.parse("Look: $link\nsee you there")
        assertTrue(back.toString(), back is Shared.Track)
        back as Shared.Track
        assertEquals("Ein Gedi / עין גדי & more", back.name)
        assertTrue(back.points.all { it.ele == null })
        assertEquals(30, back.points.size)
        assertEquals("Ein Gedi", TrackLink.parse(link.replace("#track=", "#map=15/31.4/35.3&track="))?.name?.substringBefore(" /"))
    }

    @Test
    fun longTracksAreThinnedKeepingTheirShape() {
        val points = hike(5_000)
        val link = TrackLink.of("All day", points)!!
        val back = TrackLink.parse(link)!!.points
        assertTrue("${back.size} points", back.size <= TrackLink.MAX_POINTS)
        assertTrue("${link.length} characters", link.length < 4_000)
        // Both ends kept; every original point near the thinned line's points.
        assertEquals(points.first().lat, back.first().lat, 1e-5)
        assertEquals(points.last().lon, back.last().lon, 1e-5)
        val far = points.filterIndexed { i, _ -> i % 50 == 0 }.maxOf { p -> back.minOf { q -> abs(p.lat - q.lat) + abs(p.lon - q.lon) } }
        assertTrue("farthest $far°", far < 1e-3)
    }

    @Test
    fun notALink() {
        assertNull(TrackLink.parse("https://maparound.app/app/#map=15/31.4/35.3"))
        assertNull(TrackLink.parse("https://maparound.app/app/#track=AAAA"))
        assertNull(TrackLink.parse("#track=!!!"))
        assertNull(TrackLink.of("One point", listOf(GeoPoint(31.0, 35.0))))
    }

    @Test
    fun gpxLinks() {
        assertEquals("https://example.org/hikes/Wadi%20Qelt.gpx", SharedLinks.gpxLink("Here: https://example.org/hikes/Wadi%20Qelt.gpx enjoy"))
        assertEquals("https://example.org/t.GPX?dl=1", SharedLinks.gpxLink("https://example.org/t.GPX?dl=1"))
        assertEquals(
            "https://raw.githubusercontent.com/u/r/main/a.gpx",
            SharedLinks.gpxLink("https://maparound.app/app/?gpx=https%3A%2F%2Fraw.githubusercontent.com%2Fu%2Fr%2Fmain%2Fa.gpx"),
        )
        assertNull(SharedLinks.gpxLink("https://example.org/gpx-guide.html"))
        assertNull(SharedLinks.gpxLink("https://maparound.app/app/?gpx=javascript:alert(1)"))
        assertEquals("Wadi Qelt", SharedLinks.gpxName("https://example.org/hikes/Wadi%20Qelt.gpx?dl=1"))
        assertEquals("ein gedi loop", SharedLinks.gpxName("https://example.org/ein_gedi_loop.GPX"))
    }

    @Test
    fun theSharedTextCarriesTheLink() {
        val recorded = Track("Morning walk", TrackKind.RECORDED, hike(50), 0L)
        val text = RouteText.of(recorded)
        assertTrue(text, "maparound.app/app/#track=" in text)
        assertTrue(RouteText.hasLink(recorded))
        assertTrue(!RouteText.hasRouteLink(recorded))
    }

    @Test
    fun embedCodeIsAnIframeOfTheEmbeddedMap() {
        val track = Track("Tom & Jerry's \"loop\"", TrackKind.RECORDED, hike(50), 0L)
        val code = TrackLink.embedCode(track)!!
        assertTrue(code, code.startsWith("<iframe src=\"https://maparound.app/app/?embed#track="))
        assertTrue(code, code.endsWith("></iframe>"))
        // HTML-escaped, the address's & too; the browser reads it back as the link.
        assertTrue(code, "title=\"Tom &amp; Jerry's &quot;loop&quot;\"" in code)
        val src = code.substringAfter("src=\"").substringBefore('"').replace("&amp;", "&")
        val back = TrackLink.parse(src)!!
        assertEquals(track.name, back.name)
        assertEquals(50, back.points.size)
        assertNull(TrackLink.embedCode(Track("Dot", TrackKind.RECORDED, hike(1), 0L)))
    }
}
