package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.Test

class SharedLinksTest {
    private fun point(s: String) = SharedLinks.parse(s) as Shared.Point

    private fun assertAt(p: Shared.Point, lat: Double, lon: Double) {
        assertEquals(lat, p.lat, 1e-6)
        assertEquals(lon, p.lon, 1e-6)
    }

    @Test
    fun geoUris() {
        assertAt(point("geo:32.01512,34.94208"), 32.01512, 34.94208)
        val labelled = point("geo:0,0?q=31.7767,35.2345(Western%20Wall)")
        assertAt(labelled, 31.7767, 35.2345)
        assertEquals("Western Wall", labelled.label)
        assertEquals(15, point("geo:46.5,7.9?z=15").zoom)
        assertNull(SharedLinks.parse("geo:0,0?q=Main+Street+Tel+Aviv")) // addresses need a geocoder
    }

    @Test
    fun googleLinks() {
        assertAt(point("https://maps.google.com/?q=32.0853,34.7818"), 32.0853, 34.7818)
        assertAt(point("https://www.google.com/maps/search/?api=1&query=32.0853%2C34.7818"), 32.0853, 34.7818)
        val place = point("https://www.google.com/maps/place/Masada/@31.3156,35.3536,15z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d31.3155556!4d35.3536111")
        assertAt(place, 31.3155556, 35.3536111) // exact pin beats the viewport
        assertEquals("Masada", place.label)
        assertAt(point("https://www.google.com/maps/@29.5577,34.9519,12z"), 29.5577, 34.9519)
        // What Google Maps' "share" puts in the text: name, address, short link
        assertTrue(SharedLinks.shortLink("Ein Gedi\nIsrael\nhttps://maps.app.goo.gl/AbCdEf123?g_st=ic") != null)
    }

    @Test
    fun otherMapsAndPlainText() {
        assertAt(point("https://www.openstreetmap.org/?mlat=47.1301&mlon=9.5806#map=16/47.1301/9.5806"), 47.1301, 9.5806)
        assertAt(point("https://www.openstreetmap.org/#map=14/31.7683/35.2137"), 31.7683, 35.2137)
        assertAt(point("https://maps.apple.com/?ll=46.5584,7.9853&q=Jungfraujoch"), 46.5584, 7.9853)
        assertEquals("Jungfraujoch", point("https://maps.apple.com/?ll=46.5584,7.9853&q=Jungfraujoch").label)
        assertAt(point("https://waze.com/ul?ll=32.0853%2C34.7818&navigate=yes"), 32.0853, 34.7818)
        assertAt(point("Meet here: 32.01512, 34.94208"), 32.01512, 34.94208)
        val dms = point("32°00′54.4″N 34°56′31.5″E")
        assertAt(dms, 32.015111, 34.942083)
        assertAt(point("""33°48'12.5"S 151°17'02.1"E"""), -33.803472, 151.283917)
        assertNull(SharedLinks.parse("see you at 7, bring 2 liters of water"))
        assertNull(SharedLinks.parse("geo:0,0"))
    }

    @Test
    fun routeLinksRoundTrip() {
        val wps = listOf(GeoPoint(31.7681, 35.1584), GeoPoint(31.77, 35.14), GeoPoint(31.7731, 35.1251))
        val brouter = SharedLinks.brouterWebLink(wps, RouteProfile.HIKE)!!
        assertTrue(brouter.contains("lonlats=35.1584,31.7681;35.14,31.77;35.1251,31.7731&profile=hiking-mountain"))
        val back = SharedLinks.parse("Our hike: $brouter") as Shared.Route
        assertEquals(3, back.points.size)
        assertEquals(31.77, back.points[1].lat, 1e-9)
        assertEquals(RouteProfile.HIKE, back.profile)

        val google = SharedLinks.googleDirectionsLink(wps, RouteProfile.CAR)!!
        val g = SharedLinks.parse(google) as Shared.Route
        assertEquals(3, g.points.size)
        assertEquals(RouteProfile.CAR, g.profile)

        val dir = SharedLinks.parse("https://www.google.com/maps/dir/31.7681,35.1584/31.7731,35.1251/@31.77,35.14,14z") as Shared.Route
        assertEquals(2, dir.points.size)
    }

    @Test
    fun pointTextContainsWorkingLinks() {
        val text = SharedLinks.pointText(32.01512, 34.94208, "Meeting point")
        assertTrue(text.startsWith("Meeting point\n32.01512, 34.94208"))
        // Our own share text parses back to the same place (MapAround to MapAround).
        val p = point(text)
        assertAt(p, 32.01512, 34.94208)
        assertEquals("Meeting point", p.label)
        assertEquals("geo:32.01512,34.94208?q=32.01512,34.94208(Mount%20Tabor)", SharedLinks.geoUri(32.01512, 34.94208, "Mount Tabor"))
    }
}
