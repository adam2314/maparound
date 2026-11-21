package app.maparound.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.i18n.T
import kotlinx.coroutines.flow.StateFlow

/**
 * A style for the offline vector maps: a mapsforge render theme, built in or imported. What people
 * see of it, in the app's language: [MapThemes.title] and [MapThemes.about].
 */
data class MapTheme(
    val id: String,
    val name: String,
    /** What it is (the built-in styles': from the texts, see [MapThemes.about]); an imported style's: the folder it came in. */
    val description: String,
    /** Imported by the user (can be deleted). */
    val imported: Boolean = false,
)

object MapThemes {
    /** MapAround's hiking style (topo.xml). */
    const val TOPO = "topo"

    /** MapAround's maps drawn like Tracestrack Topo (tracestrack.xml): Topo's rules, options and trails in other colours. */
    const val TRACESTRACK = "tracestrack"

    /** mapsforge's default theme. */
    const val CLASSIC = "classic"

    /** mapsforge's other built-in themes (Android). */
    const val OSMARENDER = "osmarender"
    const val BIKER = "biker"
    const val MOTORIDER = "motorider"

    /** Ids of imported themes start with this. */
    const val IMPORTED = "user:"

    /**
     * Whether the theme gets MapAround's own map data: contour lines and downloaded marked
     * trails (Topo styles them; an imported theme can, see docs/map-styles.md).
     */
    fun drawsTerrain(themeId: String) = themeId == TOPO || themeId == TRACESTRACK || themeId.startsWith(IMPORTED)

    /** Whether the style has Topo's options (by their ids in topo.xml), named from the texts ([app.maparound.i18n.MapTexts.topoOption]). */
    fun hasTopoOptions(themeId: String) = themeId == TOPO || themeId == TRACESTRACK

    /** The style's name as people see it (Classic and Tracestrack look in the app's language; the others are names of their own). */
    fun title(theme: MapTheme): String = when (theme.id) {
        CLASSIC -> T.map.styleClassic
        TRACESTRACK -> T.map.styleTracestrack
        else -> theme.name
    }

    /** What the style is, in the app's language: a built-in style's description, an imported one's folder. */
    fun about(theme: MapTheme): String = when (theme.id) {
        TOPO -> T.map.styleTopoAbout
        TRACESTRACK -> T.map.styleTracestrackAbout
        CLASSIC -> T.map.styleClassicAbout
        OSMARENDER -> T.map.styleOsmarenderAbout
        BIKER -> T.map.styleBikerAbout
        MOTORIDER -> T.map.styleMotoriderAbout
        else -> if (theme.imported) T.map.importedStyle(theme.description) else theme.description
    }
}

/** The styles a platform can draw the offline maps with, and the ones the user imported. */
interface MapThemeLibrary {
    /** Built-in styles first, then imported ones. */
    val themes: StateFlow<List<MapTheme>>

    /** The theme's options, or null when it has none. Cached: cheap to call. */
    fun menu(themeId: String): StyleMenu?

    /** Whether themes can be imported here. */
    val canImport: Boolean

    /**
     * Adds the render theme(s) in a picked `.xml` file, or a `.zip` with themes and their
     * symbols. Returns the new styles, or an error message. Blocking (call off the main thread).
     */
    fun import(name: String?, bytes: ByteArray): Result<List<MapTheme>>

    fun delete(themeId: String)

    /** The style's render theme (XML), for its legend; null when it can't be read. Blocking. */
    fun themeXml(themeId: String): String? = null

    /** One of the style's symbols ("symbols/peak.svg") drawn [heightPx] high, for its legend; null if none. */
    suspend fun symbol(themeId: String, path: String, heightPx: Int): ImageBitmap? = null
}

val LocalMapThemes = staticCompositionLocalOf<MapThemeLibrary> { error("No MapThemeLibrary provided") }

/** The categories to draw for [themeId] with the user's [choices], or null for all (no menu). */
fun MapThemeLibrary.categories(themeId: String, choices: Map<String, StyleChoice>): Set<String>? =
    menu(themeId)?.categories(choices[themeId] ?: StyleChoice())
