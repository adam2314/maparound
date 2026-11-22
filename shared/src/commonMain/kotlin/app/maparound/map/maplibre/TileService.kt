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

    /** [temporary]: made without data that couldn't be read now (streamed maps); ask again soon. */
    class Response(val bytes: ByteArray, val contentType: String, val temporary: Boolean = false)

    /** The built-in world where the maps have nothing (Android). Set before [setMaps]. */
    var world: app.maparound.map.WorldTile? = null

    /** Label fonts from somewhere other than [fontsDir] (Android: the app's assets): (font, range) → bytes. */
    var glyphSource: ((font: String, range: String) -> ByteArray?)? = null

    /**
     * Answers for paths the platform makes itself (Android: `raster/…`), given the path's parts;
     * null to go on with the shared ones.
     */
    var extra: ((parts: List<String>) -> Response?)? = null

    /**
     * Buildings in the offline map's tiles get how the ground varies under them (from the elevation
     * files), for 3D views that stand them on the terrain by their middle (MapLibre GL JS: the web).
     */
    var buildingRelief: Boolean = false

    /** The offline map tiles' version in their URLs: the maps', and the terrain's when it's in them. */
    val osmVersion: String
        get() = version +
            (if (buildingRelief) terrainVersion?.let { "-t$it" }.orEmpty() else "") +
            // Streamed maps readable again after tiles were made without them: asked for anew.
            (if (remoteMaps.isNotEmpty()) "-s${app.maparound.shared.stream.MapStream.generation.value}" else "")

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

    /** The maps streamed where the installed ones don't reach (see [setMaps]). */
    var remoteMaps: List<app.maparound.map.RemoteMap> = emptyList()
        private set

    /**
     * Serves these maps from now on, and the [remote] ones where they don't reach. Opens the files:
     * call off the main thread. Returns [version].
     */
    fun setMaps(paths: List<String>, remote: List<app.maparound.map.RemoteMap> = emptyList()): String {
        mapPaths = paths
        remoteMaps = remote
        val key = (if (world != null) "world2|" else "") + paths.joinToString("|") { p ->
            val m = fileSystem.metadataOrNull(p.toPath())
            "$p:${m?.size}:${m?.lastModifiedAtMillis}"
        } + (if (remote.isEmpty()) "" else "|stream:" + remote.joinToString(",") { it.url })
        if (key != this.key) {
            this.key = key
            tiles = MapTiles(paths, world, remote)
            version = if (paths.isEmpty() && remote.isEmpty() && world == null) "0" else (key.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        }
        return version
    }

    @Volatile
    private var terrain: TerrainTiles? = null

    /** Like [version], for the elevation files; null when there are none. */
    @Volatile
    var terrainVersion: String? = null
        private set

    /**
     * The contour and hill-shading tiles' version in their URLs: the elevation files', and the
     * maps' (whose sea they're cut at); null when there are no elevation files.
     */
    val terrainKey: String? get() = terrainVersion?.let { "$it-$version" }

    /** Looks at the elevation files again (after downloads). Returns [terrainVersion]. */
    fun refreshTerrain(): String? {
        val dem = demDir?.let(::DemIndex)
        if (dem == null || dem.isEmpty) {
            terrain = null
            terrainVersion = null
        } else {
            val key = (dem.fingerprint.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
            if (key != terrainVersion) {
                // Contours and shading stop at the coast of the maps served then.
                terrain = TerrainTiles(dem) { tiles.takeIf { it.mapCount > 0 }?.sea?.let { s -> s::isSea } }
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
        if (parts.size == 2 && parts[0] == "viewshed") return viewshedImage(parts[1])
        if (parts.size != 5) return null
        val z = parts[2].toIntOrNull() ?: return null
        val x = parts[3].toIntOrNull() ?: return null
        val y = parts[4].substringBefore('.').toIntOrNull() ?: return null
        if (z !in 0..22 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        return when (parts[0]) {
            "osm" -> tiles.let { m ->
                val bytes = m.tile(z, x, y, ground = terrain?.takeIf { buildingRelief }?.let { t -> t::elevation })
                Response(bytes, PBF, temporary = !m.complete)
            }
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

    /**
     * The [viewshed] as an image, one pixel a cell: green where it's in sight, red where hidden,
     * clear outside the circle. Only the one shown now ([name] is its key): an older one's URL gets
     * nothing.
     */
    private fun viewshedImage(name: String): Response? {
        val vs = viewshed ?: return null
        if (name != "${MapStyle.viewshedKey(vs)}.png") return null
        val pixels = ByteArray(vs.size * vs.size * 4)
        for (i in 0 until vs.size * vs.size) {
            val colour = when (vs.cells[i].toInt()) {
                1 -> VIEWSHED_SEEN
                0 -> VIEWSHED_HIDDEN
                else -> continue
            }
            for (c in 0 until 4) pixels[i * 4 + c] = colour[c]
        }
        return Response(Png.rgba(vs.size, vs.size, pixels), "image/png")
    }

    private companion object {
        /** In sight and hidden: red, green, blue and alpha. */
        val VIEWSHED_SEEN = byteArrayOf(67, 160.toByte(), 71, 80)
        val VIEWSHED_HIDDEN = byteArrayOf(198.toByte(), 40, 40, 115)
        const val PBF = "application/x-protobuf"
        val RANGE = Regex("[0-9]+-[0-9]+[.]pbf")
    }
}
