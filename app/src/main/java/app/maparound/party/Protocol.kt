package app.maparound.party

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

// Plain Kotlin/JVM (javax.crypto exists on Android and the desktop), fully unit-tested.
//
// A party is a 16-byte id plus a 32-byte secret, shared by QR code or link. Every message is
// end-to-end encrypted with AES-256-GCM, so MQTT brokers and LoRa radios only relay ciphertext.

/** The shared secret that defines a party. */
class PartyKey(val id: ByteArray, val secret: ByteArray, val name: String) {
    init {
        require(id.size == 16 && secret.size == 32)
    }

    /** 4 bytes that identify the party in packets and topics without revealing its id. */
    val tag: Int = ByteBuffer.wrap(hmac("MapAround party tag v1")).int

    /** Hex form of [tag], used in MQTT topics. */
    val tagHex: String get() = String.format("%08x", tag)

    /** Invite link; also what the QR code contains. Anyone with it can join and read positions. */
    fun toLink(): String =
        "maparound://party/" + B64.encodeToString(id + secret) + "?n=" + URLEncoder.encode(name, "UTF-8")

    fun hmac(label: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(label.toByteArray())
        }

    override fun equals(other: Any?) = other is PartyKey && id.contentEquals(other.id)
    override fun hashCode() = id.contentHashCode()

    companion object {
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val random = SecureRandom()

        fun create(name: String): PartyKey =
            PartyKey(ByteArray(16).also(random::nextBytes), ByteArray(32).also(random::nextBytes), name.trim().ifEmpty { "Party" })

        /** Accepts the full link, or just the code after `party/`. */
        fun parse(text: String): PartyKey? = runCatching {
            val t = text.trim()
            val code = t.substringAfter("party/").substringBefore('?').substringBefore('#')
            val raw = Base64.getUrlDecoder().decode(code)
            if (raw.size != 48) return null
            val name = t.substringAfter("?n=", "").substringBefore('&').let { URLDecoder.decode(it, "UTF-8") }.ifEmpty { "Party" }
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

    /** Who I am: name, colour index and (if I have one) my Meshtastic node number. */
    data class Hello(val name: String, val color: Int, val meshNode: Long) : PartyMessage() {
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
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encode(key: PartyKey, sender: Int, seq: Long, sentAtMs: Long, message: PartyMessage): ByteArray {
        val header = ByteBuffer.allocate(HEADER)
            .put(0x4D).put(0xA1.toByte())
            .putInt(key.tag).putInt(sender).putInt(seq.toInt())
            .put(ByteArray(4).also(random::nextBytes))
            .array()
        val plain = ByteArrayOutputStream().apply {
            write(message.type)
            write(ByteBuffer.allocate(4).putInt((sentAtMs / 1000).toInt()).array())
            write(Bodies.encode(message))
        }.toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.secret, "AES"), GCMParameterSpec(TAG_BITS, header, 6, 12))
        cipher.updateAAD(header)
        return header + cipher.doFinal(plain)
    }

    /** Cheap check before decrypting: is this a MapAround packet for this party? */
    fun isFor(key: PartyKey, bytes: ByteArray): Boolean =
        bytes.size > HEADER + 16 && bytes[0] == 0x4D.toByte() && bytes[1] == 0xA1.toByte() &&
            ByteBuffer.wrap(bytes, 2, 4).int == key.tag

    fun senderOf(bytes: ByteArray): Int = ByteBuffer.wrap(bytes, 6, 4).int

    /** Returns null for other parties, damaged or forged packets. */
    fun decode(key: PartyKey, bytes: ByteArray): Received? {
        if (!isFor(key, bytes)) return null
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.secret, "AES"), GCMParameterSpec(TAG_BITS, bytes, 6, 12))
            cipher.updateAAD(bytes, 0, HEADER)
            cipher.doFinal(bytes, HEADER, bytes.size - HEADER)
        } catch (e: Exception) {
            return null
        }
        val b = ByteBuffer.wrap(bytes)
        val sender = b.getInt(6)
        val seq = b.getInt(10).toLong() and 0xFFFFFFFFL
        val p = ByteBuffer.wrap(plain)
        val type = p.get().toInt() and 0xFF
        val sentAt = (p.int.toLong() and 0xFFFFFFFFL) * 1000
        val message = Bodies.decode(type, p) ?: return null
        return Received(sender, seq, sentAt, message)
    }
}

