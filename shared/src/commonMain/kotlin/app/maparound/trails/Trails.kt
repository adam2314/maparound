package app.maparound.trails

import app.maparound.shared.fileSystem
import app.maparound.shared.util.Xml
import app.maparound.shared.util.XmlHandler
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.math.abs
import kotlin.math.roundToLong

// Plain Kotlin (Android and iOS): marked hiking routes from OpenStreetMap route relations.
// Downloaded per 1°×1° cell from the Overpass API, stored in a compact binary file per cell.

/** Waymark colours the Topo style can draw. Unknown colours fall back to [OTHER]. */
enum class TrailColour(val rgb: Int) {
    RED(0xD32F2F), BLUE(0x1E63D6), GREEN(0x2E9E3E), YELLOW(0xF2C200), BLACK(0x222222),
    WHITE(0xFFFFFF), ORANGE(0xF57C00), PURPLE(0x8E24AA), BROWN(0x8D5524), OTHER(0xC2185B);

    val key: String get() = name.lowercase()

    companion object {
        /** From `osmc:symbol` (its first field is the way colour) or `colour`. */
        fun of(osmcSymbol: String?, colour: String?): TrailColour {
            osmcSymbol?.substringBefore(':')?.let { named(it) }?.let { return it }
            colour?.let { named(it) ?: hex(it) }?.let { return it }
            return OTHER
        }

        private fun named(s: String): TrailColour? = when (s.trim().lowercase()) {
            "red", "darkred" -> RED
            "blue", "darkblue", "lightblue", "aqua", "cyan" -> BLUE
            "green", "darkgreen", "lightgreen", "lime" -> GREEN
            "yellow", "gold" -> YELLOW
            "black" -> BLACK
            "white" -> WHITE
            "orange" -> ORANGE
            "purple", "violet", "magenta", "pink" -> PURPLE
            "brown" -> BROWN
            else -> null
        }

        /** "#ff0000" -> nearest palette colour. */
        private fun hex(s: String): TrailColour? {
            val v = s.trim().removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16) ?: return null
            return entries.filter { it != OTHER }.minBy { c ->
                abs((c.rgb shr 16 and 255) - (v shr 16 and 255)) +
                    abs((c.rgb shr 8 and 255) - (v shr 8 and 255)) + abs((c.rgb and 255) - (v and 255))
            }
        }
    }
}

/** A route relation. [network] 0 = international … 3 = local, 4 = unknown (for label priority). */
data class TrailRoute(val name: String, val ref: String, val network: Int, val colour: TrailColour)

/** A way that belongs to one or more routes; coordinates in micro-degrees (lat, lon pairs). */
class TrailWay(val id: Long, val routes: IntArray, val coords: IntArray)

/**
 * A rock or terrain line that the mapsforge maps leave out (their tag mapping has no
 * `natural=cliff`), fetched with the trails so the Topo style can draw it.
 */
class TrailFeature(val id: Long, val kind: Kind, val coords: IntArray) {
    enum class Kind(val tag: String) {
        CLIFF("cliff"), ARETE("arete"), RIDGE("ridge"), EARTH_BANK("earth_bank");

        companion object {
            fun of(natural: String?): Kind? = entries.firstOrNull { it.tag == natural }
        }
    }
}

class TrailCell(
    val routes: List<TrailRoute>,
    val ways: List<TrailWay>,
    val features: List<TrailFeature> = emptyList(),
    /** Downloaded together with the cliffs (format 2); older cells need fetching again for them. */
    val withFeatures: Boolean = true,
)

/**
 * Trail cells: Overpass XML in, a small binary file out. The file layout is the one Java's
 * DataOutputStream wrote in earlier versions (big-endian, strings as "modified UTF-8"), so
 * files already on a phone stay readable.
 */
object TrailCellIO {
    private const val MAGIC = 0x4D415452 // "MATR"
    /** 1: routes and their ways; 2: also cliffs, arêtes, ridges and earth banks ([TrailFeature]). */
    private const val VERSION = 2

