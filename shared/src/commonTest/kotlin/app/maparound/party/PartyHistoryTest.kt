package app.maparound.party

import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import app.maparound.ui.PartyReplay
import kotlin.math.abs
import kotlin.test.Test

class PartyHistoryTest {
    private val key = PartyKey.create("Night hike")
    private val t0 = 1_800_000_000_000L

    private fun pos(lat: Double, lon: Double = 35.0, alt: Int? = 300, speed: Int? = 4) =
        PartyMessage.Position(lat, lon, alt, 6, speed, 90, 77)

    @Test
    fun messagesAreInTheOrderTheyWereWritten() {
        val engine = PartyEngine(key, myId = 1) { t0 }
        engine.receive(Envelope.encode(key, 2, 1, t0 + 60_000, PartyMessage.Text("second")), Via.ONLINE)
        // Written before, arrived after (by radio).
        engine.receive(Envelope.encode(key, 3, 1, t0, PartyMessage.Text("first")), Via.LORA)
        engine.applyOwn(PartyMessage.Text("mine, between"), t0 + 30_500)
        engine.receive(Envelope.encode(key, 2, 2, t0 + 90_000, PartyMessage.Text("third")), Via.ONLINE)
        assertEquals(listOf("first", "mine, between", "second", "third"), engine.chat.map { it.text })
        // Each with when it was written (in whole seconds, as they travel).
        assertEquals(listOf(t0, t0 + 30_000, t0 + 60_000, t0 + 90_000), engine.chat.map { it.at })
        // The same moment: as they came.
        engine.receive(Envelope.encode(key, 3, 2, t0 + 90_000, PartyMessage.Text("same time")), Via.ONLINE)
        assertEquals("same time", engine.chat.last().text)
    }

    @Test
    fun positionsGoInTheHistoryInTheirPlace() {
        val engine = PartyEngine(key, myId = 1) { t0 }
        engine.receive(Envelope.encode(key, 2, 1, t0, pos(31.00)), Via.ONLINE)
        engine.receive(Envelope.encode(key, 2, 2, t0 + 20_000, pos(31.02)), Via.ONLINE)
        // An older one late, by radio: in the history in its place, the marker stays on the newest.
        engine.receive(Envelope.encode(key, 2, 3, t0 + 10_000, pos(31.01)), Via.LORA)
        assertEquals(listOf(31.00, 31.01, 31.02), engine.history.of(2).map { it.position.lat })
        assertEquals(31.02, engine.members[2]!!.position!!.lat)
        // The same position again (relayed, or kept by the broker): once.
        engine.receive(Envelope.encode(key, 2, 4, t0 + 20_000, pos(31.02)), Via.ONLINE)
        assertEquals(3, engine.history.of(2).size)
        assertEquals(mapOf(2 to 3), engine.history.counts)
    }

    @Test
    fun aPositionWithoutAGoodFixIsASignOfLifeNotAPlace() {
        val engine = PartyEngine(key, myId = 1) { t0 }
        // Wi-Fi or cell towers only: ±60 m. Not on the map, nor in the history.
        val rough = PartyMessage.Position(31.10, 35.10, 300, 60, 0, null, 55)
        engine.receive(Envelope.encode(key, 2, 1, t0, rough), Via.ONLINE)
        var m = engine.members[2]!!
        assertNull(m.position)
        assertTrue(m.noFixNow)
        assertEquals(55, m.report!!.batteryPct)
        assertEquals(0, engine.history.of(2).size)
        // A good fix (±8 m) is plotted.
        engine.receive(Envelope.encode(key, 2, 2, t0 + 10_000, pos(31.20).copy(accuracyM = 8)), Via.ONLINE)
        m = engine.members[2]!!
        assertEquals(31.20, m.position!!.lat)
        assertFalse(m.noFixNow)
        // Then the fix is lost: the marker stays where it was, and an SOS still comes through.
        engine.receive(Envelope.encode(key, 2, 3, t0 + 20_000, rough.copy(sos = true, batteryPct = 50)), Via.ONLINE)
        m = engine.members[2]!!
        assertEquals(31.20, m.position!!.lat)
        assertTrue(m.noFixNow)
        assertTrue(m.sos)
        assertEquals(50, m.report!!.batteryPct)
        assertEquals(listOf(31.20), engine.history.of(2).map { it.position.lat })
        // Accuracy unknown (older versions): shown.
        assertTrue(PartyMessage.Position(31.0, 35.0, null, null, null, null, null).isGoodFix)
    }

    @Test
    fun theHistoryKeepsItsNewestAndGoesToAFileAndBack() {
        val h = PartyHistory(maxPerMember = 3)
        for (i in 0 until 5) assertTrue(h.add(7, t0 + i * 1000L, pos(31.0 + i * 0.001)))
        assertEquals(listOf(t0 + 2000, t0 + 3000, t0 + 4000), h.of(7).map { it.at })
        assertFalse("older than all, and full", h.add(7, t0, pos(30.0)))
        h.add(9, t0, PartyMessage.Position(-33.9, 151.2, null, null, null, null, null, sos = true, driving = true))
        val back = PartyHistory.decode(h.encode(key.tag), key.tag)!!
        assertEquals(h.of(7), back.of(7))
        assertEquals(h.of(9), back.of(9))
        assertNull(PartyHistory.decode(h.encode(key.tag), PartyKey.create("Another").tag))
        assertNull(PartyHistory.decode(byteArrayOf(1, 2, 3), key.tag))
    }

    @Test
    fun aReplayGoesAlongTheWaySkippingLongStops() {
        val points = listOf(
            HistoryPoint(t0, pos(31.000)),
            HistoryPoint(t0 + 60_000, pos(31.010)),
            // An hour's stop: skipped while playing.
            HistoryPoint(t0 + 3_660_000, pos(31.020)),
        )
        PartyReplay.open(Member(5, name = "Noa"), points)
        val s = PartyReplay.state.value!!
        assertTrue(s.playing)
        assertEquals(t0, s.t)
        // Halfway between the first two: halfway there.
        PartyReplay.seek(t0 + 30_000)
        assertTrue(abs(PartyReplay.state.value!!.where.lat - 31.005) < 1e-9)
        assertFalse(PartyReplay.state.value!!.playing)
        val gone = PartyReplay.state.value!!.distance
        assertTrue("$gone", abs(gone - 556) < 5) // half of 0.01° of latitude
        // Playing past the second position: the stop is skipped, then the end.
        PartyReplay.play(true)
        PartyReplay.setSpeed(60)
        PartyReplay.advance(1_000) // 60 s on: at the second position
        PartyReplay.advance(10)
        assertEquals(t0 + 3_660_000, PartyReplay.state.value!!.t)
        assertFalse("stops at the end", PartyReplay.state.value!!.playing)
        // Again: from the start.
        PartyReplay.play(true)
        assertEquals(t0, PartyReplay.state.value!!.t)
        PartyReplay.close()
        assertNull(PartyReplay.state.value)
    }
}
