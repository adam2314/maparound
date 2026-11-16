package app.maparound.party

import app.maparound.shared.fileSystem
import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import okio.Path.Companion.toPath
import kotlin.math.roundToInt

// Plain Kotlin: each member's positions as this device received them, in the order they were
// taken, for their history on the map. Unit-tested.

/** A member's position taken at [at] (their clock, ms). */
data class HistoryPoint(val at: Long, val position: PartyMessage.Position)

/**
 * Every member's positions this device received while in the party, oldest first. A position that
 * arrives late (by radio, after newer ones online) goes in its place; the same moment twice counts
 * once. At most [maxPerMember] each: beyond that the oldest go.
 */
class PartyHistory(private val maxPerMember: Int = MAX_PER_MEMBER) {
    private val byMember = HashMap<Int, ArrayList<HistoryPoint>>()

    /** Changes whenever positions are added or removed (so the file knows when to save). */
    var version = 0
        private set

    /** Adds [member]'s position taken at [at]; false when it was there already (or too old to keep). */
    fun add(member: Int, at: Long, position: PartyMessage.Position): Boolean {
        val list = byMember.getOrPut(member) { ArrayList() }
        // Nearly always the newest: looked for from the end.
        var i = list.size
        while (i > 0 && list[i - 1].at > at) i--
        if (i > 0 && list[i - 1].at == at) return false
        if (i == 0 && list.size >= maxPerMember) return false
        list.add(i, HistoryPoint(at, position))
        if (list.size > maxPerMember) list.removeAt(0)
        version++
        return true
    }

    /** [member]'s positions, oldest first. */
    fun of(member: Int): List<HistoryPoint> = byMember[member]?.toList().orEmpty()

    /** How many positions each member has. */
    val counts: Map<Int, Int> get() = byMember.filterValues { it.isNotEmpty() }.mapValues { it.value.size }

    fun clear() {
        if (byMember.isEmpty()) return
        byMember.clear()
        version++
    }

    /**
     * For the file: 'H' 'S', format 1, the party's [tag], then per member its id, how many, and
     * each position: seconds (u32), lat/lon (1e-7°), height (m; −32768 unknown), accuracy, speed
     * (km/h; 255 unknown), heading (degrees; −1 unknown), battery (255 unknown), flags (1 SOS, 4 driving).
     */
    fun encode(tag: Int): ByteArray {
        val members = byMember.filterValues { it.isNotEmpty() }
        val w = ByteWriter(16 + members.values.sumOf { 8 + it.size * POINT_BYTES })
        w.byte('H'.code).byte('S'.code).byte(FORMAT).int(tag).int(members.size)
        for ((id, list) in members) {
            w.int(id).int(list.size)
            for (h in list) {
                val p = h.position
                w.int((h.at / 1000).toInt())
                w.int((p.lat * 1e7).roundToInt()).int((p.lon * 1e7).roundToInt())
                w.short(p.altM?.coerceIn(-32767, 32767) ?: Short.MIN_VALUE.toInt())
                w.byte(u8(p.accuracyM)).byte(u8(p.speedKmh))
                w.short(p.headingDeg?.let { (it % 360 + 360) % 360 } ?: -1)
                w.byte(u8(p.batteryPct))
                w.byte((if (p.sos) 1 else 0) or (if (p.driving) 4 else 0))
            }
        }
        return w.toByteArray()
    }

    companion object {
        /** About a day of positions every 20 seconds. */
        const val MAX_PER_MEMBER = 5000
        private const val FORMAT = 1
        private const val NONE = 255
        private const val POINT_BYTES = 20

        /** A history [encode]d for the party with [tag]; null when it's another party's or damaged. */
        fun decode(bytes: ByteArray, tag: Int): PartyHistory? = runCatching {
            val r = ByteReader(bytes)
            if (r.u8() != 'H'.code || r.u8() != 'S'.code || r.u8() != FORMAT || r.int() != tag) return null
            val history = PartyHistory()
            repeat(r.int()) {
                val id = r.int()
                repeat(r.int()) {
                    val at = (r.int().toLong() and 0xFFFFFFFFL) * 1000
                    val lat = r.int() / 1e7
                    val lon = r.int() / 1e7
                    val alt = r.short().toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                    val acc = opt(r.u8())
                    val speed = opt(r.u8())
                    val heading = r.short().toInt().takeIf { it >= 0 }
                    val battery = opt(r.u8())
                    val flags = r.u8()
                    history.add(id, at, PartyMessage.Position(lat, lon, alt, acc, speed, heading, battery, flags and 1 != 0, flags and 4 != 0))
                }
            }
            history
        }.getOrNull()

        private fun u8(v: Int?): Int = v?.coerceIn(0, 254) ?: NONE
        private fun opt(v: Int): Int? = v.takeIf { it != NONE }
    }
}

/**
 * The party's [PartyHistory] in a file at [path], so it outlasts the app being closed: read when
 * the party starts again, written at most every minute while it grows and when the party stops,
 * deleted when leaving it or joining another.
 */
class PartyHistoryFile(private val path: String) {
    private var savedVersion = -1
    private var savedAt = 0L

    /** The history kept for the party [key], or an empty one. */
    fun load(key: PartyKey): PartyHistory {
        val history = runCatching {
            val p = path.toPath()
            if (fileSystem.exists(p)) PartyHistory.decode(fileSystem.read(p) { readByteArray() }, key.tag) else null
        }.getOrNull() ?: PartyHistory()
        savedVersion = history.version
        return history
    }

    /** Writes [history] if it changed, at most every minute unless [now] is the last chance ([force]). */
    fun save(history: PartyHistory, key: PartyKey, now: Long, force: Boolean = false) {
        if (history.version == savedVersion) return
        if (!force && now - savedAt < SAVE_EVERY_MS) return
        runCatching {
            val p = path.toPath()
            p.parent?.let { fileSystem.createDirectories(it) }
            fileSystem.write(p) { write(history.encode(key.tag)) }
        }
        savedVersion = history.version
        savedAt = now
    }

    fun delete() {
        runCatching { fileSystem.delete(path.toPath(), mustExist = false) }
        savedVersion = -1
    }

    private companion object {
        const val SAVE_EVERY_MS = 60_000L
    }
}
