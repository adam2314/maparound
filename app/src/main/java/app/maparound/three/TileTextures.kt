package app.maparound.three

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.snapshotter.MapSnapshotter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** Map pictures for 3D tiles. [load] blocks (call it off the main thread). */
interface TileTextures {
    val maxZoom: Int

    /** Worker threads worth using. */
    val threads: Int

    /** A tile without a picture isn't worth showing (it's retried later). */
    val needsPicture: Boolean get() = true

    fun load(t: TileId): Bitmap?
    fun close()
}

/**
 * The map drawn by MapLibre, like the 2D map (the same style, offline maps, world, contours,
 * trails, background and online sources), without labels: the 3D view stands those up. Each
 * tile is a 512-pixel snapshot centred on the tile at its zoom, so it covers exactly the tile.
 *
 * MapLibre's snapshotters live on the main thread; [load] hands the work there and waits. Create
 * on the main thread.
 */
class GlTileTextures(context: Context, styleJson: String, override val maxZoom: Int) : TileTextures {
    private val main = Handler(Looper.getMainLooper())
    private val snapshotters = ArrayBlockingQueue<MapSnapshotter>(COUNT)
    override val threads = COUNT

    init {
        repeat(COUNT) {
            val options = MapSnapshotter.Options(SIZE, SIZE)
                .withStyleJson(styleJson)
                .withPixelRatio(1f)
                .withCameraPosition(CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(0.0).build())
            options.showLogo = false
            options.showAttribution = false
            snapshotters.put(MapSnapshotter(context.applicationContext, options))
        }
    }

    override fun load(t: TileId): Bitmap? {
        val s = snapshotters.take()
        try {
            val done = CountDownLatch(1)
            var picture: Bitmap? = null
            var error: String? = null
            val n = 1 shl t.z
            val lat = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * (t.y + 0.5) / n))))
            val lon = (t.x + 0.5) / n * 360.0 - 180.0
            main.post {
                s.setCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(t.z.toDouble()).bearing(0.0).tilt(0.0).build())
                s.start(
                    { snapshot ->
                        // No transparency needed: half the memory on the GPU.
                        picture = snapshot.bitmap.copy(Bitmap.Config.RGB_565, false)
                        done.countDown()
                    },
                    { e ->
                        error = e
                        done.countDown()
                    },
                )
            }
            if (!done.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                main.post { s.cancel() }
                throw IllegalStateException("snapshot timed out")
            }
            error?.let { throw IllegalStateException(it) }
            return picture
        } finally {
            snapshotters.put(s)
        }
    }

    override fun close() {
        main.post { snapshotters.forEach { runCatching { it.cancel() } } }
    }

    companion object {
        private const val COUNT = 2
        private const val SIZE = 512
        private const val TIMEOUT_S = 40L

        /**
         * Pictures for [styleJson], made on the main thread from any thread (a snapshotter has to
         * be), waiting for it; null when it can't be made.
         */
        fun createFrom(context: Context, styleJson: String, maxZoom: Int): GlTileTextures? {
            if (Looper.myLooper() == Looper.getMainLooper()) return GlTileTextures(context, styleJson, maxZoom)
            var made: GlTileTextures? = null
            val done = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                made = runCatching { GlTileTextures(context, styleJson, maxZoom) }.getOrNull()
                done.countDown()
            }
            done.await(10, TimeUnit.SECONDS)
            return made
        }

        /** "HTTP status code 404": the server hasn't got it (not a passing failure). */
        fun notServed(error: Throwable): Boolean = Regex("""status code 4\d\d""").containsMatchIn(error.message.orEmpty())
    }
}

/**
 * [primary]'s pictures, and where its server hasn't got a tile (a regional map at the world's zooms
 * or past its border: an HTTP 4xx), [fallback]'s (made when first needed): the 3D view shows the
 * global map there rather than nothing. Other failures (offline) are retried as they are.
 */
class FallbackTileTextures(private val primary: TileTextures, private val fallback: () -> TileTextures?) : TileTextures by primary {
    @Volatile private var made: TileTextures? = null
    private var tried = false

    override fun load(t: TileId): Bitmap? = try {
        primary.load(t)
    } catch (e: Exception) {
        if (!GlTileTextures.notServed(e)) throw e
        val other = synchronized(this) {
            if (!tried) {
                tried = true
                made = fallback()
            }
            made
        } ?: throw e
        other.load(t)
    }

    override fun close() {
        primary.close()
        made?.close()
    }
}

/**
 * [inner]'s pictures kept on disk ([root], a folder per [key]: a changed style or changed data
 * starts afresh), so a place seen before shows at once, also offline: WebP, at most [maxBytes] in
 * all (the oldest go first), each kept [maxAgeMs]. A picture drawn while [incomplete] went up (a
 * tile of the map couldn't be made in full) isn't kept.
 */
