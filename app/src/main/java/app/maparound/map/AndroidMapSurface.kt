package app.maparound.map

import android.content.Context
import app.maparound.AppGraph
import app.maparound.map.gl.AndroidNativeMap
import app.maparound.map.gl.GlStyles
import app.maparound.map.gl.GlTiles
import app.maparound.map.maplibre.MapLibreSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The shared map screen's [MapSurface] on Android: MapLibre ([AndroidNativeMap]) driven by the
 * shared [MapLibreSurface], like on the iPhone, with the app's tiles from [GlTiles] and its
 * styles from [GlStyles].
 */
class AndroidMapSurface private constructor(
    val native: AndroidNativeMap,
    private val surface: MapLibreSurface,
) : MapSurface by surface {

    /** [textScale]: text and symbols this much larger (the car screen). */
    constructor(context: Context, textScale: Float = 1f) : this(AndroidNativeMap(context, textScale))

    private constructor(native: AndroidNativeMap) : this(native, MapLibreSurface(native, GlTiles.service, GlStyles::styleFor))

    override suspend fun setBaseMap(base: BaseMap) {
        if (base is BaseMap.Offline) {
            // The style's own symbols, and the downloaded trails (reading their index: off the main thread).
            native.symbols = { path -> AppGraph.themes.openSymbol(base.theme, path) }
            withContext(Dispatchers.IO) {
                val hasTrails = AppGraph.trails.state.value.cells > 0
                GlTiles.setTrails(if (hasTrails) base.trailsVersion else -1, base.trails, base.drawsTerrain)
            }
        }
        surface.setBaseMap(base)
    }

    override fun onResume() = native.onResume()
    override fun onPause() = native.onPause()

    override fun destroy() {
        surface.destroy()
        native.onDestroy()
    }
}
