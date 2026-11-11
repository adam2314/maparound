package app.maparound.shared.mapsforge

import okio.FileHandle
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

// A reader for mapsforge binary map files (".map", format versions 3–5), in common Kotlin so the
// same country maps work on Android and iOS. Only reading, no rendering. Follows the published
// mapsforge file specification; checked against mapsforge's own reader in the tests.

class MapsforgeException(message: String) : Exception(message)

data class MfTag(val key: String, val value: String)

class MfPoi(val lat: Double, val lon: Double, val layer: Int, val tags: List<MfTag>)

/** One way: an outer ring/line, then any inner rings, in degrees as [lat0, lon0, lat1, lon1, …]. */
class MfWay(val layer: Int, val tags: List<MfTag>, val rings: List<DoubleArray>, val labelLat: Double?, val labelLon: Double?)

class MfData(val pois: List<MfPoi>, val ways: List<MfWay>, /** Every block read was all water. */ val isWater: Boolean)

enum class MfSelector { ALL, POIS, NAMED }

class MfHeader(
    val version: Int,
    val fileSize: Long,
    val date: Long,
    val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double,
    val tileSize: Int,
    val projection: String,
    val debug: Boolean,
    val startLat: Double?, val startLon: Double?, val startZoom: Int?,
    val languages: String?, val comment: String?, val createdBy: String?,
    val poiTags: List<MfTag>,
    val wayTags: List<MfTag>,
    val intervals: List<SubFile>,
) {
    class SubFile(val baseZoom: Int, val minZoom: Int, val maxZoom: Int, val start: Long, val size: Long) {
        // Blocks of this sub-file at its base zoom, set from the map's bounding box.
        var left = 0; var top = 0; var right = 0; var bottom = 0
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        val blocks: Long get() = width.toLong() * height
        var indexStart = 0L
    }

    val minZoom: Int get() = intervals.minOf { it.minZoom }
    val maxZoom: Int get() = intervals.maxOf { it.maxZoom }

    /** The sub-file used for a zoom level: the interval containing it, else the nearest. */
    fun subFileFor(zoom: Int): SubFile =
        intervals.firstOrNull { zoom in it.minZoom..it.maxZoom }
            ?: if (zoom < minZoom) intervals.minBy { it.minZoom } else intervals.maxBy { it.maxZoom }
}

class MapsforgeFile(private val file: FileHandle) {
    val header: MfHeader = readHeader()
    private val indexCache = HashMap<MfHeader.SubFile, ByteArray>()

    /**
     * Map data for tile z/x/y (Web Mercator XYZ). Mirrors mapsforge's query: data from the
     * zoom interval of [z]; POIs and ways that appear up to the query zoom; when the tile is
     * smaller than a stored block, ways are picked by the block's 4×4 sub-tile bitmask and POIs
     * by the tile's bounds.
     */
    fun read(z: Int, x: Int, y: Int, selector: MfSelector = MfSelector.ALL): MfData {
        val queryZoom = z.coerceIn(header.minZoom, header.maxZoom)
        val sub = header.subFileFor(queryZoom)
        val bz = sub.baseZoom
        val fromX: Int; val toX: Int; val fromY: Int; val toY: Int
        var bitmask = 0xFFFF
        var filter = false
        when {
            z < bz -> {
                val d = bz - z
                fromX = x shl d; toX = ((x + 1) shl d) - 1
                fromY = y shl d; toY = ((y + 1) shl d) - 1
            }
            z > bz -> {
                val d = z - bz
                fromX = x shr d; toX = fromX
                fromY = y shr d; toY = fromY
                bitmask = tileBitmask(x, y, d)
                filter = true
            }
            else -> { fromX = x; toX = x; fromY = y; toY = y }
        }
        val pois = ArrayList<MfPoi>()
        val ways = ArrayList<MfWay>()
        var allWater = true
        var anyBlock = false
        val north = tileLat(y, z)
        val south = tileLat(y + 1, z)
        val west = tileLon(x, z)
        val east = tileLon(x + 1, z)
        for (by in maxOf(fromY, sub.top)..minOf(toY, sub.bottom)) {
            for (bx in maxOf(fromX, sub.left)..minOf(toX, sub.right)) {
                val (offset, water, next) = blockEntry(sub, bx - sub.left, by - sub.top)
                anyBlock = true
                if (!water) allWater = false
                val size = next - offset
                if (size <= 0) continue
                if (size > MAX_BLOCK) throw MapsforgeException("Block too large: $size bytes")
                val buf = Reader(readAt(sub.start + offset, size.toInt()))
                processBlock(buf, sub, queryZoom, bx, by, if (filter) bitmask else null, selector, pois, ways,
                    if (filter) doubleArrayOf(south, west, north, east) else null)
            }
        }
        return MfData(pois, ways, anyBlock && allWater && pois.isEmpty() && ways.isEmpty())
    }

