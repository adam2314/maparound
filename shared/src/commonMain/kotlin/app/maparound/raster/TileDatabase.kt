package app.maparound.raster

import app.maparound.i18n.T
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp

class TileDatabaseException(message: String) : Exception(message)

/**
 * A raster tile database in the web map's tile grid (Web Mercator, 256-pixel tiles), read with
 * [SqliteFile]:
 * - **MBTiles** (`tiles` table: zoom_level, tile_column, tile_row in TMS order, tile_data; or the
 *   deduplicated `map` + `images` tables behind a `tiles` view), made by QGIS, GDAL, MOBAC,
 *   TileMill, MapTiler…;
 * - **SQLiteDB** of RMaps, Locus and OsmAnd (`tiles`: x, y, z, s, image), whose z is 17 − zoom
 *   ("BigPlanet" numbering) unless its `info` table says otherwise.
 *
 * Tiles are PNG, JPEG or WebP images; vector MBTiles (map data in protobuf) are refused.
 */
class TileDatabase(private val file: SqliteFile, private val onClose: AutoCloseable? = null) : AutoCloseable {
    enum class Kind(val label: String) { MBTILES("MBTiles"), SQLITEDB("SQLiteDB") }

    /** A table looked up by some key columns: through its index, or a key map built once. */
    private inner class Keyed(table: String, private val keys: List<String>, value: String) {
        private val root: Int
        private val cols: List<String>
        private val alias: Int
        private val keyAt: List<Int>
        private val valueAt: Int
        private val index: SqliteFile.SchemaEntry?
        private var memo: HashMap<List<Any?>, Long>? = null
        private val lock = SynchronizedObject()

        init {
            val e = file.entry(table) ?: throw TileDatabaseException("No table $table")
            if (file.isWithoutRowid(table)) throw TileDatabaseException("Unsupported table layout (WITHOUT ROWID)")
            root = e.rootPage
            val (c, a) = file.columns(table)
            cols = c.map { it.lowercase() }
            alias = a
            keyAt = keys.map { k -> cols.indexOf(k).also { if (it < 0) throw TileDatabaseException("No column $k in $table") } }
            valueAt = cols.indexOf(value).also { if (it < 0) throw TileDatabaseException("No column $value in $table") }
            index = file.indexOn(table, keys)
        }

        private fun col(r: SqliteFile.Row, i: Int): Any? = if (i == alias) r.rowid else r.values.getOrNull(i)

        private fun norm(v: Any?): Any? = when (v) { is Int -> v.toLong(); is Double -> if (v % 1.0 == 0.0) v.toLong() else v; else -> v }

        fun value(key: List<Any?>): Any? {
            val rowid = if (index != null) {
                var r: Long? = null
                file.seek(index.rootPage, key.map(::norm)) { e -> r = e.lastOrNull() as? Long; false }
                r
            } else {
                memo()[key.map(::norm)]
            } ?: return null
            return file.row(root, rowid)?.let { col(it, valueAt) }
        }

        /** Every row's key values (from the index when there is one: much less to read). */
        fun eachKey(visit: (List<Any?>) -> Unit) {
            if (index != null) file.seek(index.rootPage, emptyList()) { e -> visit(e.take(keys.size)); true }
            else file.scan(root) { r -> visit(keyAt.map { col(r, it) }); true }
        }

        private fun memo(): HashMap<List<Any?>, Long> = memo ?: synchronized(lock) {
            memo ?: HashMap<List<Any?>, Long>().also { m ->
                file.scan(root) { r -> m[keyAt.map { norm(col(r, it)) }] = r.rowid; true }
                memo = m
            }
        }
    }

    val kind: Kind
    val name: String?
    val minZoom: Int
    val maxZoom: Int
    /** Coverage in degrees. */
    val north: Double
    val south: Double
    val west: Double
    val east: Double

    /** Tile lookup: the image of z/x/y (file numbering) or null. */
    private val lookup: (Int, Int, Int) -> ByteArray?
    /** SQLiteDB: z column = 17 − zoom. */
    private val inverseZoom: Boolean
    /** Rows counted from the south (TMS): MBTiles always, SQLiteDB if its info says so. */
    private val flipY: Boolean

