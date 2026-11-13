@file:OptIn(ExperimentalEncodingApi::class)

package app.maparound.party

import app.maparound.i18n.T
import app.maparound.shared.crypto.aesGcmOpen
import app.maparound.shared.crypto.aesGcmSeal
import app.maparound.shared.crypto.hmacSha256
import app.maparound.shared.crypto.randomBytes
import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import app.maparound.shared.util.hex
import app.maparound.shared.util.urlDecode
import app.maparound.shared.util.urlEncode
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

// Plain Kotlin (crypto from the platform: javax.crypto on Android, CommonCrypto on iOS), fully
// unit-tested on both.
//
// A party is a 16-byte id plus a 32-byte secret, shared by QR code or link. Every message is
// end-to-end encrypted with AES-256-GCM, so MQTT brokers and LoRa radios only relay ciphertext.

/** The shared secret that defines a party. */
class PartyKey(val id: ByteArray, val secret: ByteArray, val name: String) {
    init {
        require(id.size == 16 && secret.size == 32)
    }

    /** 4 bytes that identify the party in packets and topics without revealing its id. */
    val tag: Int = ByteReader.intAt(hmac("MapAround party tag v1"), 0)

    /** Hex form of [tag], used in MQTT topics. */
    val tagHex: String get() = hex(tag).padStart(8, '0')

    /**
     * Invite link; also what the QR code contains. Anyone with it can join and read positions.
     *
     * A web link, so messaging apps make it tappable and a phone camera can open the QR code: the
     * page ([INVITE_PAGE]) opens MapAround with the app link `maparound://party/<code>?n=<name>`,
     * or offers the app on Google Play. The code is in the part after `#`, which browsers keep on
     * the phone: the website never receives it.
     */
    fun toLink(): String = "$INVITE_PAGE#" + B64.encode(id + secret) + "?n=" + urlEncode(name)

    fun hmac(label: String): ByteArray = hmacSha256(secret, label.encodeToByteArray())

    override fun equals(other: Any?) = other is PartyKey && id.contentEquals(other.id)
    override fun hashCode() = id.contentHashCode()

    companion object {
        /** Host and path of the invite page (docs/party.html on the project's GitHub Pages site). */
        const val INVITE_HOST = "adam2314.github.io"
        const val INVITE_PATH = "/maparound/party"
        const val INVITE_PAGE = "https://$INVITE_HOST$INVITE_PATH"

        /** Where an invite's code starts: the web link, and the app link (also the first app versions' invites). */
        private val LINK_STARTS = listOf("$INVITE_HOST$INVITE_PATH#", "maparound://party/")

        /** URL-safe Base64, written without padding; read with or without. */
        private val B64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

        fun create(name: String): PartyKey =
            PartyKey(randomBytes(16), randomBytes(32), name.trim().ifEmpty { T.party.defaultPartyName })

        /** True if [text] contains an invite link (web or app link). */
        fun hasLink(text: String): Boolean = LINK_STARTS.any { it in text }

        /**
         * Accepts an invite link (web or app link), a message with one in it ("Join my party …:
         * <link>"), or just the code.
         */
        fun parse(text: String): PartyKey? = runCatching {
            val t = text.trim()
            val rest = LINK_STARTS.firstNotNullOfOrNull { start ->
                t.indexOf(start).takeIf { it >= 0 }?.let { t.substring(it + start.length) }
            } ?: t
            val link = rest.takeWhile { !it.isWhitespace() }
            val code = link.substringBefore('?').substringBefore('#').substringBefore('&')
            val raw = B64.decode(code)
            if (raw.size != 48) return null
            val name = link.substringAfter("?n=", "").substringBefore('&').substringBefore('#')
                .let(::urlDecode).ifEmpty { T.party.defaultPartyName }
            PartyKey(raw.copyOfRange(0, 16), raw.copyOfRange(16, 48), name)
        }.getOrNull()
    }
}

/** Decoded content of a party message. */
sealed class PartyMessage {
    abstract val type: Int

