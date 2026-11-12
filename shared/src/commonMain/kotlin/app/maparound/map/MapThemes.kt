package app.maparound.map

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/** A style for the offline vector maps: a mapsforge render theme, built in or imported. */
data class MapTheme(
    val id: String,
    val name: String,
    val description: String,
    /** Imported by the user (can be deleted). */
    val imported: Boolean = false,
)

object MapThemes {
    /** MapAround's hiking style (topo.xml). */
    const val TOPO = "topo"

    /** mapsforge's default theme. */
    const val CLASSIC = "classic"

    /** Ids of imported themes start with this. */
    const val IMPORTED = "user:"

    /**
     * Whether the theme gets MapAround's own map data: contour lines and downloaded marked
     * trails (Topo styles them; an imported theme can, see docs/map-styles.md).
     */
    fun drawsTerrain(themeId: String) = themeId == TOPO || themeId.startsWith(IMPORTED)
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
}

val LocalMapThemes = staticCompositionLocalOf<MapThemeLibrary> { error("No MapThemeLibrary provided") }

/** The categories to draw for [themeId] with the user's [choices], or null for all (no menu). */
fun MapThemeLibrary.categories(themeId: String, choices: Map<String, StyleChoice>): Set<String>? =
    menu(themeId)?.categories(choices[themeId] ?: StyleChoice())