    /**
     * Everything stored for [queryZoom] in an area, like mapsforge's readMapData / readNamedItems
     * over a tile range: POIs inside the area, ways whose outline touches it.
     */
    fun readArea(
        queryZoom: Int, minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        selector: MfSelector = MfSelector.ALL,
    ): MfData {
        val qz = queryZoom.coerceIn(header.minZoom, header.maxZoom)
        val sub = header.subFileFor(qz)
        val bz = sub.baseZoom
        val pois = ArrayList<MfPoi>()
        val ways = ArrayList<MfWay>()
        val area = doubleArrayOf(minLat, minLon, maxLat, maxLon)
        for (by in maxOf(latToTileY(maxLat, bz), sub.top)..minOf(latToTileY(minLat, bz), sub.bottom)) {
            for (bx in maxOf(lonToTileX(minLon, bz), sub.left)..minOf(lonToTileX(maxLon, bz), sub.right)) {
                val (offset, _, next) = blockEntry(sub, bx - sub.left, by - sub.top)
                val size = next - offset
                if (size <= 0) continue
                if (size > MAX_BLOCK) throw MapsforgeException("Block too large: $size bytes")
                val from = ways.size
                processBlock(Reader(readAt(sub.start + offset, size.toInt())), sub, qz, bx, by, null, selector, pois, ways, area)
                // Like mapsforge's way filter: drop ways of edge blocks that lie wholly outside.
                val kept = ways.subList(from, ways.size).filter { touches(it, area) }
                while (ways.size > from) ways.removeAt(ways.size - 1)
                ways += kept
            }
        }
        return MfData(pois, ways, false)
    }

    private fun touches(w: MfWay, a: DoubleArray): Boolean {
        for (ring in w.rings) {
            var s = 90.0; var n = -90.0; var we = 180.0; var e = -180.0
            for (i in ring.indices step 2) {
                s = minOf(s, ring[i]); n = maxOf(n, ring[i])
                we = minOf(we, ring[i + 1]); e = maxOf(e, ring[i + 1])
            }
            if (s <= a[2] && n >= a[0] && we <= a[3] && e >= a[1]) return true
        }
        return false
    }

    // ---- header ----------------------------------------------------------------------------------

    private fun readHeader(): MfHeader {
        val first = Reader(readAt(0, 24))
        val magic = first.bytes(20).decodeToString()
        if (magic != "mapsforge binary OSM") throw MapsforgeException("Not a mapsforge map file")
        val headerSize = first.int()
        if (headerSize !in 70..1_000_000) throw MapsforgeException("Bad header size $headerSize")
        val r = Reader(readAt(24, headerSize))
        val version = r.int()
        if (version !in 3..5) throw MapsforgeException("Unsupported map file version $version")
        val fileSize = r.long()
        val date = r.long()
        val minLat = r.int() / 1e6
        val minLon = r.int() / 1e6
        val maxLat = r.int() / 1e6
        val maxLon = r.int() / 1e6
        val tileSize = r.short()
        val projection = r.string()
        val flags = r.byte()
        val debug = flags and 0x80 != 0
        var startLat: Double? = null
        var startLon: Double? = null
        if (flags and 0x40 != 0) { startLat = r.int() / 1e6; startLon = r.int() / 1e6 }
        val startZoom = if (flags and 0x20 != 0) r.byte() else null
        val languages = if (flags and 0x10 != 0) r.string() else null
        val comment = if (flags and 0x08 != 0) r.string() else null
        val createdBy = if (flags and 0x04 != 0) r.string() else null
        val poiTags = List(r.short()) { tag(r.string()) }
        val wayTags = List(r.short()) { tag(r.string()) }
        val n = r.byte()
        if (n !in 1..32) throw MapsforgeException("Bad zoom interval count $n")
        val intervals = List(n) { MfHeader.SubFile(r.byte(), r.byte(), r.byte(), r.long(), r.long()) }
        for (s in intervals) {
            s.left = lonToTileX(minLon, s.baseZoom)
            s.right = lonToTileX(maxLon, s.baseZoom)
            s.top = latToTileY(maxLat, s.baseZoom)
            s.bottom = latToTileY(minLat, s.baseZoom)
            s.indexStart = s.start + if (debug) 16 else 0
        }
        return MfHeader(version, fileSize, date, minLat, minLon, maxLat, maxLon, tileSize, projection, debug,
            startLat, startLon, startZoom, languages, comment, createdBy, poiTags, wayTags, intervals)
    }

