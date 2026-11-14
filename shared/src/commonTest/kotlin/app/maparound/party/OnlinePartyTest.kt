package app.maparound.party

import app.maparound.data.KeyValueStore
import app.maparound.sensors.GeoFix
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The party online as a full member (the web app's), against a broker played here. */
class OnlinePartyTest {
    private class MemoryStore : KeyValueStore {
        val m = HashMap<String, Any>()
        override fun contains(key: String) = key in m
        override fun getString(key: String) = m[key] as String?
        override fun getBoolean(key: String, default: Boolean) = m[key] as Boolean? ?: default
        override fun getFloat(key: String, default: Float) = m[key] as Float? ?: default
        override fun getInt(key: String, default: Int) = m[key] as Int? ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putBoolean(key: String, value: Boolean) { m[key] = value }
        override fun putFloat(key: String, value: Float) { m[key] = value }
        override fun putInt(key: String, value: Int) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String) = m[key] as Set<String>?
        override fun putStringSet(key: String, value: Set<String>) { m[key] = value }
    }

    /** One broker: what's opened and sent, and the callbacks to answer with. */
    private class Broker : MqttSockets {
        val urls = ArrayList<String>()
        val sent = ArrayList<ByteArray>()
        var onOpen: () -> Unit = {}
        var onData: (ByteArray) -> Unit = {}

        override fun open(url: String, onOpen: () -> Unit, onData: (ByteArray) -> Unit, onClose: (String) -> Unit): MqttSocket {
            urls += url
            this.onOpen = onOpen
            this.onData = onData
            return object : MqttSocket {
                override fun send(bytes: ByteArray) { sent += bytes }
                override fun close() = Unit
            }
        }

        /** Answers the connection and the subscription. */
        fun accept() {
            onOpen()
            onData(byteArrayOf(0x20, 2, 0, 0)) // CONNACK, accepted
            onData(byteArrayOf(0x90.toByte(), 3, 0, 1, 0)) // SUBACK
        }

        fun publishes(): List<MqttWire.Packet.Publish> =
            sent.flatMap { MqttWire.Reader().feed(it) }.filterIsInstance<MqttWire.Packet.Publish>()
    }

    private fun fix(lat: Double, lon: Double, at: Long) =
        GeoFix(lat, lon, 1500.0, 5f, 1.2f, null, 90f, at, at, true)

    @Test
    fun aFullMemberOnline() = runTest {
        var now = 1_800_000_000_000L
        val store = MemoryStore()
        val broker = Broker()
        val fixes = MutableSharedFlow<GeoFix>(extraBufferCapacity = 8)
        val party = OnlineParty(store, broker, locations = { fixes }, battery = { 80 }, scope = backgroundScope, clock = { now })
        assertFalse(party.watchOnly)
        assertFalse(party.hasRadio)

        party.create("Hike", "Dana")
        val s = party.state.value
        val key = assertNotNull(s.party)
        val me = assertNotNull(s.me)
        assertEquals("Dana", me.name)
        assertTrue(s.active)
        assertEquals(listOf(PartyRelays.WEBSOCKET[0]), broker.urls)

        // Connected: our name, kept on the broker under our own topic.
        broker.accept()
        assertEquals(TransportStatus.State.CONNECTED, party.state.value.online.state)
        val base = "${PartyRelays.ROOT}/${key.tagHex}/${OnlineParty.senderHex(me.id)}"
        val hello = broker.publishes().single()
        assertEquals("$base/hello", hello.topic)
        assertTrue(hello.retained)
        val h = assertNotNull(Envelope.decode(key, hello.payload)).message as PartyMessage.Hello
        assertEquals("Dana", h.name)

        // Our position, with the battery.
        testScheduler.runCurrent()
        fixes.emit(fix(47.1, 9.5, now))
        testScheduler.runCurrent()
        val pos = broker.publishes().last()
        assertEquals("$base/pos", pos.topic)
        assertTrue(pos.retained)
        val p = assertNotNull(Envelope.decode(key, pos.payload)).message as PartyMessage.Position
        assertEquals(47.1, p.lat, 1e-5)
        assertEquals(80, p.batteryPct)
        assertEquals(47.1, party.state.value.myPosition?.lat)

        // Someone else: their hello and a message.
        val ari = 0x12345678
        fun fromAri(seq: Long, m: PartyMessage, retained: Boolean = false) =
            broker.onData(MqttWire.publish("${PartyRelays.ROOT}/${key.tagHex}/${OnlineParty.senderHex(ari)}/x", Envelope.encode(key, ari, seq, now, m), retained))
        fromAri(1, PartyMessage.Hello("Ari", PartyPalette.PRESETS[3], 0, 0), retained = true)
        fromAri(2, PartyMessage.Text("Where are you?"))
        assertEquals(listOf("Ari"), party.state.value.members.map { it.name })
        assertEquals("Where are you?", party.state.value.chat.single().text)

        // Messages aren't kept; SOS is, and the position goes again with it.
        party.sendText("At the hut")
        val msg = broker.publishes().last()
        assertEquals("$base/msg", msg.topic)
        assertFalse(msg.retained)
        assertEquals(2, party.state.value.chat.size)
        now += 2_000
        party.sos(true, "Twisted ankle")
        val last2 = broker.publishes().takeLast(2)
        assertEquals(listOf("$base/sos", "$base/pos"), last2.map { it.topic })
        assertTrue((Envelope.decode(key, last2[1].payload)!!.message as PartyMessage.Position).sos)

        // Ghost: the party on, our position not sent.
        party.sos(false)
        party.setSharing(false)
        val count = broker.publishes().size
        now += 120_000
        fixes.emit(fix(47.2, 9.6, now))
        testScheduler.runCurrent()
        assertEquals(count, broker.publishes().size)
        assertEquals(47.2, party.state.value.myPosition?.lat)

        // Leaving: said, and our kept state cleared from the broker.
        party.leave()
        val after = broker.publishes().drop(count)
        assertTrue(Envelope.decode(key, after[0].payload)!!.message == PartyMessage.Leave)
        assertEquals(listOf("pos", "hello", "sos", "meet"), after.drop(1).map { it.topic.substringAfterLast('/') })
        assertTrue(after.drop(1).all { it.retained && it.payload.isEmpty() })
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertNull(party.state.value.party)
        assertNull(store.getString("link"))
    }

    @Test
    fun backAfterTheAppIsReopened() = runTest {
        val store = MemoryStore()
        val first = OnlineParty(store, Broker(), locations = { MutableSharedFlow() }, scope = backgroundScope)
        first.create("Hike", "Dana")
        val me = first.state.value.me!!

        // Reopened: the same party, the same us, on again, the sequence numbers further on.
        val broker = Broker()
        val again = OnlineParty(store, broker, locations = { MutableSharedFlow() }, scope = backgroundScope)
        assertEquals(me, again.state.value.me)
        assertEquals("Hike", again.state.value.party?.name)
        assertTrue(again.state.value.active)
        broker.accept()
        val hello = Envelope.decode(again.state.value.party!!, broker.publishes().single().payload)!!
        assertTrue(hello.seq > 500, "${hello.seq}")
    }

    @Test
    fun aWatchedPartyAsksToJoin() = runTest {
        // The web app only watched before: the invite was kept without us in it.
        val store = MemoryStore()
        val key = PartyKey.create("Hike")
        store.putString("link", key.toLink())
        store.putBoolean("active", true)
        val party = OnlineParty(store, Broker(), locations = { MutableSharedFlow() }, scope = backgroundScope)
        assertNull(party.state.value.party)
        assertEquals("Hike", party.pendingInvite.value?.name)
    }
}
