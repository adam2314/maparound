package app.maparound.map

import android.util.Log
import app.maparound.data.MapNames
import app.maparound.terrain.ContourDataStore
import app.maparound.terrain.DemIndex
import app.maparound.trails.TrailDataStore
import app.maparound.trails.TrailIndex
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.layer.hills.AdaptiveClasyHillShading
import org.mapsforge.map.layer.hills.DemFolderFS
import org.mapsforge.map.layer.hills.HillsRenderConfig
import org.mapsforge.map.layer.hills.MemoryCachingHgtReaderTileSource
import org.mapsforge.map.reader.MapFile
import java.io.File

/**
 * What mapsforge draws offline: the installed maps (a world overview only at low zooms), contour
 * lines and marked trails as extra map data, and hill shading. Used by the 2D map and the 3D view.
 */
class OfflineMapData(val store: MapDataStore, val hills: HillsRenderConfig?, val mapCount: Int) {
    fun close() {
        runCatching { store.close() }
        hills?.interruptAndDestroy()
    }

    companion object {
        private const val TAG = "OfflineMapData"
        const val WORLD_MAX_ZOOM = 7

        fun open(files: List<File>, style: MapController.OfflineStyle, maxZoom: Int = MapController.MAX_ZOOM): OfflineMapData {
            val vector = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
            // With a low-detail world map present, use it only for overview zooms so its coarse
            // coastlines don't paint over the detailed country maps.
            val splitByZoom = files.size > 1 && files.any { MapNames.isWorld(it.name) }
            var count = 0
            for (f in files) {
                try {
                    val mapFile = MapFile(f)
                    if (splitByZoom) {
                        if (MapNames.isWorld(f.name)) {
                            mapFile.restrictToZoomRange(0, WORLD_MAX_ZOOM.toByte())
                        } else {
                            mapFile.restrictToZoomRange((WORLD_MAX_ZOOM + 1).toByte(), maxZoom.toByte())
                        }
                    }
                    vector.addMapDataStore(mapFile, false, false)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable map ${f.name}", e)
                }
            }
            // Contour lines are generated from the elevation tiles as extra map data (only the
            // topo theme styles them); hill shading is done by mapsforge itself.
            val dem = style.demDir?.takeIf { style.contours || style.hillshade }?.let { DemIndex(it.path) }?.takeUnless { it.isEmpty }
            val trailIndex = style.trailsDir?.takeIf { style.trails && style.topo }?.let { TrailIndex(it) }?.takeUnless { it.isEmpty }
            val withContours = dem != null && style.contours && style.topo
            val store: MapDataStore = if (withContours || trailIndex != null) {
                MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
                    addMapDataStore(vector, false, false)
                    if (withContours) addMapDataStore(ContourDataStore(dem!!, vector), false, false)
                    if (trailIndex != null) addMapDataStore(TrailDataStore(trailIndex), false, false)
                }
            } else {
                vector
            }
            val hills = if (dem != null && style.hillshade) {
                HillsRenderConfig(
                    MemoryCachingHgtReaderTileSource(DemFolderFS(java.io.File(dem.dir)), AdaptiveClasyHillShading(true), AndroidGraphicFactory.INSTANCE),
                ).also { it.indexOnThread() }
            } else {
                null
            }
            return OfflineMapData(store, hills, count)
        }
    }
}
