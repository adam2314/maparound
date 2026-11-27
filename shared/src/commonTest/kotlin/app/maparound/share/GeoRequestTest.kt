package app.maparound.share

import app.maparound.routing.GeoPoint
import app.maparound.routing.RouteProfile
import app.maparound.share.GeoRequest.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The examples of Google's navigation app intents page, and what MapAround reads in them. */
class GeoRequestTest {
    private val nav = GeoRequest.ACTION_NAVIGATE
    private val view = "android.intent.action.VIEW"

    @Test
    fun navigateToAPlaceByName() {
        assertEquals(GeoRequest.Navigate(null, null, "Googleplex", null, null, Mode.NAVIGATE), GeoRequest.parse(nav, "geo:0,0?q=Googleplex"))
        assertEquals(
            GeoRequest.Navigate(null, null, "1600 Amphitheatre parkway", null, RouteProfile.BIKE, Mode.ADD_STOP),
            GeoRequest.parse(nav, "geo:0,0?q=1600+Amphitheatre+parkway&mode=b&intent=add_a_stop"),
        )
        assertEquals(
            GeoRequest.Navigate(null, null, "coffee shop", null, RouteProfile.HIKE, Mode.NAVIGATE),
            GeoRequest.parse(nav, "geo:0,0?q=coffee+shop&mode=w&intent=navigation"),
        )
        // Text near a point: searched there.
        assertEquals(
            GeoRequest.Navigate(null, null, "Starbucks on Main Street", GeoPoint(1.1, 2.2), RouteProfile.HIKE, Mode.NAVIGATE),
            GeoRequest.parse(nav, "geo:1.1,2.2?q=Starbucks+on+Main+Street&mode=w&intent=navigation"),
        )
        // Encoded, and every way of travel but walking and cycling by car.
        assertEquals("Café", (GeoRequest.parse(nav, "geo:0,0?q=Caf%C3%A9&mode=d") as GeoRequest.Navigate).query)
        assertEquals(RouteProfile.CAR, (GeoRequest.parse(nav, "geo:0,0?q=x&mode=l") as GeoRequest.Navigate).profile)
        assertEquals(Mode.DIRECTIONS, (GeoRequest.parse(nav, "geo:0,0?q=x&intent=directions") as GeoRequest.Navigate).mode)
    }

    @Test
    fun navigateToAPoint() {
        assertEquals(
            GeoRequest.Navigate(GeoPoint(1.1, 2.2), null, null, null, RouteProfile.HIKE, Mode.NAVIGATE),
            GeoRequest.parse(nav, "geo:1.1,2.2?mode=w&intent=navigation"),
        )
        assertEquals(
            GeoRequest.Navigate(GeoPoint(32.79, 34.99), "Haifa", null, null, null, Mode.NAVIGATE),
            GeoRequest.parse(nav, "geo:0,0?q=32.79,34.99(Haifa)"),
        )
        // Offline variant.
        assertEquals(GeoPoint(47.0, 9.5), (GeoRequest.parse(nav, "geo.offline:47,9.5") as GeoRequest.Navigate).point)
    }

    @Test
    fun findAndCommands() {
        assertEquals(GeoRequest.Search("restaurants nearby", null), GeoRequest.parse(view, "geo:0,0?q=restaurants+nearby"))
        // A point shown (VIEW): its route, not started.
        assertEquals(Mode.DIRECTIONS, (GeoRequest.parse(view, "geo:1.1,2.2") as GeoRequest.Navigate).mode)
        assertEquals(GeoRequest.Command("exit_navigation", null), GeoRequest.parse(view, "geo.action:?act=exit_navigation"))
        assertEquals(GeoRequest.Command("select_search_result", 2), GeoRequest.parse(view, "geo.action:?act=select_search_result&id=2"))
        assertEquals(GeoRequest.Command("mute", null), GeoRequest.parse(view, "geo.action.offline:?act=mute"))
    }

    @Test
    fun notARequest() {
        assertNull(GeoRequest.parse(nav, "geo:0,0"))
        assertNull(GeoRequest.parse(nav, "geo:0,0?q="))
        assertNull(GeoRequest.parse(nav, null))
        assertNull(GeoRequest.parse(nav, "https://example.org"))
        assertNull(GeoRequest.parse(view, "geo.action:?x=1"))
    }
}
