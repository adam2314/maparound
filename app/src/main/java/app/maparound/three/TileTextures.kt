package app.maparound.three

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.maparound.USER_AGENT
import app.maparound.map.MapController
import app.maparound.map.OfflineMapData
import app.maparound.map.OnlineSource
import app.maparound.map.TileSources
import app.maparound.map.TopoTheme
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.cache.InMemoryTileCache
import org.mapsforge.map.layer.renderer.DatabaseRenderer
import org.mapsforge.map.layer.renderer.RendererJob
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Map pictures for 3D tiles. [load] blocks (call it off the main thread). */
interface TileTextures {
    val maxZoom: Int
    /** Worker threads worth using (rendering is CPU-bound; downloads are not). */
    val threads: Int
    fun load(t: TileId): Bitmap?
    fun close()
}

/**
 * Offline maps drawn by mapsforge's own renderer, same style as the 2D map (topo, contours,
 * shading, trails). [hybrid]: transparent, roads, trails and labels only, to lay over imagery.
 */
class OfflineTileTextures(
    context: Context,
    files: List<File>,
    style: MapController.OfflineStyle,
    private val hybrid: Boolean = false,
) : TileTextures {
    private val data = OfflineMapData.open(files, if (hybrid) style.copy(hillshade = false) else style, maxZoom = 21)
    // 512-pixel tiles drawn at twice the "1×" size, so labels stay readable when tilted.
    private val displayModel = DisplayModel().apply { setUserScaleFactor(2f / DisplayModel.getDeviceScaleFactor()) }
    private val theme = RenderThemeFuture(
        AndroidGraphicFactory.INSTANCE,
        if (hybrid) TopoTheme(context, hybrid = true) else if (style.topo) TopoTheme(context) else MapsforgeThemes.DEFAULT,
        displayModel,
    ).also { it.run() }
    private val renderer = DatabaseRenderer(data.store, AndroidGraphicFactory.INSTANCE, InMemoryTileCache(16), null, true, false, data.hills)
    val mapCount = data.mapCount
    override val maxZoom = 17
    // mapsforge renders tiles on several threads in its own map view too.
    override val threads = 2

    override fun load(t: TileId): Bitmap? {
        val tile = Tile(t.x, t.y, t.z.toByte(), displayModel.tileSize)
        val bitmap = renderer.executeJob(RendererJob(tile, data.store, theme, displayModel, 1f, hybrid, false)) ?: return null
        return try {
            AndroidGraphicFactory.getBitmap(bitmap).copy(if (hybrid) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565, false)
        } finally {
            bitmap.decrementRefCount()
        }
    }

    override fun close() {
        theme.decrementRefCount()
        data.close()
    }
}

/** A raster map (RMAP), reprojected to 512-pixel tiles. */
class RasterTileTextures(private val renderer: app.maparound.raster.RasterRenderer) : TileTextures {
    override val maxZoom = 18
    override val threads = 2

    override fun load(t: TileId): Bitmap? {
        val size = 512
        val px = IntArray(size * size)
        if (!renderer.render(t.z, t.x, t.y, size, px)) return null
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    override fun close() = Unit
}

/** A background (imagery or raster map) with a transparent picture (the hybrid vector map) on top. */
class CompositeTileTextures(private val bottom: TileTextures, private val top: TileTextures) : TileTextures {
    override val maxZoom = minOf(18, maxOf(bottom.maxZoom, top.maxZoom))
    override val threads = maxOf(bottom.threads, top.threads)

    override fun load(t: TileId): Bitmap? {
        val b = if (t.z <= bottom.maxZoom) runCatching { bottom.load(t) }.getOrNull() else null
        val o = if (t.z <= top.maxZoom) runCatching { top.load(t) }.getOrNull() else null
        if (b == null && o == null) return null
        val out = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
        val c = android.graphics.Canvas(out)
        c.drawColor(0xFFE8E4D8.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val dst = android.graphics.Rect(0, 0, 512, 512)
        b?.let { c.drawBitmap(it, null, dst, paint); it.recycle() }
        o?.let { c.drawBitmap(it, null, dst, paint); it.recycle() }
        return out
    }

    override fun close() {
        bottom.close()
        top.close()
    }
}

/** Online XYZ tiles (OSM, OpenTopoMap, satellite…), cached on disk for a month. */
class OnlineTileTextures(context: Context, private val source: OnlineSource) : TileTextures {
    private val dir = File(context.cacheDir, "tiles3d/" + source.id.replace(Regex("[^A-Za-z0-9_]"), "_"))
    override val maxZoom = source.maxZoom.coerceAtMost(18)
    override val threads = 4

    override fun load(t: TileId): Bitmap? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val file = File(dir, "${t.z}/${t.x}/${t.y}")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < 30L * 24 * 3_600_000) {
            BitmapFactory.decodeFile(file.path, opts)?.let { return it }
        }
        val conn = URL(TileSources.tileUrl(source, t.z, t.x, t.y)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { it.readBytes() }
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } finally {
            conn.disconnect()
        }
    }

    override fun close() = Unit
}
