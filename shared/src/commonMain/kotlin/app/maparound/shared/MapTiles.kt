package app.maparound.shared

import app.maparound.data.MapNames
import app.maparound.map.GeoBox
import app.maparound.map.MapRegions
import app.maparound.map.Coverage
import app.maparound.map.RemoteMap
import app.maparound.map.WorldTile
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mvt.MapsforgeToMvt
import app.maparound.shared.mvt.MvtTile
import app.maparound.shared.mvt.WorldToMvt
import app.maparound.shared.stream.MapStream
import app.maparound.shared.stream.StreamUnavailable
import okio.FileHandle

/** Opens a file for random reads (same on Android and iOS). */
expect fun openFile(path: String): FileHandle

/**
 * Vector tiles for MapLibre from mapsforge map files, and the built-in [world] where those have
 * nothing (Android; on iOS mapsforge's world overview map, when installed). Where no installed map
 * reaches, the [remote] maps are read over the internet ([MapStream]), from the zoom the world
 * makes room for countries' maps. Not thread safe: call from one thread (the tile server's).
 */
class MapTiles(paths: List<String>, private val world: WorldTile? = null, private val remote: List<RemoteMap> = emptyList()) {
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

    /** Where the installed maps have sea, for the contours and hill shading to stop at the coast. */
    val sea: app.maparound.terrain.SeaCells by lazy { app.maparound.terrain.SeaCells(maps) }

    /** The remote maps opened (their headers read), by address; and those that aren't maps. */
    private val streamed = HashMap<String, MapsforgeFile>()
    private val broken = HashSet<String>()

    /**
     * Whether the last [tile] has everything: false when a remote map it needed couldn't be read
     * (no network), so it was made without it, for the map to ask for it again soon.
     */
    var complete: Boolean = true
        private set

    /** Bounds of all maps: south, west, north, east. */
    val bounds: List<Double>
        get() = if (maps.isEmpty()) emptyList() else listOf(
            maps.minOf { it.header.minLat }, maps.minOf { it.header.minLon },
            maps.maxOf { it.header.maxLat }, maps.maxOf { it.header.maxLon },
        )

    /** The tile z/x/y; [ground]: the terrain's height at a point, for buildings' relief. */
    fun tile(z: Int, x: Int, y: Int, ground: ((Double, Double) -> Float)? = null): ByteArray {
        // MapLibre's tiles are 512 px: shown like 256-pixel tiles one zoom deeper.
        val zoom = z + 1
        val countries = zoom >= WorldTile.COUNTRY_MAPS_FROM_ZOOM
        val box = GeoBox(
            (MapsforgeFile.tileLat(y + 1, z) * 1e6).toInt(), (MapsforgeFile.tileLon(x, z) * 1e6).toInt(),
            (MapsforgeFile.tileLat(y, z) * 1e6).toInt(), (MapsforgeFile.tileLon(x + 1, z) * 1e6).toInt(),
        )
        complete = true
        val far = if (countries && remote.isNotEmpty()) opened(remote.filter { it.reaches(box) }) else emptyList()
        var t = make(z, x, y, box, countries, far, ground)
        if (far.isNotEmpty() && !complete) {
            // A remote map's data couldn't be read: the tile without those maps.
            t = make(z, x, y, box, countries, emptyList(), ground)
        }
        if (!complete) MapStream.missed()
        return t.encode()
    }

    private fun make(z: Int, x: Int, y: Int, box: GeoBox, countries: Boolean, far: List<Pair<RemoteMap, MapsforgeFile>>, ground: ((Double, Double) -> Float)?): MvtTile {
        val t = MvtTile(z, x, y)
        val all = if (far.isEmpty()) maps else maps + far.map { it.second }
        try {
            if (world == null) {
                MapsforgeToMvt.fill(t, all, z, x, y, ground = ground)
            } else {
                if (countries) MapsforgeToMvt.fill(t, all, z, x, y, ground = ground)
                var holes = if (countries) areas.filter { it.intersects(box) } else emptyList()
                var inTile = if (countries) covers.filter { it.box.intersects(box) } else emptyList()
                if (far.isNotEmpty()) {
                    holes = holes + far.map { (_, m) -> m.area() }
                    inTile = inTile + far.map { (r, m) -> Coverage(m.area(), MapRegions.bundled?.byFileName(r.fileName)) }
                }
                world.draw(box, z + 1, holes, WorldToMvt(t), covers = inTile)
            }
        } catch (e: StreamUnavailable) {
            complete = false
        }
        return t
    }

    /** The remote maps' files, opened at first use; those that can't be read now are left out. */
    private fun opened(wanted: List<RemoteMap>): List<Pair<RemoteMap, MapsforgeFile>> = wanted.mapNotNull { r ->
        streamed[r.url]?.let { return@mapNotNull r to it }
        if (r.url in broken) return@mapNotNull null
        try {
            (r to MapsforgeFile(MapStream.open(r.url), wholeIndex = false)).also { streamed[r.url] = it.second }
        } catch (e: StreamUnavailable) {
            complete = false
            null
        } catch (e: Exception) {
            // Not a map (the server sent something else): left out from now on.
            broken += r.url
            null
        }
    }

    private fun MapsforgeFile.area() =
        GeoBox((header.minLat * 1e6).toInt(), (header.minLon * 1e6).toInt(), (header.maxLat * 1e6).toInt(), (header.maxLon * 1e6).toInt())
}
