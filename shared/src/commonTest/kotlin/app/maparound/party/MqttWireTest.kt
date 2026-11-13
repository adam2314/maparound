package app.maparound.party

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MqttWireTest {
    private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun packetsOut() {
        assertEquals("10 0f 00 04 4d 51 54 54 04 02 00 3c 00 03 61 62 63", MqttWire.connect("abc", 60).hex())
        assertEquals("82 08 00 01 00 03 61 2f 62 00", MqttWire.subscribe(1, "a/b").hex())
        assertEquals("40 02 01 02", MqttWire.pubAck(0x0102).hex())
        assertEquals("c0 00", MqttWire.PINGREQ.hex())
        assertEquals("e0 00", MqttWire.DISCONNECT.hex())
        // A body over 127 bytes takes two length bytes.
        val long = MqttWire.connect("x".repeat(200), 60)
        assertEquals("10 d4 01", long.copyOfRange(0, 3).hex())
        assertEquals(3 + 212, long.size)
    }

    /** A PUBLISH as a broker sends it. */
    private fun publish(topic: String, payload: ByteArray, qos: Int = 0, retained: Boolean = false, id: Int = 7): ByteArray {
        val t = topic.encodeToByteArray()
        val body = byteArrayOf((t.size ushr 8).toByte(), t.size.toByte()) + t +
            (if (qos > 0) byteArrayOf((id ushr 8).toByte(), id.toByte()) else ByteArray(0)) + payload
        val length = if (body.size < 128) byteArrayOf(body.size.toByte()) else byteArrayOf(((body.size and 0x7F) or 0x80).toByte(), (body.size ushr 7).toByte())
        return byteArrayOf((0x30 or (qos shl 1) or (if (retained) 1 else 0)).toByte()) + length + body
    }

    @Test
    fun readsWhateverWayThePacketsArrive() {
        val payload = ByteArray(300) { it.toByte() }
        val stream = byteArrayOf(0x20, 2, 0, 0) + // CONNACK, accepted
            byteArrayOf(0x90.toByte(), 3, 0, 1, 0) + // SUBACK, QoS 0 granted
            publish("maparound/v1/0a0b0c0d/00000001/pos", payload, retained = true) +
            publish("a/b", byteArrayOf(1, 2, 3), qos = 1, id = 0x0203) +
            byteArrayOf(0xD0.toByte(), 0) // PINGRESP

        fun check(packets: List<MqttWire.Packet>) {
            assertEquals(5, packets.size)
            assertEquals(MqttWire.Packet.ConnAck(0), packets[0])
            assertEquals(MqttWire.Packet.SubAck(1, listOf(0)), packets[1])
            val p = packets[2] as MqttWire.Packet.Publish
            assertEquals("maparound/v1/0a0b0c0d/00000001/pos", p.topic)
            assertContentEquals(payload, p.payload)
            assertTrue(p.retained)
            assertEquals(null, p.packetId)
            val q = packets[3] as MqttWire.Packet.Publish
            assertEquals(1, q.qos)
            assertEquals(0x0203, q.packetId)
            assertContentEquals(byteArrayOf(1, 2, 3), q.payload)
            assertEquals(MqttWire.Packet.PingResp, packets[4])
        }

        // All at once, a byte at a time, and in uneven pieces.
        check(MqttWire.Reader().feed(stream))
        MqttWire.Reader().let { r -> check(stream.flatMap { r.feed(byteArrayOf(it)) }) }
        MqttWire.Reader().let { r -> check(stream.toList().chunked(7).flatMap { r.feed(it.toByteArray()) }) }
    }

    @Test
    fun refusesMalformedAndHugePackets() {
        assertFailsWith<IllegalStateException> { MqttWire.Reader().feed(byteArrayOf(0x30, -1, -1, -1, -1, 1)) }
        assertFailsWith<IllegalStateException> { MqttWire.Reader(maxPacket = 100).feed(publish("t", ByteArray(200))) }
    }

    @Test
    fun relays() {
        assertEquals(PartyRelays.MQTT_TLS.map(PartyRelays::host), PartyRelays.WEBSOCKET.map(PartyRelays::host))
        assertEquals("maparound/v1/0a0b0c0d/+/+", PartyRelays.allOf("0a0b0c0d"))
        assertEquals("broker.emqx.io", PartyRelays.host("wss://broker.emqx.io:8084/mqtt"))
    }
}
