package app.maparound.party.link

import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter

// Plain Kotlin: marks on the wire (docs/LINK_PROTOCOL.md §4), their fingerprints (§5), and
// shapes split into parts that fit a LoRa packet. Merging them is the party engine's. Unit-tested.

/** A mark's id: its creator's sender id and their counter (from 1). (0, 0) is the meeting point. */
data class MarkId(val creator: Int, val counter: Long) {
    companion object {
        val MEETING = MarkId(0, 0)
    }
}

/** A register's value, its version (Unix seconds, from the party's clock) and who wrote it. */
data class Reg<out T>(val value: T, val version: Long, val editor: Int)

sealed class Geom {
    data class Point(val at: GridPoint) : Geom()

    data class Circle(val center: GridPoint, val radiusM: Long) : Geom()

    /**
     * A line, or with [area] a closed area (its last vertex not repeated). [part]: one part of a
     * shape too big for one packet; the parts share their boundary vertices.
     */
    data class Path(val area: Boolean, val vertices: List<GridPoint>, val part: Part? = null) : Geom()

    companion object {
        const val MAX_VERTICES = 1000
    }
}

/** Part [index] of [count] (at most [MAX]). */
data class Part(val index: Int, val count: Int) {
    companion object {
        const val MAX = 16
    }
}

enum class Width { NORMAL, THIN, THICK, EXTRA_THICK }

enum class LineStyle { SOLID, DASHED, DOTTED }

enum class Fill { NONE, LIGHT, MEDIUM, STRONG }

/** [icon]: a [MarkIcon] code; [rgb] 0xRRGGBB, null for the icon's own colour. */
data class Style(
    val icon: Int? = null,
    val rgb: Int? = null,
    val width: Width = Width.NORMAL,
    val line: LineStyle = LineStyle.SOLID,
    val fill: Fill = Fill.NONE,
)

data class MarkText(val name: String, val note: String = "") {
    companion object {
        const val NAME_MAX = 40
        const val NOTE_MAX = 120
    }
}

/** Mark icons: 1–8 are the saved places' categories, so a shared place keeps its own. */
object MarkIcon {
    const val PIN = 0
    const val FAVOURITE = 1
    const val CAMP = 2
    const val WATER = 3
    const val VIEW = 4
    const val PARKING = 5
    const val FUEL = 6
    const val FOOD = 7
    const val DANGER = 8
    const val FLAG = 9

    /** The name alone, without a pin. */
    const val LABEL = 10
}

internal object MarkCodec {
    private const val GEOM = 1
    private const val STYLE = 2
    private const val TEXT = 4
    private const val ALIVE = 8

    fun id(b: ByteWriter, id: MarkId) = b.int(id.creator).varint(id.counter)

    fun id(r: ByteReader) = MarkId(r.int(), r.varint())

    fun encode(b: ByteWriter, m: LinkMessage.Mark, sender: Int, time: Long) {
        id(b, m.id)
        var groups = 0
        if (m.geom != null) groups = groups or GEOM
        if (m.style != null) groups = groups or STYLE
        if (m.text != null) groups = groups or TEXT
        if (m.alive != null) groups = groups or ALIVE
        b.byte(groups)
        fun <T> reg(reg: Reg<T>?, payload: (ByteWriter, T) -> Unit) {
            reg ?: return
            val by = reg.editor != sender
            b.varint((zigzag(time - reg.version) shl 1) or (if (by) 1L else 0L))
            if (by) b.int(reg.editor)
            payload(b, reg.value)
        }
        reg(m.geom, ::geom)
        reg(m.style, ::style)
        reg(m.text, ::text)
        reg(m.alive) { w, alive -> w.byte(if (alive) 1 else 0) }
    }

    fun decode(r: ByteReader, sender: Int, time: Long): LinkMessage.Mark {
        val id = id(r)
        val groups = r.u8()
        fun <T> reg(bit: Int, payload: (ByteReader) -> T): Reg<T>? {
            if (groups and bit == 0) return null
            val meta = r.varint()
            val version = time - unzigzag(meta ushr 1)
            if (version < 0 || version > 0xFFFFFFFFL) throw IllegalStateException("Version out of range")
            val editor = if (meta and 1L != 0L) r.int() else sender
            return Reg(payload(r), version, editor)
        }
        // Registers of a newer version (bits 4–7) come after these: not read.
        return LinkMessage.Mark(id, reg(GEOM, ::geom), reg(STYLE, ::style), reg(TEXT, ::text), reg(ALIVE) { it.u8() and 1 != 0 })
    }

    private const val PART = 8

