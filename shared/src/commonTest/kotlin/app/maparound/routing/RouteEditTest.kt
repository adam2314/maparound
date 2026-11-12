package app.maparound.routing

import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.cos
import kotlin.test.Test

class RouteEditTest {
    private val metresPerDegree = 111_195.0

    /** A point [north] and [east] metres from 47°N 9°E. */
    private fun at(north: Double, east: Double) =
        GeoPoint(47.0 + north / metresPerDegree, 9.0 + east / (metresPerDegree * cos(toRadians(47.0))))

    private fun near(expected: GeoPoint, actual: GeoPoint?, withinM: Double = 1.0) {
        assertNotNull(actual)
        assertTrue("$actual is ${Geo.distance(expected, actual!!)} m from $expected", Geo.distance(expected, actual) <= withinM)
    }

    /** A routed line through [stops]: 20 steps per leg, so stop k is point 20·k. */
    private fun routed(stops: List<GeoPoint>): List<GeoPoint> {
        val out = arrayListOf(stops.first())
        for (k in 1 until stops.size) {
            val p = stops[k - 1]
            val q = stops[k]
            for (s in 1..20) out += GeoPoint(p.lat + (q.lat - p.lat) * s / 20, p.lon + (q.lon - p.lon) * s / 20)
        }
        return out
    }

    @Test
    fun findsTheNearestPointOfALine() {
        val line = listOf(at(0.0, 0.0), at(1000.0, 0.0), at(1000.0, 1000.0))
        val hit = RouteEdit.nearestOnLine(line, at(400.0, 30.0))!!
        assertEquals(0, hit.segment)
        near(at(400.0, 0.0), hit.point)
        assertEquals(30.0, hit.distanceM, 0.5)
        // Past the corner: on the second segment, and the corner itself when beyond both.
        assertEquals(1, RouteEdit.nearestOnLine(line, at(1020.0, 600.0))!!.segment)
        near(at(1000.0, 0.0), RouteEdit.nearestOnLine(line, at(1100.0, -100.0))!!.point)
        assertNull(RouteEdit.nearestOnLine(listOf(at(0.0, 0.0)), at(1.0, 1.0)))
    }

    @Test
    fun grabsAStopBeforeTheLine() {
        val stops = listOf(at(0.0, 0.0), at(1000.0, 0.0), at(1000.0, 1000.0))
        val line = routed(stops)
        // 1 m per dp: stops within 24 m, the line within 16 m.
        assertEquals(RouteEdit.Grab(1, inserted = false, at = stops[1]), RouteEdit.grab(at(1010.0, 10.0), stops, line, 1.0))
        // On the first leg: a new stop between 1 and 2 (index 1), on the line.
        val first = RouteEdit.grab(at(500.0, 12.0), stops, line, 1.0)!!
        assertEquals(1, first.index)
        assertTrue(first.inserted)
        near(at(500.0, 0.0), first.at)
        // On the second leg: between 2 and 3.
        assertEquals(2, RouteEdit.grab(at(985.0, 500.0), stops, line, 1.0)!!.index)
        // Too far from both; but zoomed out (10 m per dp) the same spot is close enough.
        assertNull(RouteEdit.grab(at(500.0, 40.0), stops, line, 1.0))
        assertEquals(1, RouteEdit.grab(at(500.0, 40.0), stops, line, 10.0)!!.index)
        // A single stop has no line to grab.
        assertNull(RouteEdit.grab(at(500.0, 0.0), stops.take(1), emptyList(), 1.0))
    }

    @Test
    fun legsFollowTheStopsAlongTheLine() {
        val stopIndices = listOf(0, 20, 40, 60)
        assertEquals(0, RouteEdit.legOf(0, stopIndices))
        assertEquals(0, RouteEdit.legOf(19, stopIndices))
        assertEquals(1, RouteEdit.legOf(20, stopIndices))
        assertEquals(2, RouteEdit.legOf(59, stopIndices))
        assertEquals(2, RouteEdit.legOf(60, stopIndices)) // the last point still belongs to the last leg
    }

