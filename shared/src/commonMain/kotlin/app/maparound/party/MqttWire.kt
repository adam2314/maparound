package app.maparound.party

// Plain Kotlin: where the party meets online, and the few MQTT 3.1.1 packets the web app needs
// to watch a party over a WebSocket (it only listens). Unit-tested.

/**
 * The public MQTT brokers that relay party packets (end-to-end encrypted). Everyone tries them
 * in this order, so the members (and watchers) end up on the same one.
 * Topics: `maparound/v1/<party tag>/<sender>/<kind>`.
 */
object PartyRelays {
    const val ROOT = "maparound/v1"

    /** For the apps: MQTT over TLS. Free public brokers; fine for testing, use your own server for real groups. */
    val MQTT_TLS = listOf(
        "ssl://broker.emqx.io:8883",
        "ssl://test.mosquitto.org:8886",
        "ssl://broker.hivemq.com:8883",
    )

    /** For the web app: the same brokers, MQTT over secure WebSockets. */
    val WEBSOCKET = listOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://test.mosquitto.org:8081/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
    )

    /** Everything sent in the party with [tagHex] ([PartyKey.tagHex]). */
    fun allOf(tagHex: String) = "$ROOT/$tagHex/+/+"

    /** The host of a broker's address, for the screens. */
    fun host(uri: String) = uri.substringAfter("://").substringBefore(':').substringBefore('/')
}

/** MQTT 3.1.1 packets: connecting, subscribing and keeping alive out; what the broker sends in. */
object MqttWire {
    /** CONNECT with a clean session and no will, user or password. */
    fun connect(clientId: String, keepAliveSeconds: Int): ByteArray =
        packet(0x10, str("MQTT") + byteArrayOf(4, 0x02) + u16(keepAliveSeconds) + str(clientId))

    fun subscribe(packetId: Int, filter: String, qos: Int = 0): ByteArray =
        packet(0x82, u16(packetId) + str(filter) + byteArrayOf(qos.toByte()))

    /** The answer to a QoS 1 PUBLISH. */
    fun pubAck(packetId: Int): ByteArray = packet(0x40, u16(packetId))

    val PINGREQ: ByteArray get() = byteArrayOf(0xC0.toByte(), 0)
    val DISCONNECT: ByteArray get() = byteArrayOf(0xE0.toByte(), 0)

    sealed class Packet {
        /** [code] 0: accepted. */
        data class ConnAck(val code: Int) : Packet()

        /** One code per filter: the QoS granted, or 0x80 for a refusal. */
        data class SubAck(val packetId: Int, val codes: List<Int>) : Packet()

        /** [retained]: sent because it was kept on the broker (the latest state), not just now. */
        class Publish(val topic: String, val payload: ByteArray, val qos: Int, val retained: Boolean, val packetId: Int?) : Packet()

        object PingResp : Packet()

        data class Other(val type: Int) : Packet()
    }

    /** Takes the stream as it comes (a WebSocket message may hold part of a packet, or several). */
    class Reader(private val maxPacket: Int = 1 shl 20) {
        private var buf = ByteArray(0)

        /** The packets completed by [bytes]. Throws on a malformed or too big packet. */
        fun feed(bytes: ByteArray): List<Packet> {
            buf += bytes
            val out = ArrayList<Packet>()
            var at = 0
            while (buf.size - at >= 2) {
                // The remaining length: 1 to 4 bytes, 7 bits each, least significant first.
                var length = 0
                var shift = 0
                var i = at + 1
                var done = false
                while (i < buf.size && i <= at + 4) {
                    val b = buf[i++].toInt() and 0xFF
                    length = length or ((b and 0x7F) shl shift)
                    shift += 7
                    if (b and 0x80 == 0) {
                        done = true
                        break
                    }
                }
                if (!done) {
                    if (i > at + 4) throw IllegalStateException("Malformed MQTT packet length")
                    break // the rest of the length hasn't come yet
                }
                if (length > maxPacket) throw IllegalStateException("MQTT packet too big ($length bytes)")
                if (buf.size - i < length) break
                out += parse(buf[at].toInt() and 0xFF, buf.copyOfRange(i, i + length))
                at = i + length
            }
            buf = buf.copyOfRange(at, buf.size)
            return out
        }
    }

    private fun parse(header: Int, body: ByteArray): Packet = when (header ushr 4) {
        2 -> Packet.ConnAck(if (body.size >= 2) body[1].toInt() and 0xFF else -1)
        3 -> {
            val qos = (header ushr 1) and 3
            val topicLength = u16(body, 0)
            val topic = body.decodeToString(2, 2 + topicLength)
            var at = 2 + topicLength
            val id = if (qos > 0) u16(body, at).also { at += 2 } else null
            Packet.Publish(topic, body.copyOfRange(at, body.size), qos, header and 1 == 1, id)
        }
        9 -> Packet.SubAck(u16(body, 0), (2 until body.size).map { body[it].toInt() and 0xFF })
        13 -> Packet.PingResp
        else -> Packet.Other(header ushr 4)
    }

    private fun packet(header: Int, body: ByteArray): ByteArray {
        val length = ArrayList<Byte>()
        var n = body.size
        do {
            var b = n and 0x7F
            n = n ushr 7
            if (n > 0) b = b or 0x80
            length += b.toByte()
        } while (n > 0)
        return byteArrayOf(header.toByte()) + length.toByteArray() + body
    }

    private fun str(s: String): ByteArray {
        val b = s.encodeToByteArray()
        require(b.size <= 0xFFFF)
        return u16(b.size) + b
    }

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
}
