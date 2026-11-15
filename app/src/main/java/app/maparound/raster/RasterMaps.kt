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
import app.maparound.i18n.T
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

    private val _preparing = MutableStateFlow<RasterPreparing?>(null)
    override val preparing: StateFlow<RasterPreparing?> = _preparing.asStateFlow()

    /** Where a GeoTIFF's reduced copies are kept (see [TiffOverviews]). */
    private fun overviewFile(id: String) = File(File(context.filesDir, "raster"), "$id.ovr")

    private class Opened(val closeable: AutoCloseable, val renderer: RasterTiles)
    private val opened = ConcurrentHashMap<String, Opened>()

    override fun get(id: String): RasterMap? = _maps.value.firstOrNull { it.id == id }

    /**
     * Adds a GeoTIFF, an RMAP or a tile database picked with the file picker (keeps read access to
     * it). A big GeoTIFF with no reduced copies of its own gets them made first ([preparing]).
     * Blocking.
     */
    fun add(uri: Uri): RasterMap {
        val name = displayName(uri) ?: T.offline.rasterMapDefaultName
        val lower = name.lowercase()
        if (lower.endsWith(".ecw") || lower.endsWith(".jp2")) throw RmapException(T.offline.ecwNotSupported)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val head = head(uri)
        val existing = _maps.value.firstOrNull { it.source == uri.toString() }
        val map = when {
            TileDatabase.isSqlite(head) -> describeDatabase(uri, name)
            isTiff(head) -> describeGeoTiff(uri, name, existing?.id ?: UUID.randomUUID().toString())
            else -> {
                val (file, close) = openFile(uri.toString())
                try {
                    describe(file, uri.toString(), name)
                } finally {
                    close.close()
                }
            }
        }
        val saved = if (existing != null) map.copy(id = existing.id) else map
        _maps.value = _maps.value.filterNot { it.source == saved.source } + saved
        save()
        return saved
    }

    override fun remove(id: String) {
        val map = get(id) ?: return
        opened.remove(id)?.closeable?.let { runCatching { it.close() } }
        overviewFile(id).delete()
        if (map.source.startsWith("content:")) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(map.source), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        _maps.value = _maps.value.filterNot { it.id == id }
        save()
    }

    /** The first bytes of a picked file. */
    private fun head(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(16).also { b -> var n = 0; while (n < 16) { val r = s.read(b, n, 16 - n); if (r < 0) break; n += r } } }
            ?: throw RmapException(T.offline.cantOpenFile)

    private fun isTiff(b: ByteArray) = b.size >= 4 &&
        ((b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte() && (b[2].toInt() == 42 || b[2].toInt() == 43) && b[3].toInt() == 0) ||
            (b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte() && b[2].toInt() == 0 && (b[3].toInt() == 42 || b[3].toInt() == 43)))

    /** A GeoTIFF: where it lies; its reduced copies made when it's big and has none. */
    private fun describeGeoTiff(uri: Uri, fileName: String, id: String): RasterMap {
        val (read, size, closer) = openRead(uri.toString())
        closer.use {
            val tiff = GeoTiff(TiffFile(read, size))
            val img = tiff.image
            val c = tiff.calibration
            val corners = corners(img.width, img.height).map { (x, y) -> c.pixelToLatLon(x, y) }
            if (corners.any { it.lat.isNaN() || it.lon.isNaN() || it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 }) {
                throw RmapException(T.offline.calibrationInvalid)
            }
            // Something to show: the first block decodes.
            img.block(0, 0, AndroidTiffCodecs)
            val title = fileName.substringBeforeLast('.')
            val ovr = overviewFile(id)
            if (TiffOverviews.needed(img, tiff.overviews)) {
                ovr.parentFile?.mkdirs()
                _preparing.value = RasterPreparing(title, 0.0)
                try {
                    TiffOverviews.build(img, AndroidTiffCodecs, AndroidTiffCodecs::encode, ovr.path, progress = { f ->
                        _preparing.value = RasterPreparing(title, f)
                    })
                } finally {
                    _preparing.value = null
                }
            } else {
                ovr.delete()
            }
            return RasterMap(
                id = id,
                name = title,
                source = uri.toString(),
                sizeBytes = size,
                north = corners.maxOf { it.lat }, south = corners.minOf { it.lat },
                west = corners.minOf { it.lon }, east = corners.maxOf { it.lon },
                projection = c.projection.name,
                width = img.width, height = img.height,
                levels = 1 + tiff.overviews.size,
                format = RasterMap.FORMAT_GEOTIFF,
            )
        }
    }

    /** The corners and the middles of the sides of a [w]×[h] image, in pixels. */
    private fun corners(w: Int, h: Int) = listOf(
        0.0 to 0.0, w / 2.0 to 0.0, w.toDouble() to 0.0, 0.0 to h / 2.0, w.toDouble() to h / 2.0,
        0.0 to h.toDouble(), w / 2.0 to h.toDouble(), w.toDouble() to h.toDouble(),
    )

    /** An MBTiles or SQLiteDB file: its coverage and zooms. */
    private fun describeDatabase(uri: Uri, fileName: String): RasterMap {
        val id = UUID.randomUUID().toString()
        val (db, size) = openDatabase(uri.toString())
        db.use { db ->
            if (db.north <= db.south || db.east <= db.west) throw TileDatabaseException(T.offline.dbCoverageEmpty)
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
            } else if (map.format == RasterMap.FORMAT_GEOTIFF) {
                val (read, size, close) = openRead(map.source)
                try {
                    val tiff = GeoTiff(TiffFile(read, size))
                    val ovr = overviewFile(id)
                    val reduced = if (ovr.exists()) {
                        val (oRead, _, oClose) = openRead(ovr.path)
                        TiffOverviews.read(oRead, tiff.image)?.let { it to oClose } ?: run { oClose.close(); null }
                    } else {
                        null
                    }
                    Opened(AutoCloseable { close.close(); reduced?.second?.close() }, GeoTiffRenderer(tiff, reduced?.first))
                } catch (e: Exception) {
                    close.close()
                    throw e
                }
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
            throw TileDatabaseException(e.message ?: T.offline.cantReadDatabase)
        } catch (e: Exception) {
            closer.close()
            throw e
        }
    }

    /** Positional reads of a file (content:// or a path), its size, and what closes it. */
    private fun openRead(source: String): Triple<RandomRead, Long, AutoCloseable> {
        val pfd: ParcelFileDescriptor = if (source.startsWith("content:")) {
            context.contentResolver.openFileDescriptor(Uri.parse(source), "r") ?: throw RmapException(T.offline.cantOpenFile)
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
        val pts = corners(f.width, f.height).map { (x, y) -> c.pixelToLatLon(x, y) }
        if (pts.any { it.lat.isNaN() || it.lon.isNaN() || it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 }) {
            throw RmapException(T.offline.calibrationInvalid)
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
 * Draws Web Mercator tiles from an image in its own projection, kept as levels of tiles (an RMAP, a
 * GeoTIFF): each output pixel is mapped to the map's projection and pixels (exactly on a 16×16
 * grid, interpolated in between), the best level is picked, and its tiles are decoded on demand (a
 * small cache keeps recent ones).
 */
abstract class PyramidRenderer(private val calibration: Calibration, private val width: Int, private val height: Int) : RasterTiles {
    /** One level: its size, its tiles' size, and how many level-0 pixels one of its pixels covers. */
    protected class Level(val width: Int, val height: Int, val tileWidth: Int, val tileHeight: Int, val factor: Double)

    protected class Src(val w: Int, val h: Int, val px: IntArray?)

    /** Most detailed first. */
    protected abstract val levels: List<Level>

    /** Tile [tx], [ty] of [level], decoded. */
    protected abstract fun load(level: Int, tx: Int, ty: Int): Src

    private val cache = object : LinkedHashMap<Long, Src>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Src>?) = size > 48
    }

    /**
     * The coarsest level still at least as sharp as the output ([per] level-0 pixels per output
     * pixel): at most two of its pixels to one output pixel, which blending neighbours still sees
     * every line of (sharper levels skip thin lines: contour lines broke up).
     */
    private fun bestLevel(per: Double): Int {
        var best = 0
        for (i in levels.indices) if (levels[i].factor <= per * 1.01) best = i
        return best
    }

    /** Renders tile z/x/y at [size]² into [out] (ARGB). False if the map doesn't cover it at all. */
    /** One tile at a time (the last tile looked up is remembered between pixels). */
    @Synchronized
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
            val p = calibration.latLonToPixel(lat, lon)
            gx[j * nodes + i] = p[0]
            gy[j * nodes + i] = p[1]
            if (p[0] in -width * 0.5..width * 1.5 && p[1] in -height * 0.5..height * 1.5) inside = true
        }
        if (!inside || gx.any { it.isNaN() }) return false
        // Level-0 pixels per output pixel picks the file's zoom level.
        val per = hypot(gx[1] - gx[0], gy[1] - gy[0]) / (size.toDouble() / g)
        val level = bestLevel(per)
        val lv = levels[level]
        val f = lv.factor
        // Level pixels per output pixel: above 1, four samples over the output pixel's footprint
        // (one alone can fall between thin lines).
        val ratio = per / f
        val d = ratio / 4
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
                // Neighbouring pixels blended: no steps along slanted lines, no lines lost.
                val c = if (ratio <= 1.05) bilinear(level, sx - 0.5, sy - 0.5, lv.width, lv.height)
                else average(
                    bilinear(level, sx - 0.5 - d, sy - 0.5 - d, lv.width, lv.height), bilinear(level, sx - 0.5 + d, sy - 0.5 - d, lv.width, lv.height),
                    bilinear(level, sx - 0.5 - d, sy - 0.5 + d, lv.width, lv.height), bilinear(level, sx - 0.5 + d, sy - 0.5 + d, lv.width, lv.height),
                )
                out[py * size + px] = c
                if (c != 0) any = true
            }
        }
        return any
    }

    /** Four colours averaged, by their alpha. */
    private fun average(a: Int, b: Int, c: Int, d: Int): Int {
        var sa = 0
        var sr = 0
        var sg = 0
        var sb = 0
        for (p in intArrayOf(a, b, c, d)) {
            val al = p ushr 24
            sa += al
            sr += ((p shr 16) and 0xFF) * al
            sg += ((p shr 8) and 0xFF) * al
            sb += (p and 0xFF) * al
        }
        if (sa == 0) return 0
        return ((sa / 4).coerceIn(1, 255) shl 24) or ((sr / sa) shl 16) or ((sg / sa) shl 8) or (sb / sa)
    }

    // The tile the last pixel came from (most pixels come from the same one as the one before).
    private var lastKey = -1L
    private var lastSrc: Src? = null

    private fun pixel(level: Int, x: Int, y: Int, w: Int, h: Int): Int {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0
        val tw = levels[level].tileWidth
        val th = levels[level].tileHeight
        val key = (level.toLong() shl 48) or ((x / tw).toLong() shl 24) or (y / th).toLong()
        val src = lastSrc?.takeIf { key == lastKey } ?: source(level, x / tw, y / th).also {
            lastKey = key
            lastSrc = it
        }
        val lx = x % tw
        val ly = y % th
        if (src.px == null || lx >= src.w || ly >= src.h) return 0
        return src.px[ly * src.w + lx]
    }

    /** The four pixels around (x, y) blended, by their alpha too: map edges fade out instead of a dark fringe. */
    private fun bilinear(level: Int, x: Double, y: Double, w: Int, h: Int): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        var sa = 0.0
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        fun add(c: Int, weight: Double) {
            if (c == 0 || weight <= 0) return
            val a = (c ushr 24) * weight
            sa += a
            sr += ((c shr 16) and 0xFF) * a
            sg += ((c shr 8) and 0xFF) * a
            sb += (c and 0xFF) * a
        }
        add(pixel(level, x0, y0, w, h), (1 - fx) * (1 - fy))
        add(pixel(level, x0 + 1, y0, w, h), fx * (1 - fy))
        add(pixel(level, x0, y0 + 1, w, h), (1 - fx) * fy)
        add(pixel(level, x0 + 1, y0 + 1, w, h), fx * fy)
        if (sa < 1) return 0
        val a = (sa + 0.5).toInt().coerceIn(1, 255)
        return (a shl 24) or ((sr / sa).toInt().coerceIn(0, 255) shl 16) or ((sg / sa).toInt().coerceIn(0, 255) shl 8) or (sb / sa).toInt().coerceIn(0, 255)
    }

    private fun source(level: Int, tx: Int, ty: Int): Src {
        val key = (level.toLong() shl 48) or (tx.toLong() shl 24) or ty.toLong()
        synchronized(cache) { cache[key]?.let { return it } }
        val src = try {
            load(level, tx, ty)
        } catch (e: Exception) {
            Log.w("RasterMaps", "Tile $level/$tx/$ty", e)
            Src(0, 0, null)
        }
        synchronized(cache) { cache[key] = src }
        return src
    }

    protected companion object {
        fun decoded(bytes: ByteArray?): Src = AndroidTiffCodecs.decode(bytes ?: ByteArray(0))?.let { Src(it.width, it.height, it.argb) } ?: Src(0, 0, null)
    }
}

