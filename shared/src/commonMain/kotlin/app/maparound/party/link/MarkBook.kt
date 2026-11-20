package app.maparound.party.link

import app.maparound.shared.util.ByteWriter

// Plain Kotlin: a party's marks as this phone holds them (docs/LINK_PROTOCOL.md §4–5): my edits,
// merged with everyone's register by register, the party's version clock, shapes waiting for
// their parts, and what to ask for and send when catching up. Unit-tested.

/** A connection's id, as the platform names it (an MQTT server, a radio). */
typealias ConnectionId = String

private fun aliveBytes(w: ByteWriter, v: Boolean) {
    w.byte(if (v) 1 else 0)
}

/** One mark: its registers as merged, and how it came here. */
class MarkEntry internal constructor(val id: MarkId) {
    var geom: Reg<Geom>? = null
        internal set
    var style: Reg<Style>? = null
        internal set
    var text: Reg<MarkText>? = null
        internal set
    var alive: Reg<Boolean>? = null
        internal set

    /** The connections that brought any of it. */
    val via: Set<ConnectionId> get() = _via
    internal val _via = HashSet<ConnectionId>()

    /** Made or edited on this phone. */
    var mine: Boolean = false
        internal set

    val isAlive: Boolean get() = alive?.value ?: true

    /** Shown on the map (if a connection that brought it is shown, or it's [mine]). */
    val isShown: Boolean get() = geom != null && isAlive

    /** The newest register's editor: who changed it last. */
    val lastEditor: Int? get() = listOfNotNull(geom, style, text, alive).maxByOrNull { it.version }?.editor

    val lastEdited: Long? get() = listOfNotNull(geom, style, text, alive).maxOfOrNull { it.version }

    /** Everything known, as one MARK. */
    fun full(): LinkMessage.Mark = LinkMessage.Mark(id, geom, style, text, alive)
}

/** What [MarkBook.receive] did. */
data class MarkReceipt(
    val entry: MarkEntry,
    /** A register changed. */
    val changed: Boolean,
    /**
     * Taken as a mark's full state (a retained snapshot online, an answer when catching up),
     * what came is behind mine: it lacks a register I have, or holds one older. Then my state is
     * worth sending. (An edit carries only what changed, so for one this says nothing.)
     */
    val behind: Boolean,
)

/**
 * A party's marks. [me]: my sender id. [nextCounter] and [clock] are kept across restarts (the
 * caller saves them): my next mark's counter, and the highest version seen in the party.
 */
class MarkBook(val me: Int, var nextCounter: Long = 1, var clock: Long = 0) {
    private val entries = LinkedHashMap<MarkId, MarkEntry>()

    /** Parts of shapes, by mark, version and editor, until all have come. */
    private val waiting = LinkedHashMap<Triple<MarkId, Long, Int>, Waiting>()

    private class Waiting(val since: Long, val count: Int, val parts: HashMap<Int, Geom.Path> = HashMap())

    val all: Collection<MarkEntry> get() = entries.values

    operator fun get(id: MarkId): MarkEntry? = entries[id]

    /** The marks to draw, given which connections are shown. */
    fun shown(isShown: (ConnectionId) -> Boolean): List<MarkEntry> =
        entries.values.filter { e -> e.isShown && (e.mine || e.via.any(isShown)) }

    /** The version for my next write: never below anything seen, so my edit beats it. */
    fun version(nowS: Long): Long {
        clock = maxOf(nowS, clock + 1)
        return clock
    }

    /** A version seen: the clock moves up to it, unless it's more than [FUTURE_S] ahead of [nowS]. */
    private fun seen(version: Long, nowS: Long) {
        if (version <= nowS + FUTURE_S) clock = maxOf(clock, version)
    }

    // My changes. Each returns the MARK to send, already applied here.

    fun create(nowS: Long, geom: Geom, style: Style? = null, text: MarkText? = null): LinkMessage.Mark =
        edit(MarkId(me, nextCounter++), nowS, geom, style, text)