    fun geom(b: ByteWriter, g: Geom) {
        when (g) {
            is Geom.Point -> b.byte(0).vertex(g.at)
            is Geom.Circle -> b.byte(3).vertex(g.center).varint(g.radiusM)
            is Geom.Path -> {
                require(g.vertices.size in (if (g.area && g.part == null) 3 else 2)..Geom.MAX_VERTICES) {
                    "${g.vertices.size} vertices"
                }
                val kind = if (g.area) 2 else 1
                if (g.part == null) {
                    b.byte(kind)
                } else {
                    require(g.part.count in 2..Part.MAX && g.part.index in 0 until g.part.count)
                    b.byte(kind or PART).byte(g.part.index or ((g.part.count - 1) shl 4))
                }
                b.varint(g.vertices.size.toLong()).vertex(g.vertices[0])
                for (i in 1 until g.vertices.size) b.delta(g.vertices[i - 1], g.vertices[i])
            }
        }
    }

    fun geom(r: ByteReader): Geom {
        val kind = r.u8()
        return when (kind and 7) {
            0 -> Geom.Point(r.vertex())
            3 -> Geom.Circle(r.vertex(), r.varint())
            1, 2 -> {
                val part = if (kind and PART != 0) r.u8().let { Part(it and 0x0F, (it shr 4) + 1) } else null
                val n = r.count(Geom.MAX_VERTICES)
                if (n < 2) throw IllegalStateException("A path of $n vertices")
                var last = r.vertex()
                val vertices = ArrayList<GridPoint>(n).apply { add(last) }
                repeat(n - 1) { vertices.add(r.delta(last).also { last = it }) }
                Geom.Path(kind and 7 == 2, vertices, part)
            }
            else -> throw IllegalStateException("Shape kind ${kind and 7}")
        }
    }

    private const val ICON = 1
    private const val COLOUR = 2

    fun style(b: ByteWriter, s: Style) {
        var flags = (s.width.ordinal shl 2) or (s.line.ordinal shl 4) or (s.fill.ordinal shl 6)
        if (s.icon != null) flags = flags or ICON
        if (s.rgb != null) flags = flags or COLOUR
        b.byte(flags)
        s.icon?.let { b.byte(it.coerceIn(0, 255)) }
        s.rgb?.let { b.byte(it shr 16).byte(it shr 8).byte(it) }
    }

    fun style(r: ByteReader): Style {
        val flags = r.u8()
        val icon = if (flags and ICON != 0) r.u8() else null
        val rgb = if (flags and COLOUR != 0) (r.u8() shl 16) or (r.u8() shl 8) or r.u8() else null
        return Style(
            icon, rgb,
            Width.entries[(flags shr 2) and 3],
            LineStyle.entries.getOrElse((flags shr 4) and 3) { LineStyle.SOLID }, // 3: reserved
            Fill.entries[(flags shr 6) and 3],
        )
    }

    fun text(b: ByteWriter, t: MarkText) {
        b.string(t.name, MarkText.NAME_MAX).string(t.note, MarkText.NOTE_MAX)
    }

    fun text(r: ByteReader) = MarkText(r.string(), r.string())

    private fun zigzag(v: Long) = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long) = (v ushr 1) xor -(v and 1)
}

object Marks {
    /**
     * A mark's fingerprint (§5): FNV-1a-32 over its id, then each register in order (shape, style,
     * text, alive): version, editor and payload; an absent one as version 0, editor 0. [mark]
     * holds its whole state, with a whole shape (not a part).
     */
    fun fingerprint(mark: LinkMessage.Mark): Int {
        val f = Fnv().bytes(MarkCodec.id(ByteWriter(16), mark.id).toByteArray())
        fun <T> reg(reg: Reg<T>?, payload: (ByteWriter, T) -> Unit) {
            if (reg == null) {
                f.u32(0).u32(0)
            } else {
                f.u32(reg.version).u32(reg.editor.toLong() and 0xFFFFFFFFL)
                f.bytes(ByteWriter(32).also { payload(it, reg.value) }.toByteArray())
            }
        }
        reg(mark.geom, MarkCodec::geom)
        reg(mark.style, MarkCodec::style)
        reg(mark.text, MarkCodec::text)
        reg(mark.alive) { w, alive -> w.byte(if (alive) 1 else 0) }
        return f.hash
    }

    /** The summary of the marks with these fingerprints (deleted ones included). */
    fun summary(fingerprints: Collection<Int>): MarksSummary {
        val f = Fnv()
        fingerprints.map { it.toUInt() }.sorted().forEach { f.u32(it.toLong()) }
        return MarksSummary(fingerprints.size, f.hash)
    }

    /**
     * My marks' DIGEST pages (§5): the fewest pages (at most 16) in which none holds more than
     * [LinkMessage.Digest.MAX_PER_PAGE], a mark going to page `fp mod pages`.
     */
    fun digest(fingerprints: Collection<Int>): List<LinkMessage.Digest> {
        val summary = summary(fingerprints)
        val max = LinkMessage.Digest.MAX_PER_PAGE
        var pages = ((fingerprints.size + max - 1) / max).coerceIn(1, Part.MAX)
        while (true) {
            val byPage = fingerprints.groupBy { (it.toUInt() % pages.toUInt()).toInt() }
            if (pages == Part.MAX || byPage.values.all { it.size <= max }) {
                return List(pages) { p ->
                    LinkMessage.Digest(summary, p, pages, byPage[p].orEmpty().map { it and 0xFFFF }.sorted())
                }
            }
            pages++
        }
    }

