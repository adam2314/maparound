package app.maparound.routing

import app.maparound.sensors.GeoFix
import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.math.cos
import kotlin.test.Test

class NavViewTest {
    private val metresPerDegree = 111_195.0

    /** A point [north] and [east] metres from 47°N 9°E. */
    private fun at(north: Double, east: Double) =
        GeoPoint(47.0 + north / metresPerDegree, 9.0 + east / (metresPerDegree * cos(toRadians(47.0))))

    private fun fix(speedKmh: Double, bearing: Float?) = GeoFix(
        lat = 47.0, lon = 9.0, altitudeM = null, accuracyM = 5f, speedMps = (speedKmh / 3.6).toFloat(),
        speedAccuracyMps = null, bearingDeg = bearing, timeMs = 0, elapsedMs = 0, satellite = true,
    )

    @Test
    fun theFasterTheFurtherOut() {
        assertEquals(17.5, NavView.zoomFor(0.0, RouteProfile.HIKE), 1e-9)
        assertEquals(17.5, NavView.zoomFor(5.0, RouteProfile.HIKE), 1e-9)
        assertEquals(17.0, NavView.zoomFor(10.0, RouteProfile.BIKE), 1e-9)
        // Driving: never closer than 17, out to 15 on a highway.
        assertEquals(17.0, NavView.zoomFor(5.0, RouteProfile.CAR), 1e-9)
        assertEquals(16.0, NavView.zoomFor(40.0, RouteProfile.CAR), 1e-9)
        assertEquals(15.5, NavView.zoomFor(80.0, RouteProfile.CAR), 1e-9)
        assertEquals(15.0, NavView.zoomFor(200.0, RouteProfile.CAR), 1e-9)
    }

    @Test
    fun turnsTheShortWayRound() {
        assertEquals(355.0, NavView.turnToward(10.0, 340.0, 0.5), 1e-9)
        assertEquals(5.0, NavView.turnToward(340.0, 30.0, 0.5), 1e-9)
        assertEquals(90.0, NavView.turnToward(0.0, 90.0, 1.0), 1e-9)
    }

    @Test
    fun theRouteAhead() {
        // 100 m north, then 100 m east.
        val route = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0))
        val north = NavView.bearingAhead(route, 0.0, 30.0)!!
        assertTrue(north < 1.0 || north > 359.0)
        // Near the corner it already looks round it.
        val corner = NavView.bearingAhead(route, 95.0, 30.0)!!
        assertTrue("$corner", corner in 45.0..90.0)
        assertEquals(90.0, NavView.bearingAhead(route, 150.0, 30.0)!!, 0.5)
        // At the end: nothing ahead.
        assertNull(NavView.bearingAhead(route, 200.0, 30.0))
        assertNull(NavView.bearingAhead(listOf(at(0.0, 0.0)), 0.0))
    }

    @Test
    fun movingFollowsTheCourseSlowFollowsTheRoute() {
        val v = NavView()
        // Driving east: the course, a good part of the way each fix.
        repeat(10) { v.update(fix(50.0, 90f), RouteProfile.CAR, ahead = 0.0) }
        assertEquals(90.0, v.bearing, 0.5)
        // Standing still: a wandering GPS course doesn't count; the route's way does.
        repeat(20) { v.update(fix(1.0, 200f), RouteProfile.CAR, ahead = 0.0) }
        assertTrue("${v.bearing}", v.bearing < 1.0 || v.bearing > 359.0)
        // No course and no route: as it was.
        val was = v.bearing
        v.update(fix(0.0, null), RouteProfile.CAR, ahead = null)
        assertEquals(was, v.bearing, 1e-9)
    }

    @Test
    fun aNewNavigationStartsAlongTheRoute() {
        val v = NavView()
        repeat(5) { v.update(fix(50.0, 200f), RouteProfile.CAR, ahead = null) }
        v.reset()
        assertEquals(0.0, v.bearing, 1e-9)
        assertTrue(v.zoom.isNaN())
        // Standing at the start of a route going east, before any progress on it.
        val points = listOf(at(0.0, 0.0), at(0.0, 100.0))
        val route = RouteResult(points, TrackStats.of(points), null, RouteResult.Source.STRAIGHT)
        v.update(fix(0.0, null), NavigationSession.State(active = true, profile = RouteProfile.HIKE, route = route))
        assertEquals(90.0, v.bearing, 0.5)
        assertEquals(17.5, v.zoom, 1e-9)
    }

    @Test
    fun theZoomEasesIn() {
        val v = NavView()
        v.update(fix(5.0, null), RouteProfile.BIKE, null)
        assertEquals(17.5, v.zoom, 1e-9)
        // A sudden 40 km/h: not all at once.
        v.update(fix(40.0, 0f), RouteProfile.BIKE, null)
        assertTrue("${v.zoom}", v.zoom < 17.5 && v.zoom > NavView.zoomFor(40.0, RouteProfile.BIKE))
        repeat(30) { v.update(fix(40.0, 0f), RouteProfile.BIKE, null) }
        assertEquals(NavView.zoomFor(40.0, RouteProfile.BIKE), v.zoom, 0.01)
    }
}
