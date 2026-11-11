package app.maparound.map

import app.maparound.AppGraph
import app.maparound.data.Prefs

/**
 * The background chosen in Layers, ready to draw: satellite imagery, a raster map, or null
 * (the vector map's own colours). Opens raster files, so call it off the main thread.
 */
fun resolveBackground(id: String): MapController.Background? = when {
    id == Prefs.BACKGROUND_SATELLITE -> MapController.Background.Online(satelliteSource)
    id.startsWith(Prefs.BACKGROUND_RASTER) -> {
        val rasterId = id.removePrefix(Prefs.BACKGROUND_RASTER)
        AppGraph.rasters.renderer(rasterId)?.let { MapController.Background.Raster(rasterId, it) }
    }
    else -> null
}