    /** Position with quantised extras; [lat]/[lon] in degrees. */
    data class Position(
        val lat: Double,
        val lon: Double,
        val altM: Int?,
        val accuracyM: Int?,
        val speedKmh: Int?,
        val headingDeg: Int?,
        val batteryPct: Int?,
        val sos: Boolean = false,
        val driving: Boolean = false,
    ) : PartyMessage() {
        override val type = 1
    }

    /**
     * Who I am: name, colour (ARGB), map icon ([PartyIcon] code) and (if I have one) my
     * Meshtastic node number.
     */
    data class Hello(val name: String, val color: Int, val meshNode: Long, val icon: Int = 0) : PartyMessage() {
        override val type = 2
    }

    /** Raises ([active]) or cancels an emergency alert. */
    data class Sos(val active: Boolean, val text: String) : PartyMessage() {
        override val type = 3
    }

    /** Sets the group's meeting point; [label] empty and 0/0 clears it. */
    data class Meet(val lat: Double, val lon: Double, val label: String) : PartyMessage() {
        override val type = 4
    }

    data class Text(val text: String) : PartyMessage() {
        override val type = 5
    }

    object Leave : PartyMessage() {
        override val type = 6
    }
}

/** An authenticated, decrypted message with its envelope fields. */
data class Received(val sender: Int, val seq: Long, val sentAt: Long, val message: PartyMessage)

/**
 * Wire format v1 (big-endian):
 * ```
 *  0      'M' 0x4D
 *  1      0xA1  magic + version 1
 *  2..5   party tag
 *  6..9   sender id (random per member and party)
 * 10..13  sequence number (uint32, never reused by a sender)
 * 14..17  random salt          -> nonce = bytes 6..17
 * 18..    AES-256-GCM( type u8 | sent-at unix seconds u32 | body ), 16-byte tag; AAD = bytes 0..17
 * ```
 * A position update is 54 bytes, well under LoRa's 233-byte limit.
 */
object Envelope {
    const val HEADER = 18

    fun encode(key: PartyKey, sender: Int, seq: Long, sentAtMs: Long, message: PartyMessage): ByteArray {
        val header = ByteWriter(HEADER)
            .byte(0x4D).byte(0xA1)
            .int(key.tag).int(sender).int(seq.toInt())
            .bytes(randomBytes(4))
            .toByteArray()
        val plain = ByteWriter(64)
            .byte(message.type)
            .int((sentAtMs / 1000).toInt())
            .bytes(Bodies.encode(message))
            .toByteArray()
        return header + aesGcmSeal(key.secret, header.copyOfRange(6, HEADER), header, plain)
    }

    /** Cheap check before decrypting: is this a MapAround packet for this party? */
    fun isFor(key: PartyKey, bytes: ByteArray): Boolean =
        bytes.size > HEADER + 16 && bytes[0] == 0x4D.toByte() && bytes[1] == 0xA1.toByte() &&
            ByteReader.intAt(bytes, 2) == key.tag

    fun senderOf(bytes: ByteArray): Int = ByteReader.intAt(bytes, 6)

    /** Returns null for other parties, damaged or forged packets. */
    fun decode(key: PartyKey, bytes: ByteArray): Received? {
        if (!isFor(key, bytes)) return null
        val plain = aesGcmOpen(
            key.secret, bytes.copyOfRange(6, HEADER), bytes.copyOfRange(0, HEADER), bytes.copyOfRange(HEADER, bytes.size),
        ) ?: return null
        val sender = ByteReader.intAt(bytes, 6)
        val seq = ByteReader.intAt(bytes, 10).toLong() and 0xFFFFFFFFL
        val p = ByteReader(plain)
        val (type, sentAt) = runCatching { p.u8() to (p.int().toLong() and 0xFFFFFFFFL) * 1000 }.getOrNull() ?: return null
        val message = Bodies.decode(type, p) ?: return null
        return Received(sender, seq, sentAt, message)
    }
}

private object Bodies {
    private const val NONE = 255

