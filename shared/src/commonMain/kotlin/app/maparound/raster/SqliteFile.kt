package app.maparound.raster

import app.maparound.i18n.T
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

// A small read-only reader for SQLite database files (https://www.sqlite.org/fileformat.html),
// enough for tile databases: the schema, table rows by rowid, full scans and index lookups. It
// reads through [RandomRead], so a file the user picked is read where it is (Android can't open
// picked files with its own SQLite) and the same code can run on iOS. No SQL, no journal: files
// are expected to be complete (as exported), not being written.

class SqliteException(message: String) : Exception(message)

class SqliteFile(private val source: RandomRead, val sizeBytes: Long) {
    /** An entry of the schema table: a table, index or view. */
    class SchemaEntry(val type: String, val name: String, val table: String, val rootPage: Int, val sql: String?)

    /** One row: its rowid and column values (Long, Double, String, ByteArray or null). */
    class Row(val rowid: Long, val values: List<Any?>)

    /** Recently read pages, oldest use first (before init: reading the schema needs it). */
    private val cache = LinkedHashMap<Int, ByteArray>()
    private val lock = SynchronizedObject()

    val pageSize: Int
    private val usable: Int
    private val utf16: Int // 0 = UTF-8, 1 = UTF-16le, 2 = UTF-16be
    val schema: List<SchemaEntry>

    init {
        val h = source.read(0, 100)
        if (h.size < 100 || h.copyOf(16).decodeToString() != "SQLite format 3\u0000") throw SqliteException(T.offline.notSqliteDatabase)
        val ps = u16(h, 16)
        pageSize = if (ps == 1) 65_536 else ps
        if (pageSize < 512 || pageSize and (pageSize - 1) != 0) throw SqliteException("Bad page size $pageSize")
        usable = pageSize - (h[20].toInt() and 0xFF)
        utf16 = when (u32(h, 56)) { 2 -> 1; 3 -> 2; else -> 0 }
        schema = buildList {
            scan(1) { r ->
                add(SchemaEntry(r.text(0) ?: "", r.text(1) ?: "", r.text(2) ?: "", (r.values[3] as? Long)?.toInt() ?: 0, r.text(4)))
                true
            }
        }
    }

    // ---- pages ----------------------------------------------------------------------------------

    private fun page(n: Int): ByteArray {
        if (n < 1 || (n - 1).toLong() * pageSize >= sizeBytes) throw SqliteException("Page $n is outside the file")
        // Re-inserting a page marks it as used last.
        val cached = synchronized(lock) { cache.remove(n)?.also { cache[n] = it } }
        if (cached != null) return cached
        val p = source.read((n - 1).toLong() * pageSize, pageSize)
        if (p.size < pageSize) throw SqliteException("The file ends early")
        synchronized(lock) {
            cache[n] = p
            while (cache.size > CACHED_PAGES) cache.remove(cache.keys.first())
        }
        return p
    }

    /** A b-tree page: its type and where its cells are. */
    private inner class Node(val number: Int) {
        val data = page(number)
        private val base = if (number == 1) 100 else 0
        val type = data[base].toInt() and 0xFF
        val count = u16(data, base + 3)
        val interior = type == INDEX_INTERIOR || type == TABLE_INTERIOR
        val rightmost = if (interior) u32(data, base + 8) else 0
        private val cellsAt = base + if (interior) 12 else 8

        init {
            if (type != INDEX_INTERIOR && type != TABLE_INTERIOR && type != INDEX_LEAF && type != TABLE_LEAF) {
                throw SqliteException("Page $number isn't a b-tree page")
            }
        }

        fun cell(i: Int): Int = u16(data, cellsAt + 2 * i)

        /** Left child of interior cell [i]. */
        fun child(i: Int): Int = u32(data, cell(i))

        /** Rowid key of a table interior cell. */
        fun interiorRowid(i: Int): Long = varint(data, cell(i) + 4).value

        /** The record of a leaf cell, or of an index interior cell. */
        fun record(i: Int): Row {
            var at = cell(i)
            if (type == INDEX_INTERIOR) at += 4
            val size = varint(data, at)
            at = size.next
            var rowid = 0L
            if (type == TABLE_LEAF) {
                val r = varint(data, at)
                rowid = r.value
                at = r.next
            }
            val payload = payload(data, at, size.value, index = type == INDEX_LEAF || type == INDEX_INTERIOR)
            return decode(payload, rowid)
        }
    }

    /** A cell's payload, following overflow pages when it doesn't fit. */
    private fun payload(data: ByteArray, at: Int, size: Long, index: Boolean): ByteArray {
        val p = size.toInt()
        val x = if (index) (usable - 12) * 64 / 255 - 23 else usable - 35
        if (p <= x) return data.copyOfRange(at, at + p)
        val m = (usable - 12) * 32 / 255 - 23
        val k = m + (p - m) % (usable - 4)
        val local = if (k <= x) k else m
        val out = ByteArray(p)
        data.copyInto(out, 0, at, at + local)
        var filled = local
        var next = u32(data, at + local)
        var guard = 0
        while (filled < p) {
            if (next == 0 || ++guard > 1_000_000) throw SqliteException("Broken overflow chain")
            val o = page(next)
            val n = minOf(usable - 4, p - filled)
            o.copyInto(out, filled, 4, 4 + n)
            filled += n
            next = u32(o, 0)
        }
        return out
    }