    /** The `natural=*` lines to fetch with the trails, as an Overpass value regex. */
    val FEATURE_REGEX = "^(" + TrailFeature.Kind.entries.joinToString("|") { it.tag } + ")$"

    /** Reads only a cell file's header: whether it has the cliffs (format 2). */
    fun hasFeatures(path: String): Boolean = runCatching {
        fileSystem.source(path.toPath()).buffer().use { it.readInt() == MAGIC && it.readInt() >= 2 }
    }.getOrDefault(false)

    /**
     * Parses Overpass XML (ways with `out geom`, relations with `out body`), streamed. Ways in a
     * route relation become trail ways; ways tagged with a [TrailFeature.Kind] become features.
     */
    fun parseOverpass(input: BufferedSource): TrailCell {
        val h = OverpassHandler()
        Xml.parse(input, h)
        val routes = ArrayList<TrailRoute>()
        val wayRoutes = HashMap<Long, MutableList<Int>>()
        for (rel in h.relations) {
            val t = rel.tags
            val index = routes.size
            routes += TrailRoute(
                name = t["name"] ?: t["ref"] ?: "",
                ref = t["ref"] ?: "",
                network = when (t["network"]) {
                    "iwn" -> 0; "nwn" -> 1; "rwn" -> 2; "lwn" -> 3; else -> 4
                },
                colour = TrailColour.of(t["osmc:symbol"], t["colour"]),
            )
            for (w in rel.wayRefs) wayRoutes.getOrPut(w) { ArrayList() } += index
        }
        val ways = h.ways.mapNotNull { (id, coords) ->
            val r = wayRoutes[id] ?: return@mapNotNull null
            if (coords.size < 4) null else TrailWay(id, r.distinct().toIntArray(), coords)
        }
        val features = h.natural.mapNotNull { (id, natural) ->
            val kind = TrailFeature.Kind.of(natural) ?: return@mapNotNull null
            h.ways[id]?.takeIf { it.size >= 4 }?.let { TrailFeature(id, kind, it) }
        }
        return TrailCell(routes, ways, features)
    }

    fun parseOverpass(xml: String): TrailCell = parseOverpass(Buffer().writeUtf8(xml))

    fun write(cell: TrailCell, path: String) {
        fileSystem.sink(path.toPath()).buffer().use { write(cell, it) }
    }

