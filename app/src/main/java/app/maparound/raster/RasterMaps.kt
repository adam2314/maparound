package app.maparound.raster

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.maparound.routing.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sinh

class RasterMapRepository(private val context: Context) : RasterLibrary {
    private val sp = context.getSharedPreferences("raster_maps", Context.MODE_PRIVATE)
    private val _maps = MutableStateFlow(load())
    override val maps: StateFlow<List<RasterMap>> = _maps.asStateFlow()

    private class Opened(val closeable: AutoCloseable, val renderer: RasterTiles)
    private val opened = ConcurrentHashMap<String, Opened>()

    override fun get(id: String): RasterMap? = _maps.value.firstOrNull { it.id == id }

    /** Adds an RMAP or a tile database picked with the file picker (keeps read access to it). Blocking. */
    fun add(uri: Uri): RasterMap {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayName(uri) ?: "Raster map"
        val map = if (TileDatabase.isSqlite(head(uri))) {
            describeDatabase(uri, name)
        } else {
            val (file, close) = openFile(uri.toString())
            try {
                describe(file, uri.toString(), name)
            } finally {
                close.close()
            }
        }
        val existing = _maps.value.firstOrNull { it.source == map.source }
        val saved = if (existing != null) map.copy(id = existing.id) else map
        _maps.value = _maps.value.filterNot { it.source == saved.source } + saved
        save()
        return saved
    }

    override fun remove(id: String) {
        val map = get(id) ?: return
        opened.remove(id)?.closeable?.let { runCatching { it.close() } }
        if (map.source.startsWith("content:")) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(map.source), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        _maps.value = _maps.value.filterNot { it.id == id }
        save()
    }

    /** The first bytes of a picked file. */
    private fun head(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(16).also { b -> var n = 0; while (n < 16) { val r = s.read(b, n, 16 - n); if (r < 0) break; n += r } } }
            ?: throw RmapException("Can't open the file")

    /** An MBTiles or SQLiteDB file: its coverage and zooms. */
    private fun describeDatabase(uri: Uri, fileName: String): RasterMap {
        val id = UUID.randomUUID().toString()
        val (db, size) = openDatabase(uri.toString())
        db.use { db ->
            if (db.north <= db.south || db.east <= db.west) throw TileDatabaseException("The database's coverage is empty")
            return RasterMap(
                id = id,
                name = db.name ?: fileName.substringBeforeLast('.'),
                source = uri.toString(),
                sizeBytes = size,
                north = db.north, south = db.south, west = db.west, east = db.east,
                projection = db.kind.label,
                width = 0, height = 0,
                levels = db.maxZoom - db.minZoom + 1,
                format = if (db.kind == TileDatabase.Kind.MBTILES) RasterMap.FORMAT_MBTILES else RasterMap.FORMAT_SQLITEDB,
            )
        }
    }

