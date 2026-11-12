package app.maparound.routing

import app.maparound.sensors.GeoFix
import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertTrue
import kotlin.math.cos
import kotlin.test.Test

class NavigatorTest {
    private val metresPerDegree = 111_195.0

    /** A point [north] and [east] metres from 47°N 9°E. */
    private fun at(north: Double, east: Double) =
        GeoPoint(47.0 + north / metresPerDegree, 9.0 + east / (metresPerDegree * cos(toRadians(47.0))))

    private fun fix(p: GeoPoint, second: Int, speed: Float, bearing: Float?) = GeoFix(
        lat = p.lat, lon = p.lon, altitudeM = null, accuracyM = 5f, speedMps = speed, speedAccuracyMps = null,
        bearingDeg = bearing, timeMs = 1_700_000_000_000L + second * 1000L, elapsedMs = second * 1000L, satellite = true,
    )

    /** 1 km north, then 1 km east; a point every 50 m, the corner at index 20. */
    private val lPoints = (0..20).map { at(it * 50.0, 0.0) } + (1..20).map { at(1000.0, it * 50.0) }
    private val lWaypoints = listOf(lPoints.first(), lPoints.last())

    private fun lRoute(turns: List<Maneuver>, durationS: Long? = 150) =
        RouteResult(lPoints, TrackStats.of(lPoints), durationS, RouteResult.Source.OFFLINE, turns)

    @Test
    fun readsBrouterTurnHints() {
        // As brouter.de sends them (car-vario, timode=2), trimmed.
        val json = """
            {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {
              "track-length": "6543",
              "voicehints": [
                [0,13,2,3255.0,-111],
                [129,9,0,196.0,4],
                [140,5,0,32.0,96],
                [144,3,0,1083.0,-28],
                [150,14,-3,50.0,170],
                [185,12,0,1877.0,0]
              ],
              "messages": [["Longitude", "Latitude"]]
            }, "geometry": {"type": "LineString", "coordinates": [[9.52,47.14,456]]}}]}
        """.trimIndent()
        val hints = Guidance.brouterHints(json)
        assertEquals(
            listOf(
                Maneuver(0, Turn.ROUNDABOUT, 2),
                Maneuver(129, Turn.KEEP_RIGHT),
                Maneuver(140, Turn.RIGHT),
                Maneuver(144, Turn.SLIGHT_LEFT),
                Maneuver(150, Turn.ROUNDABOUT_CW, 3),
                // 12 ("off route") says nothing
            ),
            hints,
        )
        assertEquals(emptyList<Maneuver>(), Guidance.brouterHints("""{"features": [{"properties": {"messages": []}}]}"""))
        assertEquals(emptyList<Maneuver>(), Guidance.brouterHints("not json"))
    }

    @Test
    fun addsStopsAndTheArrival() {
        val viaCorner = listOf(lPoints.first(), lPoints[20], lPoints.last())
        // A routed turn at the stop: one entry that says both.
        assertEquals(
            listOf(Maneuver(20, Turn.RIGHT, stop = 1), Maneuver(40, Turn.ARRIVE)),
            Guidance.maneuvers(lPoints, listOf(Maneuver(20, Turn.RIGHT)), viaCorner),
        )
        // Straight lines (no hints): the turn comes from the shape.
        assertEquals(
            listOf(Maneuver(20, Turn.RIGHT, stop = 1), Maneuver(40, Turn.ARRIVE)),
            Guidance.maneuvers(lPoints, emptyList(), viaCorner),
        )
        // A stop on the straight part of a routed line gets its own entry.
        val viaMiddle = listOf(lPoints.first(), lPoints[10], lPoints.last())
        assertEquals(
            listOf(Maneuver(10, Turn.STOP, stop = 1), Maneuver(20, Turn.RIGHT), Maneuver(40, Turn.ARRIVE)),
            Guidance.maneuvers(lPoints, listOf(Maneuver(20, Turn.RIGHT)), viaMiddle),
        )
    }

    @Test
    fun saysThings() {
        assertEquals("At the roundabout, take the 2nd exit", Guidance.instruction(Maneuver(0, Turn.ROUNDABOUT, 2)))
        assertEquals("Turn left at stop 2", Guidance.instruction(Maneuver(5, Turn.LEFT, stop = 2)))
        assertEquals("In 300 metres, turn right", Guidance.ahead(Maneuver(5, Turn.RIGHT), 296.0))
        assertEquals("You have reached the start of your route", Guidance.now(Maneuver(5, Turn.STOP, stop = 0)))
        assertEquals("10 metres", Guidance.spokenDistance(8.0))
        assertEquals("50 metres", Guidance.spokenDistance(47.0))
        assertEquals("1 kilometre", Guidance.spokenDistance(1020.0))
        assertEquals("1.5 kilometres", Guidance.spokenDistance(1480.0))
        assertEquals("11th", Guidance.ordinal(11))
        assertEquals("22nd", Guidance.ordinal(22))
    }

