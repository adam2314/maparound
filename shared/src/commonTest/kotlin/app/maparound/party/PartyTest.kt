package app.maparound.party

import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.Test

class PartyTest {
    private val key = PartyKey.create("Desert run")

    @Test
    fun linkRoundTrip() {
        val link = key.toLink()
        assertTrue(link.startsWith("maparound://party/"))
        val back = PartyKey.parse(link)!!
        assertTrue(back.secret.contentEquals(key.secret))
        assertEquals("Desert run", back.name)
        assertEquals(key.tag, back.tag)
        assertNull(PartyKey.parse("maparound://party/nonsense"))
    }

    @Test
    fun positionIs54BytesAndRoundTrips() {
        val pos = PartyMessage.Position(31.7681234, 35.1584567, 721, 8, 63, 271, 84, sos = false, driving = true)
        val bytes = Envelope.encode(key, 0x1234, 7, 1_700_000_000_000, pos)
        assertEquals(54, bytes.size)
        val r = Envelope.decode(key, bytes)!!
        assertEquals(0x1234, r.sender)
        assertEquals(7L, r.seq)
        assertEquals(1_700_000_000_000, r.sentAt)
        val p = r.message as PartyMessage.Position
        assertEquals(31.7681234, p.lat, 1e-7)
        assertEquals(35.1584567, p.lon, 1e-7)
        assertEquals(721, p.altM)
        assertEquals(63, p.speedKmh)
        assertEquals(270.0, p.headingDeg!!.toDouble(), 2.0)
        assertTrue(p.driving)
    }

    @Test
    fun otherPartiesAndTamperingAreRejected() {
        val bytes = Envelope.encode(key, 1, 1, 0, PartyMessage.Text("hi"))
        assertNull(Envelope.decode(PartyKey.create("other"), bytes))
        val forged = bytes.clone().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(Envelope.decode(key, forged))
        val reSent = bytes.clone().also { it[10] = 9 } // changed sequence number (authenticated header)
        assertNull(Envelope.decode(key, reSent))
    }

    @Test
    fun allMessageTypesRoundTrip() {
        val msgs = listOf(
            PartyMessage.Hello("Adam ⛰ the long name that is cut", 3, 0xDEADBEEFL),
            PartyMessage.Sos(true, "Stuck in sand"),
            PartyMessage.Meet(31.5, 35.2, "Camp"),
            PartyMessage.Text("Fuel stop in 10"),
            PartyMessage.Leave,
            PartyMessage.Position(-33.9, 18.4, null, null, null, null, null, sos = true),
        )
        for (m in msgs) {
            val back = Envelope.decode(key, Envelope.encode(key, 5, 1, 0, m))!!.message
            if (m is PartyMessage.Hello) {
                back as PartyMessage.Hello
                assertTrue(back.name.encodeToByteArray().size <= 24)
                assertEquals(0xDEADBEEFL, back.meshNode)
            } else if (m is PartyMessage.Position) {
                back as PartyMessage.Position
                assertNull(back.altM)
                assertNull(back.batteryPct)
                assertTrue(back.sos)
            } else {
                assertEquals(m, back)
            }
        }
    }

    @Test
    fun engineDedupesAcrossTransportsAndRaisesEvents() {
        var now = 1_000_000L
        val engine = PartyEngine(key, myId = 1) { now }
        val hello = Envelope.encode(key, 2, 1, now, PartyMessage.Hello("Dana", 1, 0))
        val joined = engine.receive(hello, Via.ONLINE)!!
        assertTrue(joined.events.first() is PartyEvent.Joined)

        val pos = Envelope.encode(key, 2, 2, now, PartyMessage.Position(31.0, 35.0, 100, 5, 0, null, 90))
        assertNotNull(engine.receive(pos, Via.LORA))
        now += 500
        assertNull("same packet via another transport is a duplicate", engine.receive(pos, Via.ONLINE))
        assertEquals(setOf(Via.ONLINE, Via.LORA), engine.members[2]!!.heard.keys)

        assertNull("own packets are ignored", engine.receive(Envelope.encode(key, 1, 9, now, PartyMessage.Text("me")), Via.ONLINE))

        // An older position arriving late must not move the member back.
        val older = Envelope.encode(key, 2, 3, now - 60_000, PartyMessage.Position(30.0, 34.0, null, null, null, null, null))
        engine.receive(older, Via.LORA)
        assertEquals(31.0, engine.members[2]!!.position!!.lat, 1e-9)

        val sos = engine.receive(Envelope.encode(key, 2, 4, now, PartyMessage.Sos(true, "flat tyre")), Via.LORA)!!
        assertTrue(sos.events.any { it is PartyEvent.SosRaised })
        assertTrue(engine.members[2]!!.sos)
    }

