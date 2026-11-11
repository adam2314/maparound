package app.maparound.trails

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.SAXParserFactory
import kotlin.math.abs

// Plain Kotlin/JVM: marked hiking routes from OpenStreetMap route relations.
// Downloaded per 1°×1° cell from the Overpass API, stored in a compact binary file per cell.

/** Waymark colours the Topo style can draw. Unknown colours fall back to [OTHER]. */
enum class TrailColour(val rgb: Int) {
    RED(0xD32F2F), BLUE(0x1E63D6), GREEN(0x2E9E3E), YELLOW(0xF2C200), BLACK(0x222222),
    WHITE(0xFFFFFF), ORANGE(0xF57C00), PURPLE(0x8E24AA), BROWN(0x8D5524), OTHER(0xC2185B);

    val key: String get() = name.lowercase(Locale.ROOT)

    companion object {
        /** From `osmc:symbol` (its first field is the way colour) or `colour`. */
        fun of(osmcSymbol: String?, colour: String?): TrailColour {
            osmcSymbol?.substringBefore(':')?.let { named(it) }?.let { return it }
            colour?.let { named(it) ?: hex(it) }?.let { return it }
            return OTHER
        }

        private fun named(s: String): TrailColour? = when (s.trim().lowercase(Locale.ROOT)) {
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

class TrailCell(val routes: List<TrailRoute>, val ways: List<TrailWay>)

object TrailCellIO {
    private const val MAGIC = 0x4D415452 // "MATR"
    private const val VERSION = 1

    /** Parses Overpass XML (ways with `out geom`, relations with `out body`). */
    fun parseOverpass(input: InputStream): TrailCell {
        val h = OverpassHandler()
        SAXParserFactory.newInstance().newSAXParser().parse(input, h)
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
        return TrailCell(routes, ways)
    }

    fun write(cell: TrailCell, file: File) {
        DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(cell.routes.size)
            for (r in cell.routes) {
                out.writeUTF(r.name.take(200))
                out.writeUTF(r.ref.take(40))
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
        }
    }

    fun read(file: File): TrailCell = read(file.inputStream())

    fun read(input: InputStream): TrailCell =
        DataInputStream(BufferedInputStream(input, 1 shl 16)).use { inp ->
            require(inp.readInt() == MAGIC) { "Not a trail file" }
            require(inp.readInt() == VERSION) { "Unsupported trail file version" }
            val routes = List(inp.readInt()) {
                TrailRoute(inp.readUTF(), inp.readUTF(), inp.readByte().toInt(), TrailColour.entries[inp.readByte().toInt()])
            }
            val ways = List(inp.readInt()) {
                val id = inp.readLong()
                val r = IntArray(inp.readShort().toInt()) { inp.readShort().toInt() }
                val c = IntArray(inp.readInt()) { inp.readInt() }
                TrailWay(id, r, c)
            }
            TrailCell(routes, ways)
        }

    private class Relation(val tags: Map<String, String>, val wayRefs: List<Long>)

    private class OverpassHandler : DefaultHandler() {
        val ways = LinkedHashMap<Long, IntArray>()
        val relations = ArrayList<Relation>()
        private var wayId = -1L
        private var coords = IntList()
        private var inRelation = false
        private var tags = HashMap<String, String>()
        private var refs = ArrayList<Long>()

        override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
            when (qName) {
                "way" -> if (!inRelation) {
                    wayId = a.getValue("id").toLong()
                    coords = IntList()
                }
                "nd" -> if (wayId >= 0) {
                    val lat = a.getValue("lat")?.toDoubleOrNull()
                    val lon = a.getValue("lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        coords.add(Math.round(lat * 1e6).toInt())
                        coords.add(Math.round(lon * 1e6).toInt())
                    }
                }
                "relation" -> {
                    inRelation = true
                    tags = HashMap()
                    refs = ArrayList()
                }
                "member" -> if (inRelation && a.getValue("type") == "way") a.getValue("ref")?.toLongOrNull()?.let(refs::add)
                "tag" -> if (inRelation) tags[a.getValue("k")] = a.getValue("v")
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (qName) {
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
