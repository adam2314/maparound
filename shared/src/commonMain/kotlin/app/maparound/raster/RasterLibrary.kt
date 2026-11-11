package app.maparound.raster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.routing.GeoPoint
import kotlinx.coroutines.flow.StateFlow

/** A raster map (RMAP) the user added. Large files are read where they are, never copied. */
data class RasterMap(
    val id: String,
    val name: String,
    /** Where the file is: a content:// URI on Android, a bookmark or path on iOS. */
    val source: String,
    val sizeBytes: Long,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
    val projection: String,
    val width: Int,
    val height: Int,
    val levels: Int,
) {
    val center: GeoPoint get() = GeoPoint((north + south) / 2, (west + east) / 2)
}

/** The raster maps the user added (Android: picked files with persisted read access). */
interface RasterLibrary {
    val maps: StateFlow<List<RasterMap>>

    fun get(id: String): RasterMap?

    fun remove(id: String)

    /** The platform's file picker for an .rmap; the file is read where it is, never copied. */
    @Composable
    fun rememberPicker(onResult: (Result<RasterMap>) -> Unit): () -> Unit
}

val LocalRasters = staticCompositionLocalOf<RasterLibrary> { error("No RasterLibrary provided") }
