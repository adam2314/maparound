package app.maparound.shared

import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mvt.MapsforgeToMvt
import okio.FileHandle

/** Opens a file for random reads (same on Android and iOS). */
expect fun openFile(path: String): FileHandle

/**
 * Vector tiles for MapLibre from mapsforge map files: the iOS map's data source. Not thread
 * safe: call from one thread (the tile server's queue).
 */
class MapTiles(paths: List<String>) {
    private val maps: List<MapsforgeFile> = paths.mapNotNull { p -> runCatching { MapsforgeFile(openFile(p)) }.getOrNull() }

    val mapCount: Int get() = maps.size

    /** Bounds of all maps: south, west, north, east. */
    val bounds: List<Double>
        get() = if (maps.isEmpty()) emptyList() else listOf(
            maps.minOf { it.header.minLat }, maps.minOf { it.header.minLon },
            maps.maxOf { it.header.maxLat }, maps.maxOf { it.header.maxLon },
        )

    fun tile(z: Int, x: Int, y: Int): ByteArray = MapsforgeToMvt.tile(maps, z, x, y)
}
