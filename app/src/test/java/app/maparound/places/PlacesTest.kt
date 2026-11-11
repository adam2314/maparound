package app.maparound.places

import app.maparound.routing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class PlacesTest {
    private val spring = Place("a1", "Ein Prat spring", 31.832, 35.315, 120.0, PlaceCategory.WATER, "Cold & clear <3", 1_700_000_000_000)
    private val camp = Place("b2", "Night camp", 31.80, 35.30, null, PlaceCategory.CAMP, "", 1_700_000_100_000)
    private val view = Place("c3", "Sunset view", 31.90, 35.40, 850.0, PlaceCategory.VIEW, "west side", 1_700_000_200_000)

    @Test
    fun gpxRoundTripKeepsEverything() {
        val w = StringWriter()
        Places.writeGpx(listOf(spring, camp, view), w)
        val back = Places.readGpx(w.toString().byteInputStream())
        assertEquals(listOf(spring, camp, view), back)
    }

    @Test
    fun readsOtherAppsWaypoints() {
        val gpx = """<?xml version="1.0"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="47.1" lon="9.5"><name>Hut</name><sym>Campground</sym></wpt>
              <wpt lat="47.2" lon="9.6"><name>Spring</name><type>Drinking water</type><cmt>reliable</cmt></wpt>
              <wpt lat="47.3" lon="9.7"><sym>Summit</sym></wpt>
              <wpt lat="200" lon="9.7"><name>broken</name></wpt>
              <trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
            </gpx>"""
        val p = Places.readGpx(gpx.byteInputStream())
        assertEquals(3, p.size)
        assertEquals(PlaceCategory.CAMP, p[0].category)
        assertEquals(PlaceCategory.WATER, p[1].category)
        assertEquals("reliable", p[1].note)
        assertEquals(PlaceCategory.VIEW, p[2].category)
        assertEquals("47.30000, 9.70000", p[2].name)
    }

    @Test
    fun searchFilterSortAndMerge() {
        val all = listOf(spring, camp, view)
        val me = GeoPoint(31.80, 35.30)
        assertEquals(listOf(camp, spring, view), Places.filter(all, "", null, Places.Sort.NEAREST, me))
        assertEquals(listOf(view, camp, spring), Places.filter(all, "", null, Places.Sort.NEWEST, null))
        assertEquals(listOf(spring, camp, view), Places.filter(all, "", null, Places.Sort.NAME, null))
        assertEquals(listOf(view), Places.filter(all, "WEST", null, Places.Sort.NAME, null)) // matches the note
        assertEquals(listOf(camp), Places.filter(all, "", PlaceCategory.CAMP, Places.Sort.NAME, null))

        val again = spring.copy(id = "zz", lat = spring.lat + 0.00003) // ~3 m away, same name
        val other = spring.copy(id = "yy", name = "Another spring")
        val (merged, added) = Places.merge(all, listOf(again, other))
        assertEquals(1, added)
        assertEquals(4, merged.size)
    }
}
