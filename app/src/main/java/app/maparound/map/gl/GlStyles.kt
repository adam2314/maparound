package app.maparound.map.gl

import android.util.Log
import app.maparound.AppGraph
import app.maparound.map.MapThemes
import app.maparound.map.maplibre.BasicLayers
import app.maparound.map.maplibre.MapStyle
import app.maparound.map.maplibre.ThemeStyle

/**
 * The MapLibre styles of the offline map, one per map style (Topo, Classic, imported…): the
 * mapsforge theme's rules made into MapLibre layers by the shared [ThemeStyle], and again with
 * fewer rule categories for the style's options. Made on first use and kept.
 */
object GlStyles {
    private const val TAG = "GlStyles"
    private val styles = HashMap<String, MapStyle>()
    private val basic by lazy { MapStyle(BasicLayers.layers, BasicLayers.GLYPHS) }

    /** The style for [themeId] (null: no offline map, e.g. an online one). */
    fun styleFor(themeId: String?): MapStyle {
        if (themeId == null) return basic
        return synchronized(styles) {
            styles.getOrPut(themeId) {
                runCatching {
                    val xml = AppGraph.themes.xml(themeId)
                    val t = ThemeStyle.convert(xml)
                    MapStyle(t.layers, BasicLayers.GLYPHS, t.hillshade) { categories -> ThemeStyle.convert(xml, categories) }
                }.onFailure { Log.w(TAG, "Map style $themeId not converted, using the basic one", it) }.getOrElse { basic }
            }
        }
    }

    /** Forgets the imported styles (after one was imported or deleted). */
    fun forgetImported() = synchronized(styles) { styles.keys.removeAll { it.startsWith(MapThemes.IMPORTED) } }
}
