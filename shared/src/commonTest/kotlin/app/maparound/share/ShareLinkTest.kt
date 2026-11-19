package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What's shared carries only the map link chosen (Settings → Sharing), MapAround's by default. */
class ShareLinkTest {
    private val google = "www.google.com/maps"
    private val osm = "www.openstreetmap.org"
    private val mapAround = "maparound.app/app/"

    @Test
    fun mapAroundsAloneByDefault() {
        assertEquals(ShareLink.MAPAROUND, SharedLinks.shareLink)
        val text = SharedLinks.pointText(32.01512, 34.94208, "Spring")
        assertEquals("Spring\n${app.maparound.ui.Fmt.point(32.01512, 34.94208)}\nMapAround: ${SharedLinks.webAppLink(32.01512, 34.94208)}", text)
        assertFalse(google in text || osm in text, text)
        // It opens back as the same place, with its name.
        val p = SharedLinks.parse(text) as Shared.Point
        assertEquals(32.01512, p.lat, 1e-6)
        assertEquals("Spring", p.label)
    }

    @Test
    fun coordinatesInTheFormatChosen() {
        val before = app.maparound.i18n.Units.current
        try {
            app.maparound.i18n.Units.set(before.copy(coords = app.maparound.tools.CoordFormat.UTM))
            val text = SharedLinks.pointText(31.7767, 35.2345, null)
            val utm = app.maparound.ui.Fmt.point(31.7767, 35.2345)
            assertTrue(utm.startsWith("36R"), utm)
            assertTrue(text.startsWith("$utm\n"), text)
            // Read back: the place from the link, and the coordinates aren't taken for its name.
            val p = SharedLinks.parse(text) as Shared.Point
            assertEquals(31.7767, p.lat, 1e-6)
            assertEquals(null, p.label)
            app.maparound.i18n.Units.set(before.copy(coords = app.maparound.tools.CoordFormat.DMS))
            assertTrue("31°46′" in SharedLinks.pointText(31.7767, 35.2345, "Gate"), SharedLinks.pointText(31.7767, 35.2345, "Gate"))
            assertEquals("Gate", (SharedLinks.parse(SharedLinks.pointText(31.7767, 35.2345, "Gate")) as Shared.Point).label)
        } finally {
            app.maparound.i18n.Units.set(before)
        }
    }

    @Test
    fun aPointWithTheLinkChosen() {
        val g = SharedLinks.pointText(32.0, 35.0, null, ShareLink.GOOGLE)
        assertTrue(google in g && osm !in g && mapAround !in g, g)
        val o = SharedLinks.pointText(32.0, 35.0, null, ShareLink.OSM)
        assertTrue(osm in o && google !in o && mapAround !in o, o)
        val all = SharedLinks.pointText(32.0, 35.0, null, ShareLink.ALL)
        assertTrue(google in all && osm in all && mapAround in all, all)
    }

    @Test
    fun aRouteWithTheLinkChosen() {
        val line = List(20) { GeoPoint(31.0 + it * 0.001, 35.0 + it * 0.001) }
        val recorded = Track("Morning walk", TrackKind.RECORDED, line, 0L)
        val m = RouteText.of(recorded, ShareLink.MAPAROUND)
        assertTrue("$mapAround#track=" in m && google !in m && osm !in m, m)
        val g = RouteText.of(recorded, ShareLink.GOOGLE)
        assertTrue(google in g && "#track=" !in g, g)
        // A track: OpenStreetMap at its start.
        val o = RouteText.of(recorded, ShareLink.OSM)
        assertTrue(osm in o && "#track=" !in o && google !in o, o)

        val planned = Track("Wadi Qelt", TrackKind.PLANNED, line, 0L, RouteProfile.HIKE, waypoints = listOf(line.first(), line.last()))
        val p = RouteText.of(planned, ShareLink.OSM)
        assertTrue("brouter" in p && google !in p && "#track=" !in p, p)
        val gp = RouteText.of(planned, ShareLink.GOOGLE)
        assertTrue("google.com/maps/dir/" in gp && "brouter" !in gp, gp)
        val all = RouteText.of(planned, ShareLink.ALL)
        assertTrue("#track=" in all && "brouter" in all && "google.com/maps/dir/" in all, all)
    }
}