    /** Changes the registers given (an edit sends only those). */
    fun edit(
        id: MarkId, nowS: Long, geom: Geom? = null, style: Style? = null, text: MarkText? = null, alive: Boolean? = null,
    ): LinkMessage.Mark {
        val v = version(nowS)
        val m = LinkMessage.Mark(
            id,
            geom?.let { Reg(it, v, me) }, style?.let { Reg(it, v, me) }, text?.let { Reg(it, v, me) }, alive?.let { Reg(it, v, me) },
        )
        val e = entries.getOrPut(id) { MarkEntry(id) }
        merge(e, m)
        e.mine = true
        return m
    }

    fun delete(id: MarkId, nowS: Long) = edit(id, nowS, alive = false)

    /** Sets the meeting point (mark (0, 0)): a flag with [label], alive again if it was cleared. */
    fun setMeeting(nowS: Long, at: GridPoint, label: String): LinkMessage.Mark {
        val wasDeleted = entries[MarkId.MEETING]?.isAlive == false
        return edit(
            MarkId.MEETING, nowS, Geom.Point(at), Style(icon = MarkIcon.FLAG), MarkText(label),
            alive = if (wasDeleted) true else null,
        )
    }

    fun clearMeeting(nowS: Long) = delete(MarkId.MEETING, nowS)

    // What others send.

    /**
     * Merges a MARK that came over [via] (null: from storage). A part of a shape waits for the
     * others; the shape counts once all have come.
     */
    fun receive(mark: LinkMessage.Mark, via: ConnectionId?, nowS: Long): MarkReceipt {
        listOfNotNull(mark.geom, mark.style, mark.text, mark.alive).forEach { seen(it.version, nowS) }
        val e = entries.getOrPut(mark.id) { MarkEntry(mark.id) }
        via?.let { e._via += it }
        var m = mark
        val path = mark.geom?.value as? Geom.Path
        if (path?.part != null) {
            m = mark.copy(geom = whole(mark.id, mark.geom, path, nowS))
        }
        val changed = merge(e, m)
        return MarkReceipt(e, changed, behind = behind(e, mark))
    }

    /** The whole shape once [path]'s part completes it; null while parts are missing (or it's old). */
    private fun whole(id: MarkId, reg: Reg<Geom>, path: Geom.Path, nowS: Long): Reg<Geom>? {
        val have = entries[id]?.geom
        if (have != null && (have.version > reg.version || have.version == reg.version && have.editor == reg.editor)) return null
        val key = Triple(id, reg.version, reg.editor)
        val count = path.part!!.count
        val w = waiting[key]?.takeIf { it.count == count } ?: Waiting(nowS, count).also { waiting[key] = it }
        w.parts[path.part.index] = path
        if (w.parts.size < count) {
            while (waiting.size > MAX_WAITING) waiting.remove(waiting.keys.first())
            return null
        }
        waiting.remove(key)
        return Marks.join(w.parts.values)?.let { Reg(it, reg.version, reg.editor) }
    }

    /** Shapes still missing parts after [afterS]: what to ask for (WANT), oldest first. */
    fun missingParts(nowS: Long, afterS: Long = 30): List<WantEntry.Parts> =
        waiting.entries.filter { nowS - it.value.since >= afterS }.map { (key, w) ->
            val missing = (0 until w.count).filter { it !in w.parts }.fold(0) { bits, i -> bits or (1 shl i) }
            WantEntry.Parts(key.first, missing)
        }.distinctBy { it.id }

    /** Forgets shapes whose parts haven't all come in [WAIT_S]. */
    fun expire(nowS: Long) {
        waiting.entries.removeAll { nowS - it.value.since >= WAIT_S }
    }

    // Catching up (§5).

    fun fingerprints(): Map<MarkId, Int> = entries.values.filter { it.hasState }.associate { it.id to Marks.fingerprint(it.full()) }

    fun summary(): MarksSummary = Marks.summary(fingerprints().values)