    private fun tag(s: String): MfTag {
        val i = s.indexOf('=')
        return if (i < 0) MfTag(s, "") else MfTag(s.substring(0, i), s.substring(i + 1))
    }

    // ---- blocks ----------------------------------------------------------------------------------

    /** (offset of the block in the sub-file, all-water flag, offset of the next block). */
    private fun blockEntry(sub: MfHeader.SubFile, col: Int, row: Int): Triple<Long, Boolean, Long> {
        val index = indexCache.getOrPut(sub) {
            if (sub.blocks > 20_000_000) throw MapsforgeException("Index too large")
            readAt(sub.indexStart, (sub.blocks * 5).toInt())
        }
        val i = row.toLong() * sub.width + col
        fun entry(k: Long): Long {
            val p = (k * 5).toInt()
            var v = 0L
            for (j in 0 until 5) v = (v shl 8) or (index[p + j].toLong() and 0xFF)
            return v
        }
        val e = entry(i)
        val offset = e and 0x7FFFFFFFFFL
        val next = if (i + 1 < sub.blocks) entry(i + 1) and 0x7FFFFFFFFFL else sub.size
        return Triple(offset, e and 0x8000000000L != 0L, next)
    }

    private fun processBlock(
        r: Reader,
        sub: MfHeader.SubFile,
        queryZoom: Int,
        bx: Int, by: Int,
        bitmask: Int?, // sub-tiles wanted, only when the tile is smaller than the block
        selector: MfSelector,
        pois: MutableList<MfPoi>,
        ways: MutableList<MfWay>,
        bounds: DoubleArray?, // south, west, north, east to keep POIs in, when the tile is smaller than the block
    ) {
        if (header.debug) r.skip(32) // "###TileStart…###"
        // Zoom table: per zoom level of the interval, how many POIs and ways appear (cumulative).
        val rows = sub.maxZoom - sub.minZoom + 1
        var cumPois = 0
        var cumWays = 0
        var poisHere = 0
        var waysHere = 0
        val row = queryZoom.coerceIn(sub.minZoom, sub.maxZoom) - sub.minZoom
        for (i in 0 until rows) {
            cumPois += r.vbeU()
            cumWays += r.vbeU()
            if (i == row) { poisHere = cumPois; waysHere = cumWays }
        }
        val firstWayOffset = r.vbeU()
        val waysStart = r.pos + firstWayOffset
        val tileLat = tileLat(by, sub.baseZoom)
        val tileLon = tileLon(bx, sub.baseZoom)

        for (i in 0 until poisHere) {
            if (header.debug) r.skip(32) // "***POIStart…***"
            val lat = tileLat + r.vbeS() / 1e6
            val lon = tileLon + r.vbeS() / 1e6
            val special = r.byte()
            val layer = special ushr 4
            val tags = readTags(r, header.poiTags, special and 0x0F)
            val flags = r.byte()
            if (flags and 0x80 != 0) tags += MfTag("name", r.string())
            if (flags and 0x40 != 0) tags += MfTag("addr:housenumber", r.string())
            if (flags and 0x20 != 0) tags += MfTag("ele", r.vbeS().toString())
            if (bounds != null && (lat < bounds[0] || lat > bounds[2] || lon < bounds[1] || lon > bounds[3])) continue
            if (selector == MfSelector.NAMED && tags.none { it.key == "name" }) continue
            pois += MfPoi(lat, lon, layer, tags)
        }
        if (selector == MfSelector.POIS) return
        r.pos = waysStart
        for (i in 0 until waysHere) {
            if (header.debug) r.skip(32) // "---WayStart…---"
            val size = r.vbeU()
            val end = r.pos + size
            val wayMask = r.short()
            if (bitmask != null && wayMask and bitmask == 0) {
                r.pos = end
                continue
            }
            val special = r.byte()
            val layer = special ushr 4
            val tags = readTags(r, header.wayTags, special and 0x0F)
            val flags = r.byte()
            if (flags and 0x80 != 0) tags += MfTag("name", r.string())
            if (flags and 0x40 != 0) tags += MfTag("addr:housenumber", r.string())
            if (flags and 0x20 != 0) tags += MfTag("ref", r.string())
            var labelDLat = 0
            var labelDLon = 0
            val hasLabel = flags and 0x10 != 0
            if (hasLabel) { labelDLat = r.vbeS(); labelDLon = r.vbeS() }
            val dataBlocks = if (flags and 0x08 != 0) r.vbeU() else 1
            val doubleDelta = flags and 0x04 != 0
            if (selector == MfSelector.NAMED && tags.none { it.key == "name" }) {
                r.pos = end
                continue
            }
            for (b in 0 until dataBlocks) {
                val nRings = r.vbeU()
                if (nRings !in 1..32767) throw MapsforgeException("Bad way data")
                val rings = List(nRings) { readCoordinates(r, tileLat, tileLon, doubleDelta) }
                val first = rings[0]
                ways += MfWay(
                    layer, tags, rings,
                    if (hasLabel) first[0] + labelDLat / 1e6 else null,
                    if (hasLabel) first[1] + labelDLon / 1e6 else null,
                )
            }
            r.pos = end
        }
    }

