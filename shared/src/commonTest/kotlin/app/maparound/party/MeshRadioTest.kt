package app.maparound.party

import app.maparound.data.KeyValueStore
import app.maparound.sensors.GeoFix
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The party's LoRa radio over a platform's Bluetooth ([MeshRadio]), and the gateway between it and online. */
class MeshRadioTest {
    /** A radio: what it has to say (FromRadio messages, read in turn), and what it was sent. */
    private class FakeRadio : RadioLinks {
        val toRead = ArrayDeque<ByteArray>()
        val written = ArrayList<ByteArray>()
        var connects = 0
        var unknown = false
        var onMore: () -> Unit = {}
        var onLost: () -> Unit = {}

        override suspend fun connect(id: String, onMore: () -> Unit, onLost: () -> Unit): RadioLink {
            connects++
            if (unknown) throw RadioUnknown()
            this.onMore = onMore
            this.onLost = onLost
            return object : RadioLink {
                override suspend fun write(bytes: ByteArray) { written += bytes }
                override suspend fun read(): ByteArray = toRead.removeFirstOrNull() ?: ByteArray(0)
                override fun close() = Unit
            }
        }

        /** The party packets sent: the payloads of ToRadio{packet} on the private port. */
        fun partyPayloads(): List<ByteArray> = written.mapNotNull { toRadio ->
            val r = PbReader(toRadio)
            var payload: ByteArray? = null
            while (r.next()) {
                if (r.field != 1) { r.skip(); continue }
                val packet = r.message()
                while (packet.next()) {
                    if (packet.field != 4) { packet.skip(); continue }
                    val data = packet.message()
                    var port = -1
                    var p = ByteArray(0)
                    while (data.next()) when (data.field) {
                        1 -> port = data.varint().toInt()
                        2 -> p = data.bytes()
                        else -> data.skip()
                    }
                    if (port == Mesh.PORT_PRIVATE) payload = p
                }
            }
            payload
        }
    }

    // FromRadio messages, as a radio sends them.
    private fun myInfo(num: Long) = PbWriter().message(3, PbWriter().varint(1, num)).toByteArray()
    private fun configComplete() = PbWriter().varint(7, 42).toByteArray()
    private fun packetIn(from: Long, port: Int, payload: ByteArray) = PbWriter().message(
        2,
        PbWriter().fixed32(1, from.toInt()).fixed32(2, Mesh.BROADCAST.toInt())
            .message(4, PbWriter().varint(1, port.toLong()).bytes(2, payload)),
    ).toByteArray()

    private class Recorder : MeshRadio.Listener {
        val statuses = ArrayList<TransportStatus>()
        val packets = ArrayList<ByteArray>()
        var myNode = 0L
        override fun onStatus(status: TransportStatus) { statuses += status }
        override fun onMyNode(num: Long) { myNode = num }
        override fun onPartyPacket(payload: ByteArray) { packets += payload }
        override fun onNode(num: Long, longName: String?, shortName: String?, position: Mesh.Position?) = Unit
    }

    @Test
    fun aSessionWithTheRadio() = runTest {
        val radio = FakeRadio()
        val heard = Recorder()
        val r = MeshRadio(radio, "radio-1", "T-Echo", heard, backgroundScope)
        r.send(byteArrayOf(1)) // before it's there: kept
        radio.toRead += listOf(myInfo(0x1234), configComplete())
        r.start()
        testScheduler.runCurrent()
        // The session: want_config first, then what was kept.
        assertEquals(3, PbReader(radio.written.first()).let { it.next(); it.field })
        assertEquals(TransportStatus.State.CONNECTED, heard.statuses.last().state)
        assertEquals(0x1234, heard.myNode)
        assertEquals(listOf(1.toByte()), radio.partyPayloads().single().toList())
        assertTrue(r.isReady)

        // The radio says there's more: a party packet, and another radio's text (not ours).
        radio.toRead += listOf(packetIn(0x99, Mesh.PORT_PRIVATE, byteArrayOf(7, 7)), packetIn(0x99, Mesh.PORT_TEXT, byteArrayOf(9)))
        radio.onMore()
        testScheduler.runCurrent()
        assertEquals(listOf<Byte>(7, 7), heard.packets.single().toList())

        // Out of range: connecting again after a pause.
        radio.onLost()
        testScheduler.runCurrent()
        assertEquals(TransportStatus.State.CONNECTING, heard.statuses.last().state)
        assertEquals(1, radio.connects)
        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()
        assertEquals(2, radio.connects)
        r.stop()
    }

