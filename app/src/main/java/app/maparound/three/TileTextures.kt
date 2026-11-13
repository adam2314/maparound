package app.maparound.three

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
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

    private companion object {
        const val COUNT = 2
        const val SIZE = 512
        const val TIMEOUT_S = 40L
    }
}