    /** Opens the file picker for a raster map; the file stays where it is (no copy). */
    @Composable
    override fun rememberPicker(onResult: (Result<RasterMap>) -> Unit): () -> Unit {
        val scope = rememberCoroutineScope()
        val callback = rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch { callback.value(withContext(Dispatchers.IO) { runCatching { add(uri) } }) }
            }
        }
        return { launcher.launch(arrayOf("*/*")) }
    }

    /** A renderer for the map (opens the file on first use). Null if it can't be read any more. */
    fun renderer(id: String): RasterTiles? {
        opened[id]?.let { return it.renderer }
        val map = get(id) ?: return null
        return try {
            val o = if (map.format == RasterMap.FORMAT_RMAP) {
                val (file, close) = openFile(map.source)
                Opened(close, RasterRenderer(file))
            } else {
                val db = openDatabase(map.source).first
                Opened(db, TileDatabaseRenderer(db))
            }
            val close = o.closeable
            opened.putIfAbsent(id, o)?.also { runCatching { close.close() } }?.renderer ?: o.renderer
        } catch (e: Exception) {
            Log.w(TAG, "Can't open ${map.name}", e)
            null
        }
    }

    private fun openFile(source: String): Pair<RmapFile, AutoCloseable> {
        val (read, size, closer) = openRead(source)
        return try {
            RmapFile(read, size) to closer
        } catch (e: Exception) {
            closer.close()
            throw e
        }
    }

    /** A tile database read in place (Android's own SQLite can't open picked files), and its size. */
    private fun openDatabase(source: String): Pair<TileDatabase, Long> {
        val (read, size, closer) = openRead(source)
        return try {
            TileDatabase(SqliteFile(read, size), closer) to size
        } catch (e: SqliteException) {
            closer.close()
            throw TileDatabaseException(e.message ?: "Can't read the database")
        } catch (e: Exception) {
            closer.close()
            throw e
        }
    }

    /** Positional reads of a file (content:// or a path), its size, and what closes it. */
    private fun openRead(source: String): Triple<RandomRead, Long, AutoCloseable> {
        val pfd: ParcelFileDescriptor = if (source.startsWith("content:")) {
            context.contentResolver.openFileDescriptor(Uri.parse(source), "r") ?: throw RmapException("Can't open the file")
        } else {
            ParcelFileDescriptor.open(File(source), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val stream = FileInputStream(pfd.fileDescriptor)
        val channel: FileChannel = stream.channel
        val size = channel.size()
        val read = RandomRead { pos, len ->
            val buf = ByteBuffer.allocate(len)
            var p = pos
            while (buf.hasRemaining()) {
                val n = channel.read(buf, p)
                if (n <= 0) break
                p += n
            }
            if (buf.position() == len) buf.array() else buf.array().copyOf(buf.position())
        }
        val closer = AutoCloseable {
            runCatching { stream.close() }
            runCatching { pfd.close() }
        }
        return Triple(read, size, closer)
    }

    private fun describe(f: RmapFile, source: String, name: String): RasterMap {
        val c = f.calibration
        val pts = listOf(0.0 to 0.0, f.width / 2.0 to 0.0, f.width.toDouble() to 0.0, 0.0 to f.height / 2.0, f.width.toDouble() to f.height / 2.0,
            0.0 to f.height.toDouble(), f.width / 2.0 to f.height.toDouble(), f.width.toDouble() to f.height.toDouble())
            .map { (x, y) -> c.pixelToLatLon(x, y) }
        if (pts.any { it.lat.isNaN() || it.lon.isNaN() || it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 }) {
            throw RmapException("The map's calibration doesn't give a valid position")
        }
        return RasterMap(
            id = UUID.randomUUID().toString(),
            name = name.substringBeforeLast('.'),
            source = source,
            sizeBytes = f.sizeBytes,
            north = pts.maxOf { it.lat }, south = pts.minOf { it.lat },
            west = pts.minOf { it.lon }, east = pts.maxOf { it.lon },
            projection = c.projection.name, width = f.width, height = f.height, levels = f.levels.size,
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun load(): List<RasterMap> = runCatching {
        val arr = JSONArray(sp.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RasterMap(
                o.getString("id"), o.getString("name"), o.getString("source"), o.optLong("size"),
                o.getDouble("n"), o.getDouble("s"), o.getDouble("w"), o.getDouble("e"),
                o.optString("proj"), o.optInt("width"), o.optInt("height"), o.optInt("levels"),
                o.optString("format", RasterMap.FORMAT_RMAP),
            )
        }
    }.getOrElse { emptyList() }

    private fun save() {
        val arr = JSONArray()
        for (m in _maps.value) {
            arr.put(
                JSONObject().put("id", m.id).put("name", m.name).put("source", m.source).put("size", m.sizeBytes)
                    .put("n", m.north).put("s", m.south).put("w", m.west).put("e", m.east)
                    .put("proj", m.projection).put("width", m.width).put("height", m.height).put("levels", m.levels)
                    .put("format", m.format),
            )
        }
        sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "RasterMaps"
        private const val KEY = "maps"
    }
}

/**
 * Draws Web Mercator tiles from an RMAP: each output pixel is mapped to the map's projection and
 * pixels (exactly on a 16×16 grid, interpolated in between), the best zoom level of the file is
 * picked, and its JPEG tiles are decoded on demand (a small cache keeps recent ones).
 */
class RasterRenderer(private val file: RmapFile) : RasterTiles {
    private class Src(val w: Int, val h: Int, val px: IntArray?)
    private val cache = object : LinkedHashMap<Long, Src>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Src>?) = size > 48
    }

    /** Renders tile z/x/y at [size]² into [out] (ARGB). False if the map doesn't cover it at all. */
    override fun render(z: Int, x: Int, y: Int, size: Int, out: IntArray): Boolean {
        val g = 16
        val nodes = g + 1
        val gx = DoubleArray(nodes * nodes)
        val gy = DoubleArray(nodes * nodes)
        val mapSize = size.toDouble() * (1L shl z)
        var inside = false
        for (j in 0 until nodes) for (i in 0 until nodes) {
            val wx = (x * size.toDouble() + i * size.toDouble() / g) / mapSize
            val wy = (y * size.toDouble() + j * size.toDouble() / g) / mapSize
            val lon = wx * 360 - 180
            val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * wy))))
            val p = file.calibration.latLonToPixel(lat, lon)
            gx[j * nodes + i] = p[0]
            gy[j * nodes + i] = p[1]
            if (p[0] in -file.width * 0.5..file.width * 1.5 && p[1] in -file.height * 0.5..file.height * 1.5) inside = true
        }
        if (!inside || gx.any { it.isNaN() }) return false
        // Level-0 pixels per output pixel picks the file's zoom level.
        val per = hypot(gx[1] - gx[0], gy[1] - gy[0]) / (size.toDouble() / g)
        val level = file.bestLevel(per)
        val lv = file.levels[level]
        val f = lv.factor
        val smooth = per / f < 0.8 // magnified: blend neighbouring pixels
        var any = false
        val cell = size.toDouble() / g
        for (py in 0 until size) {
            val fy = py / cell
            val j = floor(fy).toInt().coerceAtMost(g - 1)
            val ty = fy - j
            for (px in 0 until size) {
                val fx = px / cell
                val i = floor(fx).toInt().coerceAtMost(g - 1)
                val tx = fx - i
                val k = j * nodes + i
                val sx = ((gx[k] * (1 - tx) + gx[k + 1] * tx) * (1 - ty) + (gx[k + nodes] * (1 - tx) + gx[k + nodes + 1] * tx) * ty) / f
                val sy = ((gy[k] * (1 - tx) + gy[k + 1] * tx) * (1 - ty) + (gy[k + nodes] * (1 - tx) + gy[k + nodes + 1] * tx) * ty) / f
                val c = if (smooth) bilinear(level, sx - 0.5, sy - 0.5, lv.width, lv.height) else pixel(level, sx.toInt(), sy.toInt(), lv.width, lv.height)
                out[py * size + px] = c
                if (c != 0) any = true
            }
        }
        return any
    }

    private fun pixel(level: Int, x: Int, y: Int, w: Int, h: Int): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0
        val tw = file.tileWidth
        val th = file.tileHeight
        val src = source(level, x / tw, y / th)
        val lx = x % tw
        val ly = y % th
        if (src.px == null || lx >= src.w || ly >= src.h) return 0
        return src.px[ly * src.w + lx]
    }

    private fun bilinear(level: Int, x: Double, y: Double, w: Int, h: Int): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val c00 = pixel(level, x0, y0, w, h)
        val c10 = pixel(level, x0 + 1, y0, w, h)
        val c01 = pixel(level, x0, y0 + 1, w, h)
        val c11 = pixel(level, x0 + 1, y0 + 1, w, h)
        if (c00 == 0 && c10 == 0 && c01 == 0 && c11 == 0) return 0
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int): Int {
            val top = ch(c00, s) * (1 - fx) + ch(c10, s) * fx
            val bottom = ch(c01, s) * (1 - fx) + ch(c11, s) * fx
            return (top * (1 - fy) + bottom * fy).toInt().coerceIn(0, 255)
        }
        val a = if (c00 == 0 || c10 == 0 || c01 == 0 || c11 == 0) 0xFF else mix(24)
        return (a shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun source(level: Int, tx: Int, ty: Int): Src {
        val key = (level.toLong() shl 48) or (tx.toLong() shl 24) or ty.toLong()
        synchronized(cache) { cache[key]?.let { return it } }
        val bytes = runCatching { file.tileBytes(level, tx, ty) }.getOrNull()
        val src = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { bmp ->
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Src(bmp.width, bmp.height, px).also { bmp.recycle() }
        } ?: Src(0, 0, null)
        synchronized(cache) { cache[key] = src }
        return src
    }

}