    fun digest(): List<LinkMessage.Digest> = Marks.digest(fingerprints().values)

    /** A DIGEST page from someone: the fingerprints I lack (to WANT), and my marks it lacks (to send). */
    fun compare(page: LinkMessage.Digest): Pair<List<Int>, List<MarkId>> {
        val mine = fingerprints().filterValues { (it.toUInt() % page.pages.toUInt()).toInt() == page.page }
        val myFp16 = mine.values.map { it and 0xFFFF }.toSet()
        val theirs = page.fp16s.toSet()
        return (theirs - myFp16).sorted() to mine.filterValues { (it and 0xFFFF) !in theirs }.keys.toList()
    }

    /** What a WANT asks of me: the marks I hold that it names, in full (to be split for the radio). */
    fun wanted(want: LinkMessage.Want): List<LinkMessage.Mark> {
        val fps by lazy { fingerprints() }
        return want.entries.flatMap { w ->
            when (w) {
                is WantEntry.Fingerprint -> fps.filterValues { it and 0xFFFF == w.fp16 }.keys.mapNotNull { entries[it]?.full() }
                is WantEntry.Parts -> listOfNotNull(entries[w.id]?.takeIf { it.geom != null }?.full())
            }
        }.distinctBy { it.id }
    }

    private val MarkEntry.hasState get() = geom != null || style != null || text != null || alive != null

    /** Merges [m]'s registers into [e]; true if one changed. */
    private fun merge(e: MarkEntry, m: LinkMessage.Mark): Boolean {
        var changed = false
        m.geom?.let { if (wins(it, e.geom, MarkCodec::geom)) { e.geom = it; changed = true } }
        m.style?.let { if (wins(it, e.style, MarkCodec::style)) { e.style = it; changed = true } }
        m.text?.let { if (wins(it, e.text, MarkCodec::text)) { e.text = it; changed = true } }
        m.alive?.let { if (wins(it, e.alive, ::aliveBytes)) { e.alive = it; changed = true } }
        return changed
    }

    /** Whether [e] has a register [m] lacks or holds older. */
    private fun behind(e: MarkEntry, m: LinkMessage.Mark): Boolean {
        fun <T> mineNewer(mine: Reg<T>?, theirs: Reg<T>?, payload: (ByteWriter, T) -> Unit) =
            mine != null && wins(mine, theirs, payload)
        val mineGeom = e.geom
        val theirGeom = m.geom
        val geomBehind = when {
            mineGeom == null -> false
            theirGeom == null -> true
            // A part's payload isn't the shape's: only its version tells.
            (theirGeom.value as? Geom.Path)?.part != null -> theirGeom.version < mineGeom.version
            else -> wins(mineGeom, theirGeom, MarkCodec::geom)
        }
        return geomBehind || mineNewer(e.style, m.style, MarkCodec::style) ||
            mineNewer(e.text, m.text, MarkCodec::text) || mineNewer(e.alive, m.alive, ::aliveBytes)
    }

    companion object {
        /** Versions further ahead than this (a phone's clock gone wrong) don't move the clock. */
        const val FUTURE_S = 600L

        /** How long an incomplete shape waits for its parts. */
        const val WAIT_S = 600L
        const val MAX_WAITING = 64

        /**
         * Whether [new] replaces [old]: a higher version; at the same version a higher editor id
         * (unsigned); then the larger encoded payload. The same on every phone.
         */
        internal fun <T> wins(new: Reg<T>, old: Reg<T>?, payload: (ByteWriter, T) -> Unit): Boolean {
            if (old == null) return true
            if (new.version != old.version) return new.version > old.version
            if (new.editor != old.editor) return new.editor.toUInt() > old.editor.toUInt()
            val a = ByteWriter(32).also { payload(it, new.value) }.toByteArray()
            val b = ByteWriter(32).also { payload(it, old.value) }.toByteArray()
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d > 0
            }
            return a.size > b.size
        }
    }
}
