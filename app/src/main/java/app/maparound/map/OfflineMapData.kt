package app.maparound.map

import android.util.Log
import app.maparound.AppGraph
import app.maparound.data.MapNames
import app.maparound.terrain.ContourDataStore
import app.maparound.terrain.DemIndex
import app.maparound.trails.TrailDataStore
import app.maparound.trails.TrailIndex
import org.mapsforge.core.model.BoundingBox
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
 * What mapsforge draws offline: the built-in world (land and sea, lakes, rivers, borders and
 * names) at overview zooms and outside the installed country maps, the country maps themselves
 * from zoom 8, contour lines and marked trails as extra map data, and hill shading. Used by the
 * 2D map and the 3D view.
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
            var count = 0
            val countryAreas = ArrayList<BoundingBox>()
            for (f in files) {
                // The built-in world replaces mapsforge's world overview map (land and sea only).
                if (MapNames.isWorld(f.name)) continue
                try {
                    val mapFile = MapFile(f)
                    countryAreas += mapFile.boundingBox()
                    // Overview zooms are the world's: the country maps' own data there is sparse
                    // and would show as patches.
                    mapFile.restrictToZoomRange((WORLD_MAX_ZOOM + 1).toByte(), maxZoom.toByte())
                    vector.addMapDataStore(mapFile, false, false)
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable map ${f.name}", e)
                }
            }
            val world = WorldDataStore({ AppGraph.world }, { AppGraph.borders }, countryAreas, WORLD_MAX_ZOOM)
            // Contour lines are generated from the elevation tiles as extra map data (only the
            // topo theme styles them); hill shading is done by mapsforge itself.
            val dem = style.demDir?.takeIf { style.contours || style.hillshade }?.let { DemIndex(it.path) }?.takeUnless { it.isEmpty }
            // Downloaded trail data: the marked trails when they're on, and the cliffs and ridges
            // fetched with them always (the mapsforge maps have none).
            val trailIndex = style.trailsDir?.takeIf { style.drawsTerrain }?.let { TrailIndex(it) }
                ?.takeUnless { it.isEmpty || (!style.trails && it.featureCount == 0) }
            val withContours = dem != null && style.contours && style.drawsTerrain
            val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
                addMapDataStore(world, false, false)
                if (count > 0) addMapDataStore(vector, false, false)
                if (withContours) {
                    // Contours stop at the coast: the country maps' where they're installed, the
                    // world's elsewhere.
                    val sea = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL).apply {
                        addMapDataStore(world, false, false)
                        if (count > 0) addMapDataStore(vector, false, false)
                    }
                    addMapDataStore(ContourDataStore(dem!!, sea), false, false)
                }
                if (trailIndex != null) addMapDataStore(TrailDataStore(trailIndex, routes = style.trails), false, false)
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