    /** A record's columns (https://www.sqlite.org/fileformat.html#record_format). */
    private fun decode(p: ByteArray, rowid: Long): Row {
        val hs = varint(p, 0)
        var h = hs.next
        var body = hs.value.toInt()
        val values = ArrayList<Any?>(8)
        while (h < hs.value) {
            val t = varint(p, h)
            h = t.next
            val st = t.value
            val (v, n) = when {
                st == 0L -> null to 0
                st in 1L..6L -> {
                    val len = intArrayOf(0, 1, 2, 3, 4, 6, 8)[st.toInt()]
                    var x = p[body].toLong() // sign from the first byte
                    for (j in 1 until len) x = (x shl 8) or (p[body + j].toLong() and 0xFF)
                    x to len
                }
                st == 7L -> {
                    var x = 0L
                    for (j in 0 until 8) x = (x shl 8) or (p[body + j].toLong() and 0xFF)
                    Double.fromBits(x) to 8
                }
                st == 8L -> 0L to 0
                st == 9L -> 1L to 0
                st >= 12 && st % 2 == 0L -> ((st - 12) / 2).toInt().let { len -> p.copyOfRange(body, body + len) to len }
                st >= 13 -> ((st - 13) / 2).toInt().let { len -> text(p, body, len) to len }
                else -> throw SqliteException("Unknown column type $st")
            }
            values += v
            body += n
        }
        return Row(rowid, values)
    }

    private fun text(p: ByteArray, at: Int, len: Int): String = when (utf16) {
        0 -> p.decodeToString(at, at + len)
        else -> CharArray(len / 2) { i ->
            val a = p[at + 2 * i].toInt() and 0xFF
            val b = p[at + 2 * i + 1].toInt() and 0xFF
            (if (utf16 == 1) (b shl 8) or a else (a shl 8) or b).toChar()
        }.concatToString()
    }

    // ---- tables and indexes -----------------------------------------------------------------------

    /** Every row of the table with b-tree root [root], in rowid order, until [visit] returns false. */
    fun scan(root: Int, visit: (Row) -> Boolean) {
        fun walk(n: Int, depth: Int): Boolean {
            if (depth > 64) throw SqliteException("B-tree too deep")
            val node = Node(n)
            if (!node.interior) {
                for (i in 0 until node.count) if (!visit(node.record(i))) return false
                return true
            }
            for (i in 0 until node.count) if (!walk(node.child(i), depth + 1)) return false
            return walk(node.rightmost, depth + 1)
        }
        walk(root, 0)
    }

