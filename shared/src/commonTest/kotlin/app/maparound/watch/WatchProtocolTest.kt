package app.maparound.watch

import app.maparound.routing.NavigationSession
import app.maparound.routing.Turn
import app.maparound.tracks.TrackRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        )
        assertEquals(s, WatchProtocol.decode(WatchProtocol.encode(s)))
        // Nothing going on: just the language.
        val idle = WatchState("en")
        assertEquals(idle, WatchProtocol.decode(WatchProtocol.encode(idle)))
        assertNull(WatchProtocol.decode("not json".encodeToByteArray()))
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