    init {
        val tiles = file.entry("tiles") ?: throw TileDatabaseException(T.offline.noTilesNotTileDb)
        val tileCols = if (tiles.type == "table") file.columns("tiles").first.map { it.lowercase() } else emptyList()
        val keyed: Keyed
        var meta = emptyMap<String, String?>()
        var inverse = false
        var invY = false
        when {
            tiles.type == "table" && "zoom_level" in tileCols && "tile_data" in tileCols -> {
                kind = Kind.MBTILES
                keyed = Keyed("tiles", listOf("zoom_level", "tile_column", "tile_row"), "tile_data")
                lookup = { z, x, row -> keyed.value(listOf(z, x, row)) as? ByteArray }
            }
            tiles.type == "view" && file.entry("map") != null && file.entry("images") != null -> {
                // mbutil / TileMill: the view joins map (tile keys → tile_id) with images (tile_id → data).
                kind = Kind.MBTILES
                keyed = Keyed("map", listOf("zoom_level", "tile_column", "tile_row"), "tile_id")
                val images = Keyed("images", listOf("tile_id"), "tile_data")
                lookup = { z, x, row -> keyed.value(listOf(z, x, row))?.let { id -> images.value(listOf(id)) as? ByteArray } }
            }
            tiles.type == "table" && "image" in tileCols && "z" in tileCols -> {
                kind = Kind.SQLITEDB
                keyed = Keyed("tiles", listOf("x", "y", "z"), "image")
                lookup = { z, x, y -> keyed.value(listOf(x, y, z)) as? ByteArray }
            }
            else -> throw TileDatabaseException("Unknown tile table layout")
        }
        if (kind == Kind.MBTILES && file.entry("metadata") != null) {
            val m = HashMap<String, String?>()
            val (cols, _) = file.columns("metadata")
            val n = cols.indexOfFirst { it.equals("name", true) }
            val v = cols.indexOfFirst { it.equals("value", true) }
            if (n >= 0 && v >= 0) file.scan(file.entry("metadata")!!.rootPage) { r -> m[(r.values.getOrNull(n)?.toString() ?: "").lowercase()] = r.values.getOrNull(v)?.toString(); true }
            meta = m
            val format = meta["format"]?.lowercase()
            if (format == "pbf" || format == "mvt") {
                throw TileDatabaseException(T.offline.vectorMbtiles)
            }
        }
        if (kind == Kind.SQLITEDB) {
            inverse = true
            file.entry("info")?.let { info ->
                val (cols, _) = file.columns("info")
                var row: List<Any?>? = null
                file.scan(info.rootPage) { r -> row = r.values; false }
                fun value(c: String) = cols.indexOfFirst { it.equals(c, true) }.takeIf { it >= 0 }?.let { row?.getOrNull(it)?.toString() }
                value("tilenumbering")?.let { inverse = it.equals("BigPlanet", ignoreCase = true) }
                invY = value("inverted_y")?.trim() == "1"
                if (value("ellipsoid")?.trim() == "1") {
                    throw TileDatabaseException(T.offline.ellipticalMercator)
                }
            }
        }
        inverseZoom = inverse
        flipY = kind == Kind.MBTILES || invY
        name = meta["name"]?.takeIf { it.isNotBlank() }

        var zMin = meta["minzoom"]?.trim()?.toDoubleOrNull()?.toInt()
        var zMax = meta["maxzoom"]?.trim()?.toDoubleOrNull()?.toInt()
        var bounds = meta["bounds"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }?.toDoubleArray()
        if (zMin == null || zMax == null || bounds == null) {
            // One pass over the keys: the zooms, and per zoom the columns and rows used.
            val ranges = HashMap<Int, IntArray>() // file z -> minX, maxX, minRow, maxRow
            keyed.eachKey { k ->
                val (fz, x, row) = when (kind) {
                    Kind.MBTILES -> Triple(k[0], k[1], k[2])
                    Kind.SQLITEDB -> Triple(k[2], k[0], k[1])
                }
                val z = (fz as? Long)?.toInt() ?: return@eachKey
                val xi = (x as? Long)?.toInt() ?: return@eachKey
                val ri = (row as? Long)?.toInt() ?: return@eachKey
                val r = ranges.getOrPut(z) { intArrayOf(xi, xi, ri, ri) }
                if (xi < r[0]) r[0] = xi
                if (xi > r[1]) r[1] = xi
                if (ri < r[2]) r[2] = ri
                if (ri > r[3]) r[3] = ri
            }
            if (ranges.isEmpty()) throw TileDatabaseException(T.offline.dbHasNoTiles)
            val zooms = ranges.keys.map { if (inverse) 17 - it else it }
            zMin = zMin ?: zooms.min()
            zMax = zMax ?: zooms.max()
            if (bounds == null) {
                val z = zooms.max()
                bounds = edges(ranges.getValue(if (inverse) 17 - z else z), z)
            }
        }
        minZoom = zMin!!
        maxZoom = zMax!!.coerceAtLeast(minZoom)
        if (minZoom !in 0..24 || maxZoom > 24) throw TileDatabaseException("Unusual zoom levels $minZoom–$maxZoom")
        val b = bounds!!
        west = b[0].coerceIn(-180.0, 180.0)
        south = b[1].coerceIn(-85.06, 85.06)
        east = b[2].coerceIn(-180.0, 180.0)
        north = b[3].coerceIn(-85.06, 85.06)
    }

    /** West, south, east, north of tile columns/rows [r] (minX, maxX, minRow, maxRow) at zoom [z]. */
    private fun edges(r: IntArray, z: Int): DoubleArray {
        val n = 1 shl z
        val yTop = if (flipY) n - 1 - r[3] else r[2]
        val yBottom = if (flipY) n - 1 - r[2] else r[3]
        return doubleArrayOf(lonOf(r[0], z), latOf(yBottom + 1, z), lonOf(r[1] + 1, z), latOf(yTop, z))
    }

    /** The image of tile [z]/[x]/[y] (XYZ numbering, y from the north), or null when there's none. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z !in minZoom..maxZoom) return null
        val n = 1 shl z
        if (x !in 0 until n || y !in 0 until n) return null
        return lookup(if (inverseZoom) 17 - z else z, x, if (flipY) n - 1 - y else y)
    }

    override fun close() {
        onClose?.close()
    }

    companion object {
        /** The first 16 bytes of every SQLite database file. */
        fun isSqlite(head: ByteArray): Boolean = head.size >= 16 && head.copyOf(16).decodeToString() == "SQLite format 3\u0000"

        fun lonOf(x: Int, z: Int): Double = x.toDouble() / (1 shl z) * 360 - 180
        fun latOf(y: Int, z: Int): Double {
            val t = PI * (1 - 2.0 * y / (1 shl z))
            return atan(0.5 * (exp(t) - exp(-t))) * 180 / PI
        }
    }
}
