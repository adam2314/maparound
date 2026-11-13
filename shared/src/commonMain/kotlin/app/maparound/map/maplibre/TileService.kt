package app.maparound.map.maplibre

import app.maparound.shared.MapTiles
import app.maparound.shared.fileSystem
import app.maparound.shared.util.urlDecode
import app.maparound.terrain.DemIndex
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile

/**
 * What the in-app tile server answers MapLibre with (iOS: TileServer.swift on 127.0.0.1;
 * Android: an interceptor in MapLibre's HTTP client): `/osm/<version>/<z>/<x>/<y>.pbf` vector
 * tiles from the installed mapsforge maps (and the built-in [world] where they have nothing), and
 * from the elevation files in [demDir] `/contours/…/<z>/<x>/<y>.pbf` and `/dem/…/<z>/<x>/<y>.png`
 * (Terrarium) for hill shading; label fonts from [fontsDir] as `/fonts/<font>/<range>.pbf`.
 *
 * [respond] runs on the server's own queue, one request at a time; [setMaps] and
 * [refreshTerrain] off the main thread.
 */
class TileService(private val demDir: String? = null, private val fontsDir: String? = null) {
    /** Where the server listens, e.g. `http://127.0.0.1:52100`; set once it runs. */
    var baseUrl: String = "http://127.0.0.1"

    class Response(val bytes: ByteArray, val contentType: String)

    /** The built-in world where the maps have nothing (Android). Set before [setMaps]. */
    var world: app.maparound.map.WorldTile? = null

    /** Label fonts from somewhere other than [fontsDir] (Android: the app's assets): (font, range) → bytes. */
    var glyphSource: ((font: String, range: String) -> ByteArray?)? = null

    /**
     * Answers for paths the platform makes itself (Android: `raster/…`, `trails/…`,
     * `viewshed/…`), given the path's parts; null to go on with the shared ones.
     */
    var extra: ((parts: List<String>) -> Response?)? = null

    /** Like [version], for the downloaded marked-trail data the platform serves under `trails/`; null when none. */
    @Volatile
    var trailsVersion: String? = null

    /**
     * Adds the platform's own features to the offline map's tiles (Android: the cliffs and ridges
     * of the trail download), whose version is [osmExtraVersion].
     */
    var osmExtra: ((app.maparound.shared.mvt.MvtTile, Int, Int, Int) -> Unit)? = null

    @Volatile
    var osmExtraVersion: String? = null

    /** The offline map tiles' version in their URLs: the maps' and the extras'. */
    val osmVersion: String get() = osmExtraVersion?.let { "$version-$it" } ?: version

    /** The viewshed image the platform serves under `viewshed/<key>.png`, and where it lies. */
    @Volatile
    var viewshed: app.maparound.tools.Viewshed? = null

    @Volatile
    private var tiles: MapTiles = MapTiles(emptyList())
    private var key = ""

    /**
     * Changes with the maps (names, sizes, dates), so tile URLs change with them and MapLibre's
     * tile cache never serves tiles of maps that were replaced, also after a restart.
     */
    @Volatile
    var version: String = "0"
        private set

    /** The maps served now (see [setMaps]). */
    var mapPaths: List<String> = emptyList()
        private set

    /** Serves these maps from now on. Opens the files: call off the main thread. Returns [version]. */
    fun setMaps(paths: List<String>): String {
        mapPaths = paths
        val key = (if (world != null) "world2|" else "") + paths.joinToString("|") { p ->
            val m = fileSystem.metadataOrNull(p.toPath())
            "$p:${m?.size}:${m?.lastModifiedAtMillis}"
        }
        if (key != this.key) {
            this.key = key
            tiles = MapTiles(paths, world)
            version = if (paths.isEmpty() && world == null) "0" else (key.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        }
        return version
    }

    @Volatile
    private var terrain: TerrainTiles? = null

    /** Like [version], for the elevation files; null when there are none. */
    @Volatile
    var terrainVersion: String? = null
        private set

    /** Looks at the elevation files again (after downloads). Returns [terrainVersion]. */
    fun refreshTerrain(): String? {
        val dem = demDir?.let(::DemIndex)
        if (dem == null || dem.isEmpty) {
            terrain = null
            terrainVersion = null
        } else {
            val key = (dem.fingerprint.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
            if (key != terrainVersion) {
                terrain = TerrainTiles(dem)
                terrainVersion = key
            }
        }
        return terrainVersion
    }

    /** Font URL template for the style's labels when the fonts are in the app; null otherwise. */
    val glyphsUrl: String? get() = if (fontsDir != null || glyphSource != null) "$baseUrl/fonts/{fontstack}/{range}.pbf" else null

    /** The answer for a request path, or null (not found). */
    fun respond(path: String): Response? {
        val parts = path.substringBefore('?').trim('/').split('/')
        extra?.invoke(parts)?.let { return it }
        if (parts.size == 3 && parts[0] == "fonts") return glyphs(urlDecode(parts[1]), parts[2])
        if (parts.size != 5) return null
        val z = parts[2].toIntOrNull() ?: return null
        val x = parts[3].toIntOrNull() ?: return null
        val y = parts[4].substringBefore('.').toIntOrNull() ?: return null
        if (z !in 0..22 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        return when (parts[0]) {
            "osm" -> Response(tiles.tile(z, x, y, osmExtra), PBF)
            "contours" -> terrain?.let { Response(it.contours(z, x, y), PBF) }
            "dem" -> terrain?.terrarium(z, x, y)?.let { Response(it, "image/png") }
            else -> null
        }
    }

    /**
     * One range of a font. Ranges the app doesn't carry (scripts it leaves out) are answered
     * empty, so MapLibre just has no glyphs there instead of retrying.
     */
    private fun glyphs(fontstack: String, range: String): Response? {
        if (!RANGE.matches(range)) return null
        // A stack of several fonts ("A,B"): the first one the app has.
        val font = fontstack.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() && '/' !in it && ".." !in it } ?: return null
        glyphSource?.let { source -> return Response(source(font, range) ?: ByteArray(0), PBF) }
        val dir = fontsDir ?: return null
        val file = "$dir/$font/$range".toPath()
        val bytes = runCatching { fileSystem.read(file) { readByteArray() } }.getOrDefault(ByteArray(0))
        return Response(bytes, PBF)
    }

    private companion object {
        const val PBF = "application/x-protobuf"
        val RANGE = Regex("[0-9]+-[0-9]+[.]pbf")
    }
}