    /** Tag ids, then the values of tags declared with a value type (%b %i %f %h %s, version 5). */
    private fun readTags(r: Reader, table: List<MfTag>, n: Int): MutableList<MfTag> {
        val tags = ArrayList<MfTag>(n + 2)
        for (i in 0 until n) {
            val id = r.vbeU()
            if (id !in table.indices) throw MapsforgeException("Bad tag id $id")
            tags += table[id]
        }
        for (i in tags.indices) {
            val t = tags[i]
            if (t.value.length == 2 && t.value[0] == '%') {
                val v = when (t.value[1]) {
                    'b' -> r.byteSigned().toString()
                    'i' -> if (t.key.contains(":colour")) "#" + r.int().toUInt().toString(16) else r.int().toString()
                    'f' -> Float.fromBits(r.int()).toString()
                    'h' -> r.shortSigned().toString()
                    's' -> r.string()
                    else -> t.value
                }
                tags[i] = MfTag(t.key, v)
            }
        }
        return tags
    }

    private fun readCoordinates(r: Reader, tileLat: Double, tileLon: Double, doubleDelta: Boolean): DoubleArray {
        val n = r.vbeU()
        if (n !in 2..1_000_000) throw MapsforgeException("Bad way length $n")
        val out = DoubleArray(2 * n)
        var lat = tileLat + r.vbeS() / 1e6
        var lon = tileLon + r.vbeS() / 1e6
        out[0] = lat
        out[1] = lon
        var prevDLat = 0.0
        var prevDLon = 0.0
        for (i in 1 until n) {
            if (doubleDelta) {
                val dLat = r.vbeS() / 1e6 + prevDLat
                val dLon = r.vbeS() / 1e6 + prevDLon
                lat += dLat
                lon += dLon
                prevDLat = dLat
                prevDLon = dLon
            } else {
                lat += r.vbeS() / 1e6
                lon += r.vbeS() / 1e6
            }
            out[2 * i] = lat
            out[2 * i + 1] = lon
        }
        return out
    }