    @Test
    fun drivesTheRouteAndSaysEachCallOnce() {
        val nav = Navigator(lRoute(listOf(Maneuver(20, Turn.RIGHT))), RouteProfile.CAR, lWaypoints)
        val speech = ArrayList<String>()
        var lastToCorner = Double.MAX_VALUE
        var last: Navigator.Progress? = null
        val steps = (0..2000 step 14).map { it.toDouble() } + 2000.0
        steps.forEachIndexed { second, d ->
            val p = if (d <= 1000) at(d, 0.0) else at(1000.0, d - 1000)
            val progress = nav.update(fix(p, second, speed = 14f, bearing = if (d <= 1000) 0f else 90f))
            assertFalse("on the route at $d m", progress.offRoute)
            if (progress.next?.turn == Turn.RIGHT) {
                assertTrue(progress.distanceToNextM <= lastToCorner)
                lastToCorner = progress.distanceToNextM
            }
            progress.speech?.let { speech += it }
            last = progress
        }
        assertEquals(6, speech.size)
        assertEquals("In 1 kilometre, turn right", speech[0])
        assertTrue(speech[1], speech[1].matches(Regex("In (2[5-9]0|300) metres, turn right")))
        assertEquals("Turn right", speech[2])
        assertEquals("In 1 kilometre, you arrive at your destination", speech[3])
        assertTrue(speech[4], speech[4].matches(Regex("In (2[5-9]0|300) metres, you arrive at your destination")))
        assertEquals("You have arrived at your destination", speech[5])
        assertTrue(last!!.arrived)
        assertEquals(0.0, last!!.remainingM, 1.0)
        assertEquals(0L, last!!.remainingS!!)
    }

    @Test
    fun noticesLeavingTheRoute() {
        val nav = Navigator(lRoute(emptyList()), RouteProfile.HIKE, lWaypoints)
        assertFalse(nav.update(fix(at(100.0, 0.0), 0, 1.3f, 0f)).offRoute)
        // 100 m west of the route: off it after four fixes in a row.
        val off = (1..4).map { nav.update(fix(at(110.0 + it, -100.0), it, 1.3f, 0f)).offRoute }
        assertEquals(listOf(false, false, false, true), off)
        assertEquals(100.0, nav.update(fix(at(115.0, -100.0), 5, 1.3f, 0f)).offsetM, 2.0)
        // Back on it: fine again.
        assertFalse(nav.update(fix(at(120.0, 0.0), 6, 1.3f, 0f)).offRoute)
    }

    @Test
    fun outAndBackFollowsTheLegWeAreOn() {
        // 500 m north and back the same way, with a stop at the top.
        val up = (0..10).map { at(it * 50.0, 0.0) }
        val points = up + up.reversed().drop(1)
        val route = RouteResult(points, TrackStats.of(points), 900, RouteResult.Source.OFFLINE)
        val nav = Navigator(route, RouteProfile.HIKE, listOf(points.first(), up.last(), points.last()))
        assertEquals(listOf(Turn.U_TURN_LEFT, Turn.ARRIVE), nav.maneuvers.map { it.turn })
        assertEquals(1, nav.maneuvers.first().stop)

        var second = 0
        // At the start, which is also the end: not arrived.
        assertFalse(nav.update(fix(at(0.0, 0.0), second++, 1.3f, 0f)).arrived)
        var p: Navigator.Progress? = null
        for (d in 10..250 step 10) p = nav.update(fix(at(d.toDouble(), 0.0), second++, 1.3f, 0f))
        assertEquals(250.0, p!!.alongM, 2.0)
        assertEquals(Turn.U_TURN_LEFT, p.next?.turn)
        for (d in 260..500 step 10) p = nav.update(fix(at(d.toDouble(), 0.0), second++, 1.3f, 0f))
        for (d in 490 downTo 250 step 10) p = nav.update(fix(at(d.toDouble(), 0.0), second++, 1.3f, 180f))
        assertEquals(750.0, p!!.alongM, 2.0)
        assertEquals(Turn.ARRIVE, p.next?.turn)
        assertEquals(1, p.stopsPassed)
        for (d in 240 downTo 0 step 10) p = nav.update(fix(at(d.toDouble(), 0.0), second++, 1.3f, 180f))
        assertTrue(p!!.arrived)
    }

    @Test
    fun routeStartingElsewhereIsJoinedWhenReached() {
        val nav = Navigator(lRoute(listOf(Maneuver(20, Turn.RIGHT))), RouteProfile.CAR, lWaypoints)
        // 300 m south of the start: not on the route yet, and nothing said.
        val away = nav.update(fix(at(-300.0, 0.0), 0, 10f, 0f))
        assertFalse(away.joined)
        assertTrue(away.speech == null)
        val on = nav.update(fix(at(0.0, 0.0), 30, 10f, 0f))
        assertTrue(on.joined)
        assertNotNull(on.speech)
    }

    @Test
    fun everyTurnHasAnArrowInsideItsBox() {
        for (turn in Turn.entries) for (exit in 0..5) {
            val g = TurnGlyph.of(turn, exit)
            assertTrue(turn.name, g.lines.isNotEmpty() || g.rings.isNotEmpty())
            val all = g.lines.flatten() + g.heads.flatten() + g.rings.map { it.center }
            for (pt in all) assertTrue("$turn $exit $pt", pt.x in 0f..1f && pt.y in 0f..1f)
        }
    }
}