    /** The row with [rowid] in the table with b-tree root [root]. */
    fun row(root: Int, rowid: Long): Row? {
        var n = root
        repeat(64) {
            val node = Node(n)
            if (!node.interior) {
                // Cells are in rowid order: binary search.
                var lo = 0
                var hi = node.count - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val r = node.record(mid)
                    when {
                        r.rowid < rowid -> lo = mid + 1
                        r.rowid > rowid -> hi = mid - 1
                        else -> return r
                    }
                }
                return null
            }
            var next = node.rightmost
            var lo = 0
            var hi = node.count - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (node.interiorRowid(mid) >= rowid) { next = node.child(mid); hi = mid - 1 } else lo = mid + 1
            }
            n = next
        }
        throw SqliteException("B-tree too deep")
    }

    /**
     * Index entries (their column values, then the rowid) whose first columns equal [key], in
     * order, until [visit] returns false. An empty [key] visits every entry.
     */
    fun seek(root: Int, key: List<Any?>, visit: (List<Any?>) -> Boolean) {
        fun cmp(r: Row) = compareKey(r.values, key)
        fun walk(n: Int, depth: Int): Boolean { // false: stop
            if (depth > 64) throw SqliteException("B-tree too deep")
            val node = Node(n)
            if (!node.interior) {
                for (i in 0 until node.count) {
                    val r = node.record(i)
                    val c = cmp(r)
                    if (c < 0) continue
                    if (c > 0 || !visit(r.values)) return false
                }
                return true
            }
            for (i in 0 until node.count) {
                val r = node.record(i)
                val c = cmp(r)
                if (c < 0) continue // everything left of it is smaller still
                if (!walk(node.child(i), depth + 1)) return false
                if (c > 0 || !visit(r.values)) return false
            }
            return walk(node.rightmost, depth + 1)
        }
        walk(root, 0)
    }

    // ---- schema helpers ---------------------------------------------------------------------------

    fun entry(name: String): SchemaEntry? = schema.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** The column names of a table, in order, and which one is an alias of the rowid (INTEGER PRIMARY KEY). */
    fun columns(table: String): Pair<List<String>, Int> {
        val sql = entry(table)?.sql ?: throw SqliteException("No table $table")
        val names = ArrayList<String>()
        var rowidAlias = -1
        for (part in definitions(sql)) {
            val words = part.trim().split(Regex("\\s+"))
            val first = words.first().lowercase()
            if (first in CONSTRAINTS) continue
            if (part.contains(Regex("(?i)^\\s*\\S+\\s+integer\\s+primary\\s+key"))) rowidAlias = names.size
            names += unquote(words.first())
        }
        return names to rowidAlias
    }

    /** The columns an index covers: from its CREATE INDEX, or the table's key for an automatic one. */
    fun indexColumns(index: SchemaEntry): List<String> {
        val sql = index.sql
        if (sql != null) return definitions(sql).map { unquote(it.trim().split(Regex("\\s+")).first()) }
        // sqlite_autoindex_<table>_N: the table's PRIMARY KEY (…) or UNIQUE (…).
        val table = entry(index.table)?.sql ?: return emptyList()
        for (part in definitions(table)) {
            val m = Regex("(?i)^\\s*(?:constraint\\s+\\S+\\s+)?(?:primary\\s+key|unique)\\s*\\((.*)\\)").find(part) ?: continue
            return m.groupValues[1].split(',').map { unquote(it.trim().split(Regex("\\s+")).first()) }
        }
        return definitions(table).firstOrNull { it.contains(Regex("(?i)primary\\s+key")) }
            ?.let { listOf(unquote(it.trim().split(Regex("\\s+")).first())) } ?: emptyList()
    }

    /** An index of [table] whose columns start with [columns]. */
    fun indexOn(table: String, columns: List<String>): SchemaEntry? = schema.firstOrNull { e ->
        e.type == "index" && e.table.equals(table, true) && e.rootPage > 0 &&
            indexColumns(e).map { it.lowercase() }.take(columns.size) == columns.map { it.lowercase() }
    }

    fun isWithoutRowid(table: String) = entry(table)?.sql?.contains(Regex("(?i)without\\s+rowid")) == true

    companion object {
        private const val CACHED_PAGES = 512
        private const val INDEX_INTERIOR = 2
        private const val TABLE_INTERIOR = 5
        private const val INDEX_LEAF = 10
        private const val TABLE_LEAF = 13
        private val CONSTRAINTS = setOf("primary", "unique", "check", "foreign", "constraint")

        private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)
        private fun u32(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

        private class Varint(val value: Long, val next: Int)

        private fun varint(b: ByteArray, at: Int): Varint {
            var v = 0L
            for (i in 0 until 8) {
                val x = b[at + i].toInt() and 0xFF
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) return Varint(v, at + i + 1)
            }
            return Varint((v shl 8) or (b[at + 8].toLong() and 0xFF), at + 9)
        }

        /** The comma-separated parts between the outermost parentheses of a CREATE statement. */
        fun definitions(sql: String): List<String> {
            val start = sql.indexOf('(')
            if (start < 0) return emptyList()
            val parts = ArrayList<String>()
            var depth = 0
            var from = start + 1
            var quote: Char? = null
            for (i in start + 1 until sql.length) {
                val c = sql[i]
                if (quote != null) { if (c == quote) quote = null; continue }
                when (c) {
                    '"', '\'', '`' -> quote = c
                    '[' -> quote = ']'
                    '(' -> depth++
                    ')' -> if (depth == 0) { parts += sql.substring(from, i); return parts.filter { it.isNotBlank() } } else depth--
                    ',' -> if (depth == 0) { parts += sql.substring(from, i); from = i + 1 }
                }
            }
            return parts.filter { it.isNotBlank() }
        }

        private fun unquote(s: String) = s.trim().trim('"', '`', '\'', '[', ']')

        /** SQLite's order for the first [key].size values: NULL < numbers < text < blob. */
        fun compareKey(values: List<Any?>, key: List<Any?>): Int {
            for (i in key.indices) {
                val c = compareValue(values.getOrNull(i), key[i])
                if (c != 0) return c
            }
            return 0
        }

        private fun rank(v: Any?) = when (v) { null -> 0; is Long, is Double, is Int -> 1; is String -> 2; else -> 3 }

        private fun compareValue(a: Any?, b: Any?): Int {
            val ra = rank(a)
            val rb = rank(b)
            if (ra != rb) return ra.compareTo(rb)
            return when (a) {
                null -> 0
                is Long -> if (b is Double) a.toDouble().compareTo(b) else a.compareTo((b as Number).toLong())
                is Int -> a.toLong().compareTo((b as Number).toLong())
                is Double -> a.compareTo((b as Number).toDouble())
                is String -> {
                    val x = a.encodeToByteArray()
                    val y = (b as String).encodeToByteArray()
                    for (i in 0 until minOf(x.size, y.size)) {
                        val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                        if (d != 0) return d
                    }
                    x.size - y.size
                }
                is ByteArray -> {
                    val y = b as ByteArray
                    for (i in 0 until minOf(a.size, y.size)) {
                        val d = (a[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
                        if (d != 0) return d
                    }
                    a.size - y.size
                }
                else -> 0
            }
        }
    }
}

private fun SqliteFile.Row.text(i: Int): String? = values.getOrNull(i) as? String