/** An RMAP: its levels of JPEG tiles. */
class RasterRenderer(private val file: RmapFile) : PyramidRenderer(file.calibration, file.width, file.height) {
    override val levels = file.levels.map { Level(it.width, it.height, file.tileWidth, file.tileHeight, it.factor) }

    override fun load(level: Int, tx: Int, ty: Int): Src = decoded(runCatching { file.tileBytes(level, tx, ty) }.getOrNull())
}

/**
 * A GeoTIFF: its image, its reduced copies in the file, and those MapAround made for it
 * ([overviews]) when it had none.
 */
class GeoTiffRenderer(private val tiff: GeoTiff, private val overviews: TiffOverviews?) :
    PyramidRenderer(tiff.calibration, tiff.image.width, tiff.image.height) {
    private sealed class Source {
        class Image(val image: TiffImage) : Source()
        class Made(val level: Int) : Source()
    }

    private val sources: List<Pair<Level, Source>> = buildList {
        val img = tiff.image
        add(Level(img.width, img.height, img.blockWidth, img.blockHeight, 1.0) to Source.Image(img))
        for (o in tiff.overviews) add(Level(o.width, o.height, o.blockWidth, o.blockHeight, img.width.toDouble() / o.width) to Source.Image(o))
        overviews?.levels?.forEachIndexed { i, l ->
            add(Level(l.width, l.height, TiffOverviews.TILE, TiffOverviews.TILE, l.factor.toDouble()) to Source.Made(i))
        }
    }.sortedBy { it.first.factor }

    override val levels = sources.map { it.first }

    override fun load(level: Int, tx: Int, ty: Int): Src = when (val s = sources[level].second) {
        is Source.Image -> s.image.block(tx, ty, AndroidTiffCodecs)?.let { Src(it.width, it.height, it.argb) } ?: Src(0, 0, null)
        is Source.Made -> decoded(overviews?.tile(s.level, tx, ty))
    }
}

/** Android's decoders for TIFF data: zlib, and JPEG / WebP / PNG by BitmapFactory; tiles encoded for the overviews. */
object AndroidTiffCodecs : TiffCodecs {
    override fun inflate(data: ByteArray, size: Int): ByteArray {
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inflater.finished()) {
                val k = inflater.inflate(out, n, size - n)
                if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += k
            }
            return out
        } finally {
            inflater.end()
        }
    }

    override fun decode(data: ByteArray): Pixels? {
        if (data.isEmpty()) return null
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Pixels(bmp.width, bmp.height, px).also { bmp.recycle() }
    }

    /** A tile for the overviews: JPEG, or PNG where some of it is transparent. */
    fun encode(p: Pixels): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(p.argb, p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888)
        val opaque = p.argb.all { (it ushr 24) == 255 }
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(if (opaque) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG, 85, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
