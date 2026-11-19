package app.maparound.party.link

import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import kotlin.math.roundToInt

// Plain Kotlin: Link v1's messages and their bodies (docs/LINK_PROTOCOL.md §3–5). Unit-tested.

object LinkType {
    const val POS = 1
    const val HELLO = 2
    const val SOS = 3
    const val TEXT = 4
    const val LEAVE = 5
    const val MARK = 6
    const val DIGEST = 7
    const val WANT = 8
    const val ACK = 9
    const val LIVE = 10

    /** 0 urgent, 1 normal, 2 bulk: how queues and relays rank a type, without reading the body. */
    fun priority(type: Int): Int = when (type) {
        SOS, ACK, LIVE -> 0
        DIGEST, WANT -> 2
        else -> 1
    }
}

/** How often a member sends their position (§3). */
enum class LinkMode(val code: Int) {
    NORMAL(0), SAVER(1), LIVE(2);

    companion object {
        /** Code 3 is reserved: read as Normal. */
        fun of(code: Int): LinkMode = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

/** A member's mode, and whether Auto chose it. */
data class ModeInfo(val mode: LinkMode, val auto: Boolean)

/** A point of a position's trail: where the member was at [time] (Unix seconds). */
data class TrailPoint(val at: GridPoint, val time: Long)

/** The summary of a party's marks (§5): how many, and the hash of their fingerprints. */
data class MarksSummary(val count: Int, val hash: Int)

/** A party Live that a HELLO passes on: when it ends, when it was sent and by whom. */
data class LiveInfo(val until: Long, val time: Long, val by: Int)

sealed class LinkMessage {
    abstract val type: Int

    /**
     * A position, in the units it travels in, so the sender predicts from exactly what the others
     * read (dead reckoning). [heading] in 256ths of a turn clockwise from north; [accuracyM] and
     * [speedKmh] 0–255, where 255 means that much or more. [trail]: newest first, going back.
     */
    data class Pos(
        val at: GridPoint,
        val accuracyM: Int? = null,
        val batteryPct: Int? = null,
        val charging: Boolean = false,
        val heading: Int? = null,
        val speedKmh: Int? = null,
        val altM: Int? = null,
        val sos: Boolean = false,
        val mode: ModeInfo? = null,
        val trail: List<TrailPoint> = emptyList(),
    ) : LinkMessage() {
        override val type: Int get() = LinkType.POS

        val headingDeg: Double? get() = heading?.let { it * 360.0 / 256 }

        /** Good enough to draw: within [GOOD_FIX_M], or of unknown accuracy. */
        val isGoodFix: Boolean get() = accuracyM == null || accuracyM <= GOOD_FIX_M

        val driving: Boolean get() = (speedKmh ?: 0) >= DRIVING_KMH

        companion object {
            const val GOOD_FIX_M = 20
            const val DRIVING_KMH = 15

            /** "No fix": a heartbeat without a location repeats the last vertex with this. */
            const val NO_FIX = 255

            /** [deg] as the wire's heading (256ths of a turn). */
            fun heading(deg: Double): Int = (deg / 360.0 * 256).roundToInt().mod(256)
        }
    }

    /**
     * Who I am. [rgb]: the colour (0xRRGGBB); [icon]: a member icon (PartyIcon code). [marks]
     * non-null: this app speaks MARK, DIGEST and WANT, and that's what it holds.
     */
    data class Hello(
        val name: String,
        val rgb: Int,
        val icon: Int = 0,
        val mode: ModeInfo = ModeInfo(LinkMode.NORMAL, auto = true),
        val radio: Boolean = false,
        val gateway: Boolean = false,
        val meshNode: Long? = null,
        val marks: MarksSummary? = null,
        val live: LiveInfo? = null,
    ) : LinkMessage() {
        override val type: Int get() = LinkType.HELLO

        companion object {
            const val NAME_MAX = 24
        }
    }

    /** Raises ([active]) or cancels an alert. */
    data class Sos(val active: Boolean, val text: String = "") : LinkMessage() {
        override val type: Int get() = LinkType.SOS

        companion object {
            const val TEXT_MAX = 80
        }
    }

    data class Text(val text: String) : LinkMessage() {
        override val type: Int get() = LinkType.TEXT

        companion object {
            const val TEXT_MAX = 170
        }
    }

    data object Leave : LinkMessage() {
        override val type: Int get() = LinkType.LEAVE
    }

    /**
     * A change to a mark, or its full state (§4): the registers present, each with its version
     * (Unix seconds, from the party's clock) and editor.
     */
    data class Mark(
        val id: MarkId,
        val geom: Reg<Geom>? = null,
        val style: Reg<Style>? = null,
        val text: Reg<MarkText>? = null,
        val alive: Reg<Boolean>? = null,
    ) : LinkMessage() {
        override val type: Int get() = LinkType.MARK
    }

    /** One page of the marks I hold (§5): the low 16 bits of their fingerprints. */
    data class Digest(val summary: MarksSummary, val page: Int, val pages: Int, val fp16s: List<Int>) : LinkMessage() {
        override val type: Int get() = LinkType.DIGEST

        companion object {
            const val MAX_PER_PAGE = 80
        }
    }

    data class Want(val entries: List<WantEntry>) : LinkMessage() {
        override val type: Int get() = LinkType.WANT
    }

    /** Packets I received (an SOS, online a TEXT). */
    data class Ack(val refs: List<PacketRef>) : LinkMessage() {
        override val type: Int get() = LinkType.ACK
    }

    /** The whole party goes Live until [until] (Unix seconds); 0 ends it now. */
    data class Live(val until: Long) : LinkMessage() {
        override val type: Int get() = LinkType.LIVE

        companion object {
            /** The longest party Live: receivers cut a later [until] to this after the packet's time. */
            const val MAX_SECONDS = 12 * 3600L
        }
    }

    /** A type this app doesn't know (from a newer one): not read, but still relayed. */
    class Unknown(override val type: Int, val body: ByteArray) : LinkMessage()
}

sealed class WantEntry {
    /** Send me the marks with this fingerprint (low 16 bits). */
    data class Fingerprint(val fp16: Int) : WantEntry()

    /** Send me these parts of this mark's shape: bit i for part i. */
    data class Parts(val id: MarkId, val missing: Int) : WantEntry()
}

/** The bodies' bytes. [sender] and [time] come from the header: marks and trails refer to them. */
internal object LinkBodies {
    fun encode(m: LinkMessage, sender: Int, time: Long): ByteArray {
        val b = ByteWriter(64)
        when (m) {
            is LinkMessage.Pos -> pos(b, m, time)
            is LinkMessage.Hello -> hello(b, m)
            is LinkMessage.Sos -> b.byte(if (m.active) 1 else 0).string(m.text, LinkMessage.Sos.TEXT_MAX)
            is LinkMessage.Text -> b.string(m.text, LinkMessage.Text.TEXT_MAX)
            LinkMessage.Leave -> Unit
            is LinkMessage.Mark -> MarkCodec.encode(b, m, sender, time)
            is LinkMessage.Digest -> {
                b.varint(m.summary.count.toLong()).int(m.summary.hash)
                require(m.pages in 1..16 && m.page in 0 until m.pages)
                b.byte(m.page or ((m.pages - 1) shl 4))
                m.fp16s.forEach { b.short(it) }
            }
            is LinkMessage.Want -> m.entries.forEach { e ->
                when (e) {
                    is WantEntry.Fingerprint -> b.byte(1).short(e.fp16)
                    is WantEntry.Parts -> MarkCodec.id(b.byte(2), e.id).short(e.missing)
                }
            }
            is LinkMessage.Ack -> m.refs.forEach { b.int(it.sender).u32(it.time).short(it.seq) }
            is LinkMessage.Live -> b.u32(m.until)
            is LinkMessage.Unknown -> b.bytes(m.body)
        }
        return b.toByteArray()
    }

    /** Null when the body is damaged (it passed authentication, so: a bug in its sender). */
    fun decode(type: Int, body: ByteArray, sender: Int, time: Long): LinkMessage? = runCatching {
        val r = ByteReader(body)
        when (type) {
            LinkType.POS -> pos(r, time)
            LinkType.HELLO -> hello(r)
            LinkType.SOS -> LinkMessage.Sos(r.u8() and 1 != 0, r.string())
            LinkType.TEXT -> LinkMessage.Text(r.string())
            LinkType.LEAVE -> LinkMessage.Leave
            LinkType.MARK -> MarkCodec.decode(r, sender, time)
            LinkType.DIGEST -> {
                val summary = MarksSummary(r.count(Int.MAX_VALUE), r.int())
                val page = r.u8()
                LinkMessage.Digest(summary, page and 0x0F, (page shr 4) + 1, List(r.remaining / 2) { r.u16() })
            }
            LinkType.WANT -> LinkMessage.Want(buildList {
                while (r.remaining > 0) {
                    when (r.u8()) {
                        1 -> add(WantEntry.Fingerprint(r.u16()))
                        2 -> add(WantEntry.Parts(MarkCodec.id(r), r.u16()))
                        else -> break // a newer kind of entry: its length is unknown
                    }
                }
            })
            LinkType.ACK -> LinkMessage.Ack(List(r.remaining / 10) { PacketRef(r.int(), r.u32(), r.u16()) })
            LinkType.LIVE -> LinkMessage.Live(r.u32())
            else -> LinkMessage.Unknown(type, body)
        }
    }.getOrNull()

    private const val ACC = 1
    private const val BAT = 2
    private const val HDG = 4
    private const val SPD = 8
    private const val ALT = 16
    private const val SOS = 32
    private const val MODE = 64
    private const val TRAIL = 128

    private fun pos(b: ByteWriter, m: LinkMessage.Pos, time: Long) {
        var flags = 0
        if (m.accuracyM != null) flags = flags or ACC
        if (m.batteryPct != null) flags = flags or BAT
        if (m.heading != null) flags = flags or HDG
        if (m.speedKmh != null) flags = flags or SPD
        if (m.altM != null) flags = flags or ALT
        if (m.sos) flags = flags or SOS
        if (m.mode != null) flags = flags or MODE
        if (m.trail.isNotEmpty()) flags = flags or TRAIL
        b.byte(flags).vertex(m.at)
        m.accuracyM?.let { b.byte(it.coerceIn(0, 255)) }
        m.batteryPct?.let { b.byte(it.coerceIn(0, 100) or (if (m.charging) 0x80 else 0)) }
        m.heading?.let { b.byte(it.mod(256)) }
        m.speedKmh?.let { b.byte(it.coerceIn(0, 255)) }
        m.altM?.let { b.short(it.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) }
        m.mode?.let { b.byte(it.mode.code or (if (it.auto) 4 else 0)) }
        if (m.trail.isNotEmpty()) {
            b.varint(m.trail.size.toLong())
            var after = TrailPoint(m.at, time)
            for (p in m.trail) {
                require(p.time <= after.time) { "A trail goes back in time" }
                b.delta(after.at, p.at).varint(after.time - p.time)
                after = p
            }
        }
    }

    private fun pos(r: ByteReader, time: Long): LinkMessage.Pos {
        val flags = r.u8()
        val at = r.vertex()
        val acc = if (flags and ACC != 0) r.u8() else null
        val bat = if (flags and BAT != 0) r.u8() else null
        val hdg = if (flags and HDG != 0) r.u8() else null
        val spd = if (flags and SPD != 0) r.u8() else null
        val alt = if (flags and ALT != 0) r.short().toInt() else null
        val mode = if (flags and MODE != 0) r.u8().let { ModeInfo(LinkMode.of(it and 3), it and 4 != 0) } else null
        val trail = if (flags and TRAIL != 0) {
            var after = TrailPoint(at, time)
            List(r.count(MAX_TRAIL)) {
                val p = r.delta(after.at)
                TrailPoint(p, after.time - r.varint()).also { after = it }
            }
        } else {
            emptyList()
        }
        return LinkMessage.Pos(
            at, acc, bat?.and(0x7F), bat != null && bat and 0x80 != 0, hdg, spd, alt, flags and SOS != 0, mode, trail,
        )
    }

    /** The most trail points a position is read with (an online one fits about 120). */
    private const val MAX_TRAIL = 1000

    private const val MARKS = 1
    private const val RADIO = 2
    private const val GATEWAY = 4
    private const val NODE = 8
    private const val AUTO = 64
    private const val LIVE = 128

    private fun hello(b: ByteWriter, m: LinkMessage.Hello) {
        var caps = m.mode.mode.code shl 4
        if (m.marks != null) caps = caps or MARKS
        if (m.radio) caps = caps or RADIO
        if (m.gateway) caps = caps or GATEWAY
        if (m.meshNode != null) caps = caps or NODE
        if (m.mode.auto) caps = caps or AUTO
        if (m.live != null) caps = caps or LIVE
        b.byte(caps).byte(m.icon.coerceIn(0, 255))
        b.byte(m.rgb shr 16).byte(m.rgb shr 8).byte(m.rgb)
        m.meshNode?.let { b.u32(it) }
        m.marks?.let {
            b.varint(it.count.toLong())
            if (it.count > 0) b.int(it.hash)
        }
        m.live?.let { b.u32(it.until).u32(it.time).int(it.by) }
        b.string(m.name, LinkMessage.Hello.NAME_MAX)
    }

    private fun hello(r: ByteReader): LinkMessage.Hello {
        val caps = r.u8()
        val icon = r.u8()
        val rgb = (r.u8() shl 16) or (r.u8() shl 8) or r.u8()
        val node = if (caps and NODE != 0) r.u32() else null
        val marks = if (caps and MARKS != 0) {
            val count = r.count(Int.MAX_VALUE)
            MarksSummary(count, if (count > 0) r.int() else 0)
        } else {
            null
        }
        val live = if (caps and LIVE != 0) LiveInfo(r.u32(), r.u32(), r.int()) else null
        return LinkMessage.Hello(
            r.string(), rgb, icon, ModeInfo(LinkMode.of((caps shr 4) and 3), caps and AUTO != 0),
            caps and RADIO != 0, caps and GATEWAY != 0, node, marks, live,
        )
    }
}