private object Bodies {
    private const val NONE = 255

    fun encode(m: PartyMessage): ByteArray {
        val b = ByteBuffer.allocate(200)
        when (m) {
            is PartyMessage.Position -> {
                b.putInt((m.lat * 1e7).roundToInt()).putInt((m.lon * 1e7).roundToInt())
                // Short.MIN_VALUE means "unknown"; real heights are clamped to the range above it.
                b.putShort(m.altM?.coerceIn(-32767, 32767)?.toShort() ?: Short.MIN_VALUE)
                b.put(u8(m.accuracyM)).put(u8(m.speedKmh))
                b.put(m.headingDeg?.let { ((it % 360 + 360) % 360 * 256 / 360).toByte() } ?: NONE.toByte())
                b.put(u8(m.batteryPct))
                b.put(((if (m.sos) 1 else 0) or (if (m.driving) 4 else 0)).toByte())
            }
            is PartyMessage.Hello -> {
                b.put(m.color.toByte()).putInt(m.meshNode.toInt())
                putText(b, m.name, 24)
            }
            is PartyMessage.Sos -> {
                b.put((if (m.active) 1 else 0).toByte())
                putText(b, m.text, 80)
            }
            is PartyMessage.Meet -> {
                b.putInt((m.lat * 1e7).roundToInt()).putInt((m.lon * 1e7).roundToInt())
                putText(b, m.label, 32)
            }
            is PartyMessage.Text -> putText(b, m.text, 120)
            PartyMessage.Leave -> Unit
        }
        return b.array().copyOf(b.position())
    }

    fun decode(type: Int, b: ByteBuffer): PartyMessage? = runCatching {
        when (type) {
            1 -> {
                val lat = b.int / 1e7
                val lon = b.int / 1e7
                val alt = b.short.toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                val acc = opt(b.get())
                val speed = opt(b.get())
                val heading = opt(b.get())?.let { it * 360 / 256 }
                val battery = opt(b.get())
                val flags = b.get().toInt()
                PartyMessage.Position(lat, lon, alt, acc, speed, heading, battery, flags and 1 != 0, flags and 4 != 0)
            }
            2 -> PartyMessage.Hello(color = b.get().toInt() and 0xFF, meshNode = b.int.toLong() and 0xFFFFFFFFL, name = getText(b))
            3 -> PartyMessage.Sos(b.get().toInt() != 0, getText(b))
            4 -> PartyMessage.Meet(b.int / 1e7, b.int / 1e7, getText(b))
            5 -> PartyMessage.Text(getText(b))
            6 -> PartyMessage.Leave
            else -> null
        }
    }.getOrNull()

    private fun u8(v: Int?): Byte = (v?.coerceIn(0, 254) ?: NONE).toByte()
    private fun opt(b: Byte): Int? = (b.toInt() and 0xFF).takeIf { it != NONE }

    private fun putText(b: ByteBuffer, s: String, maxBytes: Int) {
        var bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            // Cut on a character boundary.
            var cut = s
            while (cut.toByteArray(Charsets.UTF_8).size > maxBytes) cut = cut.dropLast(1)
            bytes = cut.toByteArray(Charsets.UTF_8)
        }
        b.put(bytes.size.toByte()).put(bytes)
    }

    private fun getText(b: ByteBuffer): String {
        val n = b.get().toInt() and 0xFF
        val bytes = ByteArray(n).also { b.get(it) }
        return String(bytes, Charsets.UTF_8)
    }
}