    private fun readAt(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = file.read(position + done, out, done, length - done)
            if (n <= 0) throw MapsforgeException("Unexpected end of file")
            done += n
        }
        return out
    }

    /** Big-endian reader with mapsforge's variable-length integers. */
    private class Reader(val b: ByteArray) {
        var pos = 0
        fun byte(): Int = b[pos++].toInt() and 0xFF
        fun byteSigned(): Int = b[pos++].toInt()
        fun short(): Int = (byte() shl 8) or byte()
        fun shortSigned(): Int = ((b[pos].toInt() shl 8) or (b[pos + 1].toInt() and 0xFF)).toShort().toInt().also { pos += 2 }
        fun int(): Int = (byte() shl 24) or (byte() shl 16) or (byte() shl 8) or byte()
        fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)
        fun bytes(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }
        fun skip(n: Int) { pos += n }
        fun string(): String {
            val n = vbeU()
            return b.decodeToString(pos, pos + n).also { pos += n }
        }

        /** Unsigned: 7 bits per byte, high bit = more. */
        fun vbeU(): Int {
            var v = 0
            var shift = 0
            while (b[pos].toInt() and 0x80 != 0) {
                v = v or ((b[pos++].toInt() and 0x7F) shl shift)
                shift += 7
            }
            return v or ((b[pos++].toInt() and 0xFF) shl shift)
        }

        /** Signed: as unsigned, but the last byte has 6 data bits and a sign bit (0x40). */
        fun vbeS(): Int {
            var v = 0
            var shift = 0
            while (b[pos].toInt() and 0x80 != 0) {
                v = v or ((b[pos++].toInt() and 0x7F) shl shift)
                shift += 7
            }
            val last = b[pos++].toInt()
            val value = v or ((last and 0x3F) shl shift)
            return if (last and 0x40 != 0) -value else value
        }
    }

    companion object {
        private const val MAX_BLOCK = 32L * 1024 * 1024

        fun tileLon(x: Int, z: Int): Double = x.toDouble() / (1L shl z) * 360.0 - 180.0
        fun tileLat(y: Int, z: Int): Double {
            val n = PI - 2.0 * PI * y / (1L shl z)
            return atan(sinh(n)) * 180.0 / PI
        }
        fun lonToTileX(lon: Double, z: Int): Int =
            floor((lon + 180.0) / 360.0 * (1L shl z)).toInt().coerceIn(0, (1 shl z) - 1)
        fun latToTileY(lat: Double, z: Int): Int {
            val r = lat.coerceIn(-85.05112877980659, 85.05112877980659) * PI / 180.0
            return floor((1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * (1L shl z)).toInt().coerceIn(0, (1 shl z) - 1)
        }

        /**
         * Which of a block's 16 sub-tiles (4×4, at base zoom + 2, top-left = 0x8000) a tile
         * [zoomDiff] levels below the block covers.
         */
        fun tileBitmask(x: Int, y: Int, zoomDiff: Int): Int {
            if (zoomDiff == 1) {
                return when {
                    x % 2 == 0 && y % 2 == 0 -> 0xCC00
                    x % 2 == 1 && y % 2 == 0 -> 0x3300
                    x % 2 == 0 -> 0x00CC
                    else -> 0x0033
                }
            }
            val sx = x ushr (zoomDiff - 2)
            val sy = y ushr (zoomDiff - 2)
            val px = sx ushr 1
            val py = sy ushr 1
            val quad = when {
                px % 2 == 0 && py % 2 == 0 -> intArrayOf(0x8000, 0x4000, 0x0800, 0x0400)
                px % 2 == 1 && py % 2 == 0 -> intArrayOf(0x2000, 0x1000, 0x0200, 0x0100)
                px % 2 == 0 -> intArrayOf(0x0080, 0x0040, 0x0008, 0x0004)
                else -> intArrayOf(0x0020, 0x0010, 0x0002, 0x0001)
            }
            return when {
                sx % 2 == 0 && sy % 2 == 0 -> quad[0]
                sx % 2 == 1 && sy % 2 == 0 -> quad[1]
                sx % 2 == 0 -> quad[2]
                else -> quad[3]
            }
        }
    }
}
