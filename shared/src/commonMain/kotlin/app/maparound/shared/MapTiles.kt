package app.maparound.shared

import app.maparound.data.MapNames
import app.maparound.map.GeoBox
import app.maparound.map.MapRegions
import app.maparound.map.Coverage
import app.maparound.map.WorldTile
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mvt.MapsforgeToMvt
import app.maparound.shared.mvt.MvtTile
import app.maparound.shared.mvt.WorldToMvt
import okio.FileHandle

/** Opens a file for random reads (same on Android and iOS). */
expect fun openFile(path: String): FileHandle

/**
 * Vector tiles for MapLibre from mapsforge map files, and the built-in [world] where those have
 * nothing (Android; on iOS mapsforge's world overview map, when installed). Not thread safe: call
 * from one thread (the tile server's).
 */
class MapTiles(paths: List<String>, private val world: WorldTile? = null) {
    // With the built-in world, mapsforge's world overview map (land and sea only) isn't used.
    private val opened: List<Pair<String, MapsforgeFile>> = paths
        .filterNot { world != null && MapNames.isWorld(it.substringAfterLast('/').substringAfterLast('\\')) }
        .mapNotNull { p -> runCatching { p to MapsforgeFile(openFile(p)) }.getOrNull() }
    private val maps: List<MapsforgeFile> = opened.map { it.second }

    /** The maps' file names ("europe__liechtenstein.map"). */
    private val names: List<String> = opened.map { it.first.substringAfterLast('/').substringAfterLast('\\') }

    private val areas = maps.map { m ->
        val h = m.header
        GeoBox((h.minLat * 1e6).toInt(), (h.minLon * 1e6).toInt(), (h.maxLat * 1e6).toInt(), (h.maxLon * 1e6).toInt())
    }

    /** Where each map really draws: its region's outline when the download list knows it. */
    private val covers: List<Coverage> by lazy {
        val regions = if (maps.isEmpty()) null else MapRegions.bundled
        names.mapIndexed { i, name -> Coverage(areas[i], regions?.byFileName(name)) }
    }

    val mapCount: Int get() = maps.size

    /** Bounds of all maps: south, west, north, east. */
    val bounds: List<Double>
        get() = if (maps.isEmpty()) emptyList() else listOf(
            maps.minOf { it.header.minLat }, maps.minOf { it.header.minLon },
            maps.maxOf { it.header.maxLat }, maps.maxOf { it.header.maxLon },
        )

    /** The tile z/x/y; [extra] adds the platform's own features (see TileService.osmExtra). */
    fun tile(z: Int, x: Int, y: Int, extra: ((MvtTile, Int, Int, Int) -> Unit)? = null, ground: ((Double, Double) -> Float)? = null): ByteArray {
        val t = MvtTile(z, x, y)
        if (world == null) {
            MapsforgeToMvt.fill(t, maps, z, x, y, ground = ground)
        } else {
            // MapLibre's tiles are 512 px: shown like 256-pixel tiles one zoom deeper.
            val zoom = z + 1
            val countries = zoom >= WorldTile.COUNTRY_MAPS_FROM_ZOOM
            if (countries) MapsforgeToMvt.fill(t, maps, z, x, y, ground = ground)
            val box = GeoBox(
                (MapsforgeFile.tileLat(y + 1, z) * 1e6).toInt(), (MapsforgeFile.tileLon(x, z) * 1e6).toInt(),
                (MapsforgeFile.tileLat(y, z) * 1e6).toInt(), (MapsforgeFile.tileLon(x + 1, z) * 1e6).toInt(),
            )
            val holes = if (countries) areas.filter { it.intersects(box) } else emptyList()
            val inTile = if (countries) covers.filter { it.box.intersects(box) } else emptyList()
            world.draw(box, zoom, holes, WorldToMvt(t), covers = inTile)
        }
        extra?.invoke(t, z, x, y)
        return t.encode()
    }
}
