package app.maparound.map

import android.util.Log
import app.maparound.raster.RasterTiles
import org.mapsforge.core.graphics.TileBitmap
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.TileLayer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.queue.Job
import org.mapsforge.map.model.MapViewPosition

/**
 * A raster map (RMAP) as a mapsforge tile layer: tiles are reprojected to Web Mercator on worker
 * threads and kept in the tile cache like any other map tiles. Outside the map it's transparent.
 */
class RasterTileLayer(
    tileCache: TileCache,
    mapViewPosition: MapViewPosition,
    private val renderer: RasterTiles,
) : TileLayer<Job>(tileCache, mapViewPosition, AndroidGraphicFactory.INSTANCE.createMatrix(), true, true) {
    @Volatile private var running = true
    private val workers = List(2) { i ->
        Thread({ work() }, "raster-tiles-$i").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    override fun createJob(tile: Tile) = Job(tile, true)

    override fun isTileStale(tile: Tile, bitmap: TileBitmap) = false

    private fun work() {
        while (running) {
            val queue = jobQueue
            if (queue == null) {
                try { Thread.sleep(100) } catch (e: InterruptedException) { return }
                continue
            }
            val job = try { queue.get() } catch (e: InterruptedException) { if (running) continue else return }
            try {
                val size = job.tile.tileSize
                val pixels = IntArray(size * size)
                val bitmap = AndroidGraphicFactory.INSTANCE.createTileBitmap(size, true)
                val android = AndroidGraphicFactory.getBitmap(bitmap)
                android.eraseColor(0)
                if (renderer.render(job.tile.zoomLevel.toInt(), job.tile.tileX, job.tile.tileY, size, pixels)) {
                    android.setPixels(pixels, 0, size, 0, 0, size, size)
                }
                tileCache.put(job, bitmap)
                bitmap.decrementRefCount()
                requestRedraw()
            } catch (e: Exception) {
                Log.w("RasterTileLayer", "Tile ${job.tile} failed", e)
            } finally {
                queue.remove(job)
            }
        }
    }

    override fun onDestroy() {
        running = false
        workers.forEach { it.interrupt() }
        super.onDestroy()
    }
}