class CachedTileTextures(
    private val inner: TileTextures,
    root: File,
    key: String,
    private val incomplete: () -> Long,
    private val maxBytes: Long = 150L shl 20,
    private val maxAgeMs: Long = 30L * 24 * 3600 * 1000,
) : TileTextures by inner {
    private val dir = File(root, sha1(key).take(16))

    /** Writes pictures to disk off the threads drawing them (compressing one takes a while). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "3d-pictures-save").apply { isDaemon = true } }

    init {
        Thread({ trim(root) }, "3d-pictures-trim").apply { isDaemon = true; start() }
    }

    override fun load(t: TileId): Bitmap? {
        val file = File(dir, "${t.z}/${t.x}/${t.y}.webp")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < maxAgeMs) {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeFile(file.path, options)?.let { return it }
        }
        val before = incomplete()
        val picture = inner.load(t) ?: return null
        if (incomplete() == before) {
            // A copy: the picture itself goes to the GPU and is let go of there.
            val copy = picture.copy(picture.config ?: Bitmap.Config.RGB_565, false)
            if (copy != null) runCatching { writer.execute { save(file, copy); copy.recycle() } }.onFailure { copy.recycle() }
        }
        return picture
    }

    override fun close() {
        inner.close()
        writer.shutdown()
    }

    private fun save(file: File, picture: Bitmap) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            tmp.outputStream().buffered().use { picture.compress(format, QUALITY, it) }
            if (!tmp.renameTo(file)) tmp.delete()
        }.onFailure { Log.w("CachedTileTextures", "Couldn't keep ${file.name}: ${it.message}") }
    }

    /** Drops pictures past their age, then the oldest until all fit [maxBytes] (with room to spare). */
    private fun trim(root: File) {
        val now = System.currentTimeMillis()
        val files = root.walkTopDown().filter { it.isFile }.toMutableList()
        files.removeAll { f -> (now - f.lastModified() > maxAgeMs || f.name.endsWith(".tmp")).also { if (it) f.delete() } }
        var total = files.sumOf { it.length() }
        if (total > maxBytes) {
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= maxBytes * 8 / 10) break
                total -= f.length()
                f.delete()
            }
        }
        root.walkBottomUp().filter { it.isDirectory && it != root && it.list()?.isEmpty() == true }.forEach { it.delete() }
    }

    private companion object {
        const val QUALITY = 85

        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/**
 * [inner]'s pictures with the coordinate grid of [format] drawn on them (the 2D map's grid,
 * [app.maparound.tools.MapGrid]): it lies on the terrain and hides behind ridges as the map does.
 * About three lines across a tile, each tile's lines at its own zoom's spacing (finer ones near
 * by); their values at the tile's top and left edges, cells' codes in their middles.
 */
class GridTileTextures(private val inner: TileTextures, private val format: app.maparound.tools.CoordFormat) : TileTextures by inner {
    private val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.6f
        color = 0xB8141414.toInt()
    }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 19f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        color = 0xFF1B1B1B.toInt()
    }
    private val halo = android.graphics.Paint(text).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xEBFFFFFF.toInt()
    }

    override fun load(t: TileId): Bitmap? {
        val picture = inner.load(t) ?: return null
        val out = if (picture.isMutable) picture else picture.copy(picture.config ?: Bitmap.Config.RGB_565, true).also { picture.recycle() }
        runCatching { draw(out, t) }.onFailure { Log.w("GridTileTextures", "No grid on $t: ${it.message}") }
        return out
    }

    private fun draw(bmp: Bitmap, t: TileId) {
        val n = 1 shl t.z
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        fun x(lon: Double) = (((lon + 180) / 360 * n - t.x) * w).toFloat()
        fun y(lat: Double): Float {
            val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            return (((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / Math.PI) / 2 * n - t.y) * h).toFloat()
        }
        val mid = (t.north + t.south) / 2
        // A little past the tile, so lines run to its edges.
        val padLat = (t.north - t.south) * 0.05
        val padLon = (t.east - t.west) * 0.05
        val view = app.maparound.tools.MapGrid.View(t.south - padLat, t.west - padLon, t.north + padLat, t.east + padLon)
        // About three lines across: a tile's metres over the grid's spacing in dp.
        val mpd = TileId.sizeM(t.z, mid) / (3.2 * app.maparound.tools.MapGrid.SPACING_DP)
        val grid = app.maparound.tools.MapGrid.make(format, view, mpd, app.maparound.routing.GeoPoint(mid, (t.west + t.east) / 2))
        val canvas = android.graphics.Canvas(bmp)
        for (l in grid.lines) {
            if (l.points.size < 2) continue
            val path = android.graphics.Path()
            l.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.lon), y(p.lat)) else path.lineTo(x(p.lon), y(p.lat)) }
            canvas.drawPath(path, line)
            val label = l.label ?: continue
            // Along the top edge for the lines running north-south, the left edge for the others.
            val first = l.points.first()
            val last = l.points.last()
            val upright = kotlin.math.abs(y(first.lat) - y(last.lat)) > kotlin.math.abs(x(first.lon) - x(last.lon))
            val at = crossing(l.points.map { x(it.lon) to y(it.lat) }, upright, EDGE_PX) ?: continue
            val tw = text.measureText(label)
            val lx = if (upright) at - tw / 2 else EDGE_PX
            val ly = if (upright) EDGE_PX + text.textSize else at + text.textSize / 3
            if (lx < 0 || lx + tw > w || ly > h) continue
            canvas.drawText(label, lx, ly, halo)
            canvas.drawText(label, lx, ly, text)
        }
        for (c in grid.labels) {
            val tw = text.measureText(c.text)
            val lx = x(c.at.lon) - tw / 2
            val ly = y(c.at.lat) + text.textSize / 3
            canvas.drawText(c.text, lx, ly, halo)
            canvas.drawText(c.text, lx, ly, text)
        }
    }

    /** Where the line (pixels) crosses y = [edge] (an upright line: its x there) or x = [edge] (its y); null if it doesn't. */
    private fun crossing(pts: List<Pair<Float, Float>>, upright: Boolean, edge: Float): Float? {
        for ((a, b) in pts.zipWithNext()) {
            val a0 = if (upright) a.second else a.first
            val a1 = if (upright) a.first else a.second
            val b0 = if (upright) b.second else b.first
            val b1 = if (upright) b.first else b.second
            if ((a0 - edge) * (b0 - edge) <= 0 && a0 != b0) return a1 + (edge - a0) / (b0 - a0) * (b1 - a1)
        }
        return null
    }

    private companion object {
        /** The values sit this far in from the tile's edge (pixels). */
        const val EDGE_PX = 6f
    }
}

