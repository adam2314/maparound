package app.maparound.party

import app.maparound.shared.util.ByteWriter

// Minimal Protocol Buffers codec for the few Meshtastic messages MapAround needs to talk to a
// Meshtastic LoRa radio over Bluetooth. Field numbers follow meshtastic/protobufs mesh.proto
// (GPL-3.0). Plain Kotlin, unit-tested against bytes made by the official Python library.

/** Protobuf wire-format writer. */
class PbWriter {
    private val out = ByteWriter()

    fun varint(field: Int, v: Long) = apply { tag(field, 0); rawVarint(v) }
    fun bool(field: Int, v: Boolean) = varint(field, if (v) 1 else 0)
    fun fixed32(field: Int, v: Int) = apply { tag(field, 5); le32(v) }
    fun bytes(field: Int, v: ByteArray) = apply { tag(field, 2); rawVarint(v.size.toLong()); out.bytes(v) }
    fun message(field: Int, w: PbWriter) = bytes(field, w.toByteArray())
    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())
    private fun le32(v: Int) {
        for (i in 0 until 4) out.byte((v ushr (8 * i)) and 0xFF)
    }
    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.byte(v.toInt())
                return
            }
            out.byte(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

/** Protobuf wire-format reader: iterate fields, unknown ones are skipped. */
class PbReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    var field = 0
        private set
    var wire = 0
        private set

    fun next(): Boolean {
        if (pos >= end) return false
        val t = varint()
        field = (t ushr 3).toInt()
        wire = (t and 7).toInt()
        return true
    }

    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun fixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((buf[pos++].toInt() and 0xFF) shl (8 * i))
        return v
    }

    fun bytes(): ByteArray {
        val n = varint().toInt()
        return buf.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun message(): PbReader {
        val n = varint().toInt()
        return PbReader(buf, pos, pos + n).also { pos += n }
    }

    fun string(): String = bytes().decodeToString()

    fun skip() {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> pos += varint().toInt()
            5 -> pos += 4
            else -> pos = end // groups are not used by Meshtastic
        }
    }
}

/** Meshtastic messages, reduced to what MapAround uses. */
object Mesh {
    const val BROADCAST = 0xFFFFFFFFL
    const val PORT_TEXT = 1
    const val PORT_POSITION = 3
    const val PORT_NODEINFO = 4
    /** Port reserved for private applications; MapAround's encrypted party packets go here. */
    const val PORT_PRIVATE = 256

    data class Position(val lat: Double, val lon: Double, val altM: Int?, val time: Long?)
    data class User(val id: String, val longName: String, val shortName: String)
    data class Packet(val from: Long, val to: Long, val channel: Int, val portnum: Int, val payload: ByteArray, val rxTime: Long?)
    data class NodeInfo(val num: Long, val user: User?, val position: Position?, val lastHeard: Long?)

    /** What the radio sent us (FromRadio), only the variants we use. */
    sealed class FromRadio {
        data class MyInfo(val myNodeNum: Long) : FromRadio()
        data class Node(val info: NodeInfo) : FromRadio()
        data class PacketIn(val packet: Packet) : FromRadio()
        data class ConfigComplete(val id: Long) : FromRadio()
        object Other : FromRadio()
    }

    /** ToRadio{want_config_id}: asks the radio to send its node database (start of a session). */
    fun wantConfig(id: Int): ByteArray = PbWriter().varint(3, id.toLong() and 0xFFFFFFFFL).toByteArray()

    /** ToRadio{packet}: a broadcast on [channel] with [payload] for [portnum]. */
    fun sendPacket(payload: ByteArray, portnum: Int, packetId: Int, channel: Int = 0, hopLimit: Int = 3): ByteArray {
        val data = PbWriter().varint(1, portnum.toLong()).bytes(2, payload)
        val packet = PbWriter()
            .fixed32(2, BROADCAST.toInt())        // to
            .apply { if (channel != 0) varint(3, channel.toLong()) }
            .message(4, data)                     // decoded
            .fixed32(6, packetId)                 // id
            .varint(9, hopLimit.toLong())         // hop_limit
        return PbWriter().message(1, packet).toByteArray()
    }

    fun parseFromRadio(bytes: ByteArray): FromRadio {
        val r = PbReader(bytes)
        while (r.next()) {
            when (r.field) {
                2 -> return parsePacket(r.message())?.let { FromRadio.PacketIn(it) } ?: FromRadio.Other
                3 -> return FromRadio.MyInfo(myNodeNum(r.message()))
                4 -> return FromRadio.Node(parseNodeInfo(r.message()))
                7 -> return FromRadio.ConfigComplete(r.varint())
                else -> r.skip()
            }
        }
        return FromRadio.Other
    }

    fun parsePosition(r: PbReader): Position? {
        var lat: Int? = null
        var lon: Int? = null
        var alt: Int? = null
        var time: Long? = null
        while (r.next()) {
            when (r.field) {
                1 -> lat = r.fixed32()
                2 -> lon = r.fixed32()
                3 -> alt = r.varint().toInt()
                4 -> time = r.fixed32().toLong() and 0xFFFFFFFFL
                else -> r.skip()
            }
        }
        if (lat == null || lon == null || (lat == 0 && lon == 0)) return null
        return Position(lat / 1e7, lon / 1e7, alt, time?.takeIf { it > 0 })
    }

    fun parseUser(r: PbReader): User {
        var id = ""
        var long = ""
        var short = ""
        while (r.next()) {
            when (r.field) {
                1 -> id = r.string()
                2 -> long = r.string()
                3 -> short = r.string()
                else -> r.skip()
            }
        }
        return User(id, long, short)
    }

    private fun myNodeNum(r: PbReader): Long {
        var num = 0L
        while (r.next()) if (r.field == 1) num = r.varint() else r.skip()
        return num
    }

    private fun parseNodeInfo(r: PbReader): NodeInfo {
        var num = 0L
        var user: User? = null
        var position: Position? = null
        var lastHeard: Long? = null
        while (r.next()) {
            when (r.field) {
                1 -> num = r.varint()
                2 -> user = parseUser(r.message())
                3 -> position = parsePosition(r.message())
                5 -> lastHeard = r.fixed32().toLong() and 0xFFFFFFFFL
                else -> r.skip()
            }
        }
        return NodeInfo(num, user, position, lastHeard)
    }

    private fun parsePacket(r: PbReader): Packet? {
        var from = 0L
        var to = 0L
        var channel = 0
        var portnum = -1
        var payload = ByteArray(0)
        var rxTime: Long? = null
        while (r.next()) {
            when (r.field) {
                1 -> from = r.fixed32().toLong() and 0xFFFFFFFFL
                2 -> to = r.fixed32().toLong() and 0xFFFFFFFFL
                3 -> channel = r.varint().toInt()
                4 -> {
                    val d = r.message()
                    while (d.next()) {
                        when (d.field) {
                            1 -> portnum = d.varint().toInt()
                            2 -> payload = d.bytes()
                            else -> d.skip()
                        }
                    }
                }
                7 -> rxTime = r.fixed32().toLong() and 0xFFFFFFFFL
                else -> r.skip() // 'encrypted' packets (other channels) are ignored
            }
        }
        return if (portnum < 0) null else Packet(from, to, channel, portnum, payload, rxTime)
    }
}
