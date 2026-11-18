package app.maparound.watch

import app.maparound.routing.GeoPoint
import app.maparound.routing.NavigationSession
import app.maparound.routing.Turn
import app.maparound.tracks.TrackRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the phone and a watch tell each other. */
class WatchProtocolTest {
    @Test fun stateRoundTrip() {
        val s = WatchState(
            language = "he",
            nav = WatchState.Nav(
                title = "פנו שמאלה", distance = "180 מ'", turn = Turn.ROUNDABOUT, exit = 2,
                left = "4.6 ק\"מ", arrival = "12:43", status = WatchState.NavStatus.ON_ROUTE,
            ),
            recording = WatchState.Recording(startedAt = 1_794_979_000_000L, distance = "3.2 km", climb = "↑ 120 m"),
            distanceUnit = app.maparound.i18n.DistanceUnit.IMPERIAL,
            heightUnit = app.maparound.i18n.HeightUnit.FT,
        )
        assertEquals(s, WatchProtocol.decode(WatchProtocol.encode(s)))
        // Nothing going on: just the language.
        val idle = WatchState("en")
        assertEquals(idle, WatchProtocol.decode(WatchProtocol.encode(idle)))
        assertNull(WatchProtocol.decode("not json".encodeToByteArray()))
    }

    @Test fun routeRoundTrip() {
        val r = WatchRoute(
            points = listOf(GeoPoint(32.08531, 34.77002), GeoPoint(32.0905, 34.77351), GeoPoint(32.091, 34.78)),
            turns = listOf(GeoPoint(32.0905, 34.77351)),
            stops = listOf(GeoPoint(32.091, 34.78)),
        )
        assertEquals(r, WatchProtocol.decodeRoute(WatchProtocol.encodeRoute(r)))
        assertNull(WatchProtocol.decodeRoute(WatchProtocol.encodeRoute(null)))
        // A long route is thinned to the most the watch gets.
        val long = (0 until 5000).map { GeoPoint(32.0 + it * 1e-4, 34.8 + kotlin.math.sin(it / 7.0) * 1e-3) }
        val nav = NavigationSession.State(
            active = true,
            route = app.maparound.routing.RouteResult(long, app.maparound.routing.TrackStats.of(long), 3600, app.maparound.routing.RouteResult.Source.OFFLINE),
            maneuvers = listOf(app.maparound.routing.Maneuver(100, Turn.LEFT), app.maparound.routing.Maneuver(4999, Turn.ARRIVE)),
        )
        val w = WatchProtocol.routeOf(nav)!!
        assertTrue(w.points.size in 2..WatchProtocol.ROUTE_MAX_POINTS, "${w.points.size}")
        assertEquals(listOf(long[100]), w.turns)
        assertEquals(listOf(long[4999]), w.stops)
        assertTrue(WatchProtocol.encodeRoute(w).size < 12_000, "${WatchProtocol.encodeRoute(w).size} bytes")
    }

    @Test fun commands() {
        for (c in WatchCommand.entries) assertEquals(c, WatchProtocol.command(WatchProtocol.encode(c)))
        assertNull(WatchProtocol.command("REBOOT".encodeToByteArray()))
    }

    @Test fun idleWhenNothingRuns() {
        val s = WatchProtocol.stateOf(NavigationSession.State(), TrackRecorder.State(), 0L)
        assertNull(s.nav)
        assertNull(s.recording)
    }
}
