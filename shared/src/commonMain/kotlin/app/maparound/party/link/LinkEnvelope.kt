package app.maparound.party.link

import app.maparound.party.PartyKey
import app.maparound.shared.crypto.Gcm
import app.maparound.shared.crypto.hmacSha256
import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import app.maparound.shared.util.hex

// Plain Kotlin: Link v1's envelope (docs/LINK_PROTOCOL.md §2). The same bytes on every
// connection; relays change nothing but the hop count. Unit-tested.

/** A party's Link v1 keys, derived from the invite's secret (never used as it is). */
class LinkKey(secret: ByteArray) {
    init {
        require(secret.size == 32)
    }

    /** AES-256 key. */
    internal val key: ByteArray = hmacSha256(secret, "MapAround link key v1".encodeToByteArray())

    /** The party's 2-byte tag, in every header (and in its MQTT topics as [tagHex]). */
    val tag: Int = hmacSha256(secret, "MapAround link tag v1".encodeToByteArray()).let {
        ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF)
    }

    val tagHex: String get() = hex(tag).padStart(4, '0')

    companion object {
        fun of(party: PartyKey) = LinkKey(party.secret)
    }
}

/** A packet's identity: what duplicates are told by, and what an ACK names. */
data class PacketRef(val sender: Int, val time: Long, val seq: Int)

/** The clear part of a packet, readable without the key (relays work by it alone). */
data class LinkHeader(
    /** [LinkType]. */
    val type: Int,
    /** App-level relays so far, 0 to [LinkEnvelope.MAX_HOPS]. */
    val hops: Int,
    val tag: Int,
    val sender: Int,
    /** Unix seconds when first sent. */
    val time: Long,
    /** 0 to 65 535, wrapping. */
    val seq: Int,
) {
    val ref: PacketRef get() = PacketRef(sender, time, seq)

    /** How relays rank it (§3): 0 urgent, 1 normal, 2 bulk. */
    val priority: Int get() = LinkType.priority(type)
}

/** A packet opened with its party's key. */
data class LinkPacket(val header: LinkHeader, val message: LinkMessage)

object LinkEnvelope {
    const val MARKER = 0xA2
    const val HEADER = 14
    const val TAG = 8

    /** Header and authentication tag: what every packet costs on top of its body. */
    const val OVERHEAD = HEADER + TAG
    const val MAX_HOPS = 3

    /** The largest packet on LoRa (and on connections relayed to it) (§6). */
    const val LORA_MAX = 200

    /** The largest packet online. */
    const val ONLINE_MAX = 4096

    /** Seals [message] from [sender]; [time] in Unix seconds, [seq] its sequence number (wraps at 65 536). */
    fun seal(key: LinkKey, sender: Int, time: Long, seq: Int, message: LinkMessage): ByteArray {
        require(sender != 0) { "Sender id 0 is never used" }
        val header = ByteWriter(HEADER)
            .byte(MARKER).byte(message.type)
            .short(key.tag).int(sender).u32(time).short(seq and 0xFFFF)
            .toByteArray()
        val body = LinkBodies.encode(message, sender, time)
        return header + Gcm.seal(key.key, nonce(header), aad(header), body, tagSize = TAG)
    }

    /**
     * The header of a Link v1 packet, without the key; null when [bytes] isn't one (another
     * format, too short, reserved bits set).
     */
    fun header(bytes: ByteArray): LinkHeader? {
        if (bytes.size < OVERHEAD || bytes[0].toInt() and 0xFF != MARKER) return null
        val b1 = bytes[1].toInt() and 0xFF
        if (b1 and 0xC0 != 0) return null
        val r = ByteReader(bytes, 2)
        return LinkHeader(b1 and 0x0F, (b1 shr 4) and 3, r.u16(), r.int(), r.u32(), r.u16())
    }

    /**
     * Opens a packet with [key]: null when it isn't Link v1, is for another party, or was damaged
     * or forged. A body this app can't read (a newer type) comes as [LinkMessage.Unknown].
     * Checking the time against the clock, echoes and duplicates is the caller's.
     */
    fun open(key: LinkKey, bytes: ByteArray): LinkPacket? {
        val h = header(bytes) ?: return null
        if (h.tag != key.tag) return null
        val head = bytes.copyOf(HEADER)
        val body = Gcm.open(key.key, nonce(head), aad(head), bytes.copyOfRange(HEADER, bytes.size), tagSize = TAG)
            ?: return null
        val message = LinkBodies.decode(h.type, body, h.sender, h.time) ?: return null
        return LinkPacket(h, message)
    }

    /** The same packet one relay further, or null when it has had [MAX_HOPS] already. */
    fun relayed(bytes: ByteArray): ByteArray? {
        val h = header(bytes) ?: return null
        if (h.hops >= MAX_HOPS) return null
        return bytes.copyOf().also { it[1] = (h.type or ((h.hops + 1) shl 4)).toByte() }
    }

    /** Bytes 4–13 (sender, time, seq) and two zeros. */
    private fun nonce(header: ByteArray) = header.copyOfRange(4, HEADER) + ByteArray(2)

    /** The header with the hop bits cleared, so relays can count without breaking the tag. */
    private fun aad(header: ByteArray) = header.copyOf(HEADER).also { it[1] = (it[1].toInt() and 0xCF).toByte() }
}