    @Test
    fun gatewayRelaysPositionsAtTheRadioPace() {
        var now = 0L
        val engine = PartyEngine(key, myId = 1) { now }
        fun posAt(seq: Long) = engine.receive(
            Envelope.encode(key, 7, seq, now, PartyMessage.Position(31.0, 35.0, null, null, 60, null, null, driving = true)),
            Via.ONLINE,
        )!!
        assertTrue(engine.shouldForward(posAt(1), Via.ONLINE, Via.LORA))
        now += 10_000
        assertFalse("online sends every 5-10 s; radio relays once a minute", engine.shouldForward(posAt(2), Via.ONLINE, Via.LORA))
        now += 60_000
        assertTrue(engine.shouldForward(posAt(3), Via.ONLINE, Via.LORA))
        val text = engine.receive(Envelope.encode(key, 7, 4, now, PartyMessage.Text("ok")), Via.ONLINE)!!
        assertTrue(engine.shouldForward(text, Via.ONLINE, Via.LORA))
    }

    /**
     * Three phones: Online-only (in town), Radio-only (off-grid jeep) and a Gateway with both.
     * Each forwards like PartySession does; packets must cross over exactly once, without loops.
     */
    @Test
    fun gatewayBridgesOnlineAndRadioWithoutLoops() {
        var now = 0L
        val town = PartyEngine(key, 10) { now }
        val jeep = PartyEngine(key, 20) { now }
        val gateway = PartyEngine(key, 30) { now }
        val onlineBus = mutableListOf<ByteArray>()
        val radioBus = mutableListOf<ByteArray>()
        var delivered = 0

        // Deliver everything on both "airwaves" until quiet; the gateway relays what it accepts.
        fun pump() {
            repeat(5) {
                val online = onlineBus.toList().also { onlineBus.clear() }
                val radio = radioBus.toList().also { radioBus.clear() }
                for (b in online) {
                    town.receive(b, Via.ONLINE)?.let { delivered++ }
                    gateway.receive(b, Via.ONLINE)?.let { acc -> if (gateway.shouldForward(acc, Via.ONLINE, Via.LORA)) radioBus += b }
                }
                for (b in radio) {
                    jeep.receive(b, Via.LORA)?.let { delivered++ }
                    gateway.receive(b, Via.LORA)?.let { acc -> if (gateway.shouldForward(acc, Via.LORA, Via.ONLINE)) onlineBus += b }
                }
            }
        }

        radioBus += Envelope.encode(key, 20, 1, now, PartyMessage.Position(31.1, 35.1, null, null, 50, null, null, driving = true))
        onlineBus += Envelope.encode(key, 10, 1, now, PartyMessage.Text("where are you?"))
        pump()
        assertEquals("jeep seen in town via gateway", 31.1, town.members[20]!!.position!!.lat, 1e-9)
        assertEquals(Via.ONLINE, town.members[20]!!.heard.keys.single())
        assertEquals("town's text reached the jeep over radio", "where are you?", jeep.chat.single().text)
        assertTrue("no loops or duplicates", onlineBus.isEmpty() && radioBus.isEmpty())
        assertEquals(2, delivered)
    }

    @Test
    fun sendPolicyAdaptsToSpeedAndTransport() {
        val start = SendPolicy.Last(0, 31.0, 35.0)
        // Hiking, moved 50 m: online after 10 s, radio only after 2 min.
        assertTrue(SendPolicy.due(Via.ONLINE, start, 10_000, 31.00045, 35.0, 4.0, false))
        assertFalse(SendPolicy.due(Via.LORA, start, 60_000, 31.00045, 35.0, 4.0, false))
        assertTrue(SendPolicy.due(Via.LORA, start, 120_000, 31.00045, 35.0, 4.0, false))
        // Driving 60 km/h, 1 km in a minute: radio every 45 s.
        assertTrue(SendPolicy.due(Via.LORA, start, 45_000, 31.009, 35.0, 60.0, false))
        // Standing still: heartbeat only.
        assertFalse(SendPolicy.due(Via.LORA, start, 300_000, 31.0, 35.0, 0.0, false))
        assertTrue(SendPolicy.due(Via.LORA, start, 600_000, 31.0, 35.0, 0.0, false))
    }
}