    @Test
    fun previewDrawsStraightLegsToTheFinger() {
        val stops = listOf(at(0.0, 0.0), at(1000.0, 0.0), at(1000.0, 1000.0))
        val line = routed(stops) // stops at 0, 20 and 40
        val idx = Guidance.stopIndices(line, stops)
        assertEquals(listOf(0, 20, 40), idx)
        val finger = at(500.0, 500.0)
        // Moving the middle stop: start, finger, end.
        assertEquals(listOf(line.first(), finger, line.last()), RouteEdit.preview(line, idx, RouteEdit.Grab(1, false, stops[1]), finger))
        // Moving the start: the finger, then the route from stop 2 on.
        assertEquals(listOf(finger) + line.subList(20, 41), RouteEdit.preview(line, idx, RouteEdit.Grab(0, false, stops[0]), finger))
        // Moving the end: the route up to stop 2, then the finger.
        assertEquals(line.subList(0, 21) + finger, RouteEdit.preview(line, idx, RouteEdit.Grab(2, false, stops[2]), finger))
        // A new stop on the second leg: the first leg stays as it was.
        assertEquals(line.subList(0, 21) + finger + line.last(), RouteEdit.preview(line, idx, RouteEdit.Grab(2, true, line[30]), finger))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dragAStopOrTheLineAndUndoIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var routings = 0
            PlannerSession.router = { stops, _ ->
                routings++
                val line = routed(stops)
                RouteResult(line, TrackStats.of(line), 0, RouteResult.Source.STRAIGHT)
            }
            PlannerSession.close()
            PlannerSession.open()
            PlannerSession.setProfile(RouteProfile.HIKE)
            val a = at(0.0, 0.0)
            val b = at(1000.0, 0.0)
            val c = at(1000.0, 1000.0)
            PlannerSession.add(a)
            PlannerSession.add(c)
            advanceUntilIdle()
            assertEquals(1, routings)

            // Hold the diagonal 7 m off it and drag the new stop to the corner b: the stop keeps
            // its offset from the finger, so the finger ends 7 m off b too.
            assertTrue(PlannerSession.grab(at(505.0, 495.0), metresPerDp = 1.0))
            near(at(500.0, 500.0), PlannerSession.state.value.waypoints[1])
            PlannerSession.drag(at(800.0, 200.0))
            PlannerSession.drag(at(1005.0, -5.0))
            val dragging = PlannerSession.state.value
            assertEquals(3, dragging.waypoints.size)
            near(b, dragging.waypoints[1])
            assertEquals(3, dragging.preview!!.size) // a, the finger, c: no new route while dragging
            advanceUntilIdle()
            assertEquals(1, routings)
            PlannerSession.drop(at(1005.0, -5.0))
            advanceUntilIdle()
            val dropped = PlannerSession.state.value
            assertNull(dropped.preview)
            near(b, dropped.waypoints[1])
            assertEquals(2, routings)
            assertEquals(41, dropped.route!!.points.size) // routed through b

            // Move the end further east.
            assertTrue(PlannerSession.grab(at(1010.0, 1010.0), metresPerDp = 1.0))
            PlannerSession.drop(at(1010.0, 2010.0))
            near(at(1000.0, 2000.0), PlannerSession.state.value.waypoints[2])

            // Holding a stop without moving it changes nothing and isn't an undo step.
            assertTrue(PlannerSession.grab(b, metresPerDp = 1.0))
            PlannerSession.drag(at(1001.0, 0.0))
            PlannerSession.drop(at(1001.0, 0.0))
            near(b, PlannerSession.state.value.waypoints[1], withinM = 0.01)

            // Nothing there: the map drops its pin instead.
            assertFalse(PlannerSession.grab(at(300.0, 900.0), metresPerDp = 1.0))

            PlannerSession.undo()
            near(c, PlannerSession.state.value.waypoints[2])
            PlannerSession.undo()
            assertEquals(listOf(a, c), PlannerSession.state.value.waypoints)
        } finally {
            PlannerSession.close()
            Dispatchers.resetMain()
        }
    }
}