    fun write(cell: TrailCell, out: BufferedSink) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(cell.routes.size)
        for (r in cell.routes) {
            writeUtf(out, r.name.take(200))
            writeUtf(out, r.ref.take(40))
            out.writeByte(r.network)
            out.writeByte(r.colour.ordinal)
        }
        out.writeInt(cell.ways.size)
        for (w in cell.ways) {
            out.writeLong(w.id)
            out.writeShort(w.routes.size)
            w.routes.forEach { out.writeShort(it) }
            out.writeInt(w.coords.size)
            w.coords.forEach { out.writeInt(it) }
        }
        out.writeInt(cell.features.size)
        for (f in cell.features) {
            out.writeLong(f.id)
            out.writeByte(f.kind.ordinal)
            out.writeInt(f.coords.size)
            f.coords.forEach { out.writeInt(it) }
        }
    }

    fun read(path: String): TrailCell = fileSystem.source(path.toPath()).buffer().use { read(it) }

    fun read(inp: BufferedSource): TrailCell {
        require(inp.readInt() == MAGIC) { "Not a trail file" }
        val version = inp.readInt()
        require(version in 1..VERSION) { "Unsupported trail file version" }
        val routes = List(inp.readInt()) {
            TrailRoute(readUtf(inp), readUtf(inp), inp.readByte().toInt(), TrailColour.entries[inp.readByte().toInt()])
        }
        val ways = List(inp.readInt()) {
            val id = inp.readLong()
            val r = IntArray(inp.readShort().toInt()) { inp.readShort().toInt() }
            val c = IntArray(inp.readInt()) { inp.readInt() }
            TrailWay(id, r, c)
        }
        if (version < 2) return TrailCell(routes, ways, withFeatures = false)
        val features = List(inp.readInt()) {
            val id = inp.readLong()
            val kind = TrailFeature.Kind.entries[inp.readByte().toInt()]
            TrailFeature(id, kind, IntArray(inp.readInt()) { inp.readInt() })
        }
        return TrailCell(routes, ways, features)
    }

    /** DataOutputStream.writeUTF: a 2-byte length, then modified UTF-8 (U+0000 as two bytes, surrogates one by one). */
    internal fun writeUtf(out: BufferedSink, s: String) {
        val b = Buffer()
        for (ch in s) {
            val c = ch.code
            when {
                c in 0x0001..0x007F -> b.writeByte(c)
                c <= 0x07FF -> b.writeByte(0xC0 or (c shr 6)).writeByte(0x80 or (c and 0x3F))
                else -> b.writeByte(0xE0 or (c shr 12)).writeByte(0x80 or ((c shr 6) and 0x3F)).writeByte(0x80 or (c and 0x3F))
            }
        }
        require(b.size <= 0xFFFF) { "String too long" }
        out.writeShort(b.size.toInt())
        out.writeAll(b)
    }

    /** DataInputStream.readUTF. */
    internal fun readUtf(inp: BufferedSource): String {
        val n = inp.readShort().toInt() and 0xFFFF
        val bytes = inp.readByteArray(n.toLong())
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            val a = bytes[i].toInt() and 0xFF
            when {
                a < 0x80 -> {
                    sb.append(a.toChar())
                    i++
                }
                a and 0xE0 == 0xC0 && i + 1 < n -> {
                    sb.append((((a and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)).toChar())
                    i += 2
                }
                a and 0xF0 == 0xE0 && i + 2 < n -> {
                    sb.append((((a and 0x0F) shl 12) or ((bytes[i + 1].toInt() and 0x3F) shl 6) or (bytes[i + 2].toInt() and 0x3F)).toChar())
                    i += 3
                }
                else -> throw IllegalArgumentException("Malformed string in trail file")
            }
        }
        return sb.toString()
    }

    private class Relation(val tags: Map<String, String>, val wayRefs: List<Long>)

    private class OverpassHandler : XmlHandler {
        val ways = LinkedHashMap<Long, IntArray>()
        /** The `natural=*` value of ways that have one. */
        val natural = LinkedHashMap<Long, String>()
        val relations = ArrayList<Relation>()
        private var wayId = -1L
        private var coords = IntList()
        private var inRelation = false
        private var tags = HashMap<String, String>()
        private var refs = ArrayList<Long>()

        override fun start(name: String, attrs: Map<String, String>) {
            when (name) {
                "way" -> if (!inRelation) {
                    wayId = attrs["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Way without id")
                    coords = IntList()
                }
                "nd" -> if (wayId >= 0) {
                    val lat = attrs["lat"]?.toDoubleOrNull()
                    val lon = attrs["lon"]?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        coords.add((lat * 1e6).roundToLong().toInt())
                        coords.add((lon * 1e6).roundToLong().toInt())
                    }
                }
                "relation" -> {
                    inRelation = true
                    tags = HashMap()
                    refs = ArrayList()
                }
                "member" -> if (inRelation && attrs["type"] == "way") attrs["ref"]?.toLongOrNull()?.let(refs::add)
                "tag" -> {
                    val k = attrs["k"]
                    val v = attrs["v"]
                    if (k != null && v != null) {
                        if (inRelation) tags[k] = v else if (wayId >= 0 && k == "natural") natural[wayId] = v
                    }
                }
            }
        }

        override fun end(name: String) {
            when (name) {
                "way" -> if (wayId >= 0) {
                    ways[wayId] = coords.toArray()
                    wayId = -1
                }
                "relation" -> {
                    relations += Relation(tags, refs)
                    inRelation = false
                }
            }
        }
    }

    private class IntList {
        private var a = IntArray(64)
        private var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }
}