    @Test
    fun aRadioTheBrowserForgot() = runTest {
        val radio = FakeRadio().apply { unknown = true }
        val heard = Recorder()
        MeshRadio(radio, "radio-1", "T-Echo", heard, backgroundScope).start()
        testScheduler.runCurrent()
        assertEquals(TransportStatus.State.ERROR, heard.statuses.last().state)
    }

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

    private class Broker : MqttSockets {
        val sent = ArrayList<ByteArray>()
        var onOpen: () -> Unit = {}
        var onData: (ByteArray) -> Unit = {}
        override fun open(url: String, onOpen: () -> Unit, onData: (ByteArray) -> Unit, onClose: (String) -> Unit): MqttSocket {
            this.onOpen = onOpen
            this.onData = onData
            return object : MqttSocket {
                override fun send(bytes: ByteArray) { sent += bytes }
                override fun close() = Unit
            }
        }
        fun accept() {
            onOpen()
            onData(byteArrayOf(0x20, 2, 0, 0))
            onData(byteArrayOf(0x90.toByte(), 3, 0, 1, 0))
        }
        fun publishes() = sent.flatMap { MqttWire.Reader().feed(it) }.filterIsInstance<MqttWire.Packet.Publish>()
    }

    private suspend fun TestScope.partyWithRadio(radio: FakeRadio, broker: Broker): OnlineParty {
        val party = OnlineParty(MemoryStore(), broker, locations = { MutableSharedFlow<GeoFix>() }, scope = backgroundScope, clock = { 1_800_000_000_000L }, radios = radio)
        party.create("Off-grid", "Dana")
        broker.accept()
        radio.toRead += configComplete()
        party.setRadio("radio-1", "T-Echo")
        testScheduler.runCurrent()
        return party
    }

    @Test
    fun theGatewayRelaysBetweenOnlineAndTheRadio() = runTest {
        val radio = FakeRadio()
        val broker = Broker()
        val party = partyWithRadio(radio, broker)
        assertTrue(party.hasRadio)
        val key = party.state.value.party!!
        assertEquals(TransportStatus.State.CONNECTED, party.state.value.radio.state)
        // Our hello went both ways.
        assertTrue(radio.partyPayloads().any { (Envelope.decode(key, it)?.message as? PartyMessage.Hello)?.name == "Dana" })

        // Someone online: on to the radio, unchanged.
        val online = Envelope.encode(key, 0x11, 1, 1_800_000_000_000L, PartyMessage.Text("from online"))
        broker.onData(MqttWire.publish("${PartyRelays.ROOT}/${key.tagHex}/00000011/msg", online, retained = false))
        testScheduler.runCurrent()
        assertTrue(radio.partyPayloads().any { it.contentEquals(online) })

        // Someone on the radio: on online, under their own topic.
        val offGrid = Envelope.encode(key, 0x22, 1, 1_800_000_000_000L, PartyMessage.Text("from the radio"))
        radio.toRead += packetIn(0x5555, Mesh.PORT_PRIVATE, offGrid)
        radio.onMore()
        testScheduler.runCurrent()
        val relayed = broker.publishes().last()
        assertEquals("${PartyRelays.ROOT}/${key.tagHex}/00000022/msg", relayed.topic)
        assertTrue(relayed.payload.contentEquals(offGrid))
        assertEquals(2, party.state.value.chat.size)

        // The gateway off: nothing more relayed.
        party.setGateway(false)
        val count = radio.written.size
        broker.onData(MqttWire.publish("${PartyRelays.ROOT}/${key.tagHex}/00000011/msg", Envelope.encode(key, 0x11, 2, 1_800_000_000_000L, PartyMessage.Text("again")), retained = false))
        testScheduler.runCurrent()
        assertEquals(count, radio.written.size)
    }
}