    /**
     * [mark] as MARK messages that each seal to at most [maxPacket] bytes, sent by [sender] at
     * [time]: itself when it fits, else its shape in parts that share their boundary vertices,
     * the other registers riding with part 0 (or alone, when they leave too little room).
     * Throws when the shape needs more than [Part.MAX] parts.
     */
    fun split(mark: LinkMessage.Mark, sender: Int, time: Long, maxPacket: Int = LinkEnvelope.LORA_MAX): List<LinkMessage.Mark> {
        fun fits(m: LinkMessage.Mark) = sealedSize(m, sender, time) <= maxPacket
        if (fits(mark)) return listOf(mark)
        val geom = mark.geom
        val others = mark.copy(geom = null).takeIf { it.style != null || it.text != null || it.alive != null }
        // The registers besides the shape: together when they fit, else one by one.
        fun separately(): List<LinkMessage.Mark> = when {
            others == null -> emptyList()
            fits(others) -> listOf(others)
            else -> listOfNotNull(
                others.style?.let { LinkMessage.Mark(mark.id, style = it) },
                others.text?.let { LinkMessage.Mark(mark.id, text = it) },
                others.alive?.let { LinkMessage.Mark(mark.id, alive = it) },
            )
        }
        val whole = geom?.let { LinkMessage.Mark(mark.id, geom = it) }
        val path = geom?.value as? Geom.Path
        if (path == null || fits(whole!!)) return separately() + listOfNotNull(whole)

        // What a part costs besides its vertices after the first: measured on a part of two equal
        // vertices, with the count's byte and the zero delta's two bytes taken out and the most a
        // count can take put in.
        fun partCost(withOthers: Boolean): Int {
            val v = path.vertices[0]
            val probe = Reg(Geom.Path(path.area, listOf(v, v), Part(0, 2)), geom.version, geom.editor)
            val m = if (withOthers) others!!.copy(geom = probe) else LinkMessage.Mark(mark.id, geom = probe)
            return sealedSize(m, sender, time) - 1 - 2 + varintSize(Geom.MAX_VERTICES.toLong())
        }
        val firstWithOthers = others != null &&
            maxPacket - partCost(true) >= deltaSize(path.vertices[0], path.vertices[1])
        val ranges = ArrayList<IntRange>()
        var start = 0
        while (start < path.vertices.lastIndex) {
            var room = maxPacket - partCost(ranges.isEmpty() && firstWithOthers)
            var end = start
            while (end < path.vertices.lastIndex) {
                val cost = deltaSize(path.vertices[end], path.vertices[end + 1])
                if (cost > room) break
                room -= cost
                end++
            }
            if (end == start) throw IllegalArgumentException("A vertex doesn't fit a packet of $maxPacket bytes")
            ranges += start..end
            start = end // the next part starts at this one's last vertex
        }
        if (ranges.size > Part.MAX) throw IllegalArgumentException("${path.vertices.size} vertices need ${ranges.size} parts")
        val parts = ranges.mapIndexed { i, range ->
            val g = Reg(Geom.Path(path.area, path.vertices.slice(range), Part(i, ranges.size)), geom.version, geom.editor)
            if (i == 0 && firstWithOthers) others!!.copy(geom = g) else LinkMessage.Mark(mark.id, geom = g)
        }
        return if (firstWithOthers) parts else separately() + parts
    }

    /** The whole shape from all the parts of one version; null while one is missing or they disagree. */
    fun join(parts: Collection<Geom.Path>): Geom.Path? {
        val first = parts.firstOrNull() ?: return null
        val count = first.part?.count ?: return first
        val byIndex = parts.associateBy { it.part?.index }
        if (parts.any { it.part?.count != count || it.area != first.area } || (0 until count).any { it !in byIndex }) return null
        val vertices = ArrayList<GridPoint>()
        for (i in 0 until count) {
            val v = byIndex.getValue(i).vertices
            if (i > 0 && v.first() != vertices.last()) return null
            vertices.addAll(if (i == 0) v else v.drop(1))
        }
        return Geom.Path(first.area, vertices)
    }

    internal fun sealedSize(m: LinkMessage, sender: Int, time: Long): Int =
        LinkEnvelope.OVERHEAD + LinkBodies.encode(m, sender, time).size

    private fun deltaSize(a: GridPoint, b: GridPoint) = ByteWriter(8).delta(a, b).size

    private fun varintSize(v: Long) = ByteWriter(8).varint(v).size
}