    fun encode(m: PartyMessage): ByteArray {
        val b = ByteWriter(64)
        when (m) {
            is PartyMessage.Position -> {
                b.int((m.lat * 1e7).roundToInt()).int((m.lon * 1e7).roundToInt())
                // Short.MIN_VALUE means "unknown"; real heights are clamped to the range above it.
                b.short(m.altM?.coerceIn(-32767, 32767) ?: Short.MIN_VALUE.toInt())
                b.byte(u8(m.accuracyM)).byte(u8(m.speedKmh))
                b.byte(m.headingDeg?.let { (it % 360 + 360) % 360 * 256 / 360 } ?: NONE)
                b.byte(u8(m.batteryPct))
                b.byte((if (m.sos) 1 else 0) or (if (m.driving) 4 else 0))
            }
            is PartyMessage.Hello -> {
                // The first versions read a colour index, the node and the name, and ignore the
                // rest: they get the nearest of their colours; newer ones the icon and the RGB.
                b.byte(PartyPalette.nearestLegacy(m.color)).int(m.meshNode.toInt())
                putText(b, m.name, 24)
                b.byte(m.icon.coerceIn(0, 255))
                b.byte(m.color shr 16 and 0xFF).byte(m.color shr 8 and 0xFF).byte(m.color and 0xFF)
            }
            is PartyMessage.Sos -> {
                b.byte(if (m.active) 1 else 0)
                putText(b, m.text, 80)
            }
            is PartyMessage.Meet -> {
                b.int((m.lat * 1e7).roundToInt()).int((m.lon * 1e7).roundToInt())
                putText(b, m.label, 32)
            }
            is PartyMessage.Text -> putText(b, m.text, 120)
            PartyMessage.Leave -> Unit
        }
        return b.toByteArray()
    }

    fun decode(type: Int, b: ByteReader): PartyMessage? = runCatching {
        when (type) {
            1 -> {
                val lat = b.int() / 1e7
                val lon = b.int() / 1e7
                val alt = b.short().toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                val acc = opt(b.byte())
                val speed = opt(b.byte())
                val heading = opt(b.byte())?.let { it * 360 / 256 }
                val battery = opt(b.byte())
                val flags = b.byte().toInt()
                PartyMessage.Position(lat, lon, alt, acc, speed, heading, battery, flags and 1 != 0, flags and 4 != 0)
            }
            2 -> {
                val legacy = b.u8()
                val node = b.int().toLong() and 0xFFFFFFFFL
                val name = getText(b)
                if (b.remaining >= 4) {
                    val icon = b.u8()
                    val rgb = (b.u8() shl 16) or (b.u8() shl 8) or b.u8()
                    PartyMessage.Hello(name, (0xFF shl 24) or rgb, node, icon)
                } else {
                    PartyMessage.Hello(name, PartyPalette.legacy(legacy), node) // from a first version
                }
            }
            3 -> PartyMessage.Sos(b.byte().toInt() != 0, getText(b))
            4 -> PartyMessage.Meet(b.int() / 1e7, b.int() / 1e7, getText(b))
            5 -> PartyMessage.Text(getText(b))
            6 -> PartyMessage.Leave
            else -> null
        }
    }.getOrNull()

    private fun u8(v: Int?): Int = v?.coerceIn(0, 254) ?: NONE
    private fun opt(b: Byte): Int? = (b.toInt() and 0xFF).takeIf { it != NONE }

    private fun putText(b: ByteWriter, s: String, maxBytes: Int) {
        var bytes = s.encodeToByteArray()
        if (bytes.size > maxBytes) {
            // Cut on a character boundary.
            var cut = s
            while (cut.encodeToByteArray().size > maxBytes) cut = cut.dropLast(1)
            bytes = cut.encodeToByteArray()
        }
        b.byte(bytes.size).bytes(bytes)
    }

    private fun getText(b: ByteReader): String {
        val n = b.u8()
        return b.bytes(n).decodeToString()
    }
}
