package app.maparound.routing

import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import app.maparound.tracks.Gpx
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import kotlin.test.Test

class RoutingTest {

    @Test
    fun segmentNamesFollowBRouterConvention() {
        assertEquals("E5_N45", Segments.nameFor(47.1, 9.5))
        assertEquals("E30_N30", Segments.nameFor(32.08, 34.78))  // Tel Aviv
        assertEquals("E35_N30", Segments.nameFor(31.77, 35.21))  // Jerusalem
        assertEquals("W5_N50", Segments.nameFor(54.2, -4.5))     // Isle of Man
        assertEquals("W75_S15", Segments.nameFor(-13.16, -72.54)) // Machu Picchu
        assertEquals(listOf("E30_N25", "E35_N25", "E30_N30", "E35_N30"), Segments.namesFor(29.4, 34.2, 33.4, 35.9))
    }

    @Test
    fun distanceAndClimb() {
        // 1° of latitude ≈ 111.2 km
        assertEquals(111_195.0, Geo.distance(0.0, 0.0, 1.0, 0.0), 50.0)
        val pts = listOf(
            GeoPoint(47.0, 9.0, 1000.0), GeoPoint(47.001, 9.0, 1002.0), // +2 m: noise, ignored
            GeoPoint(47.002, 9.0, 1100.0), GeoPoint(47.003, 9.0, 1050.0),
        )
        val s = TrackStats.of(pts)
        assertEquals(333.6, s.distanceM, 1.0)
        assertEquals(100.0, s.ascentM!!, 0.01)
        assertEquals(50.0, s.descentM!!, 0.01)
        assertEquals(1000.0, s.minEleM!!, 0.01)
        assertEquals(1100.0, s.maxEleM!!, 0.01)
    }

    @Test
    fun hikingTimeUsesDin33466() {
        // 12 km & 600 m up: 3 h horizontal, 2 h vertical -> 3 + 2/2 = 4 h
        assertEquals(4 * 3600L, Geo.hikingTimeSeconds(12_000.0, 600.0, 0.0))
    }

    @Test
    fun densifyKeepsEndsAndStep() {
        val line = Geo.densify(listOf(GeoPoint(47.0, 9.0), GeoPoint(47.01, 9.0)), 100.0)
        assertEquals(13, line.size) // 1112 m in 12 steps of ≤100 m, plus the end point
        assertEquals(47.01, line.last().lat, 1e-9)
    }

    @Test
    fun gpxRoundTrip() {
        val track = Track(
            name = "Ein Karem <loop> & back",
            kind = TrackKind.PLANNED,
            points = listOf(GeoPoint(31.7681, 35.1584, 721.5, 1_700_000_000_000), GeoPoint(31.7731, 35.1251, 610.0, null)),
            created = 1_700_000_000_000,
            profile = RouteProfile.HIKE,
        )
        val bytes = Gpx.toXml(track).encodeToByteArray()
        val back = Gpx.read(bytes, "x", 0)
        assertEquals(track.name, back.name)
        assertEquals(TrackKind.PLANNED, back.kind)
        assertEquals(RouteProfile.HIKE, back.profile)
        assertEquals(2, back.points.size)
        assertEquals(721.5, back.points[0].ele!!, 0.01)
        assertEquals(1_700_000_000_000, back.points[0].time)
        assertEquals(35.1251, back.points[1].lon, 1e-7)
    }

    @Test
    fun plannedRouteKeepsItsStopsAndWaypointsAreRead() {
        val stops = listOf(GeoPoint(31.0, 35.0), GeoPoint(31.1, 35.1), GeoPoint(31.2, 35.0))
        val line = listOf(GeoPoint(31.0, 35.0, 100.0), GeoPoint(31.05, 35.06, 150.0), GeoPoint(31.1, 35.1, 120.0), GeoPoint(31.2, 35.0, 90.0))
        val t = Track("Loop", TrackKind.PLANNED, line, 0, RouteProfile.BIKE, stops)
        val back = Gpx.read(Gpx.toXml(t), "x", 0)
        assertEquals("line comes from the track, not the route", 4, back.points.size)
        assertEquals(3, back.waypoints.size)
        assertEquals(31.1, back.waypoints[1].lat, 1e-9)

        val wpt = StringBuilder().also { Gpx.writeWaypoint("Spring & camp", 30.5, 34.9, it) }.toString()
        val c = Gpx.parse(wpt)
        assertEquals(1, c.waypoints.size)
        assertEquals("Spring & camp", c.waypoints.single().name)
        assertTrue(c.trackPoints.isEmpty() && c.routePoints.isEmpty())
    }

    @Test
    fun readsForeignGpxWithRoutesAndNamespaces() {
        val gpx = """<?xml version="1.0"?>
            <gpx:gpx xmlns:gpx="http://www.topografix.com/GPX/1/1" version="1.1">
              <gpx:wpt lat="1" lon="1"><gpx:name>ignored</gpx:name></gpx:wpt>
              <gpx:rte><gpx:name>Summit</gpx:name>
                <gpx:rtept lat="46.5" lon="8.0"><gpx:ele>2000</gpx:ele></gpx:rtept>
                <gpx:rtept lat="46.51" lon="8.01"/>
              </gpx:rte>
            </gpx:gpx>"""
        val t = Gpx.read(gpx, "file.gpx", 42)
        assertEquals("Summit", t.name)
        assertEquals(TrackKind.IMPORTED, t.kind)
        assertEquals(2, t.points.size)
        assertEquals(42L, t.created)
    }
}
