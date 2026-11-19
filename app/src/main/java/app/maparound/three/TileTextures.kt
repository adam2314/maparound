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
