package app.maparound.raster

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.routing.GeoPoint
import kotlinx.coroutines.flow.StateFlow

/**
 * A raster map the user added: a GeoTIFF, an RMAP, or a tile database (MBTiles, SQLiteDB). Large
 * files are read where they are, never copied.
 */
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
    /** [FORMAT_RMAP], [FORMAT_GEOTIFF], [FORMAT_MBTILES] or [FORMAT_SQLITEDB]. */
    val format: String = FORMAT_RMAP,
) {
    companion object {
        const val FORMAT_RMAP = "rmap"
        const val FORMAT_GEOTIFF = "geotiff"
        const val FORMAT_MBTILES = "mbtiles"
        const val FORMAT_SQLITEDB = "sqlitedb"
    }

    val center: GeoPoint get() = GeoPoint((north + south) / 2, (west + east) / 2)

    /**
     * Goes in its tiles' addresses: changes with the file and with how the app draws raster maps
     * ([RENDERING]), so the map never shows tiles it kept from before.
     */
    val revision: String
        get() = (listOf(sizeBytes, width, height, levels, format, RENDERING).hashCode().toLong() and 0xFFFFFFFFL).toString(16)
}

/** Raised when raster maps are drawn differently (their tiles are made again). */
private const val RENDERING = 2

/** A raster map being made ready to show (a big GeoTIFF's reduced copies), [fraction] from 0 to 1. */
data class RasterPreparing(val name: String, val fraction: Double)

/** The raster maps the user added (Android: picked files with persisted read access). */
interface RasterLibrary {
    val maps: StateFlow<List<RasterMap>>

    /** The map being made ready after it was added, or null. */
    val preparing: StateFlow<RasterPreparing?> get() = NOT_PREPARING

    fun get(id: String): RasterMap?

    fun remove(id: String)

    /** Raster maps can be added (not in the web app yet). */
    val available: Boolean get() = true

    /** The platform's file picker for an .rmap; the file is read where it is, never copied. */
    @Composable
    fun rememberPicker(onResult: (Result<RasterMap>) -> Unit): () -> Unit
}

private val NOT_PREPARING: StateFlow<RasterPreparing?> = kotlinx.coroutines.flow.MutableStateFlow(null)

val LocalRasters = staticCompositionLocalOf<RasterLibrary> { error("No RasterLibrary provided") }
