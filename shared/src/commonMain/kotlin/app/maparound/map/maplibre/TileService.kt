package app.maparound.map.maplibre

import app.maparound.shared.MapTiles
import app.maparound.shared.fileSystem
import app.maparound.shared.util.urlDecode
import app.maparound.terrain.DemIndex
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile

/**
 * What the in-app tile server answers MapLibre with (iOS: TileServer.swift on 127.0.0.1):
 * `/osm/<version>/<z>/<x>/<y>.pbf` vector tiles from the installed mapsforge maps, and from the
 * elevation files in [demDir] `/contours/…/<z>/<x>/<y>.pbf` and `/dem/…/<z>/<x>/<y>.png`
 * (Terrarium) for hill shading; label fonts from [fontsDir] as `/fonts/<font>/<range>.pbf`.
 *
 * [respond] runs on the server's own queue, one request at a time; [setMaps] and
 * [refreshTerrain] off the main thread.
 */
class TileService(private val demDir: String? = null, private val fontsDir: String? = null) {
    /** Where the server listens, e.g. `http://127.0.0.1:52100`; set once it runs. */
    var baseUrl: String = "http://127.0.0.1"

    class Response(val bytes: ByteArray, val contentType: String)

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

    /** Serves these maps from now on. Opens the files: call off the main thread. Returns [version]. */
    fun setMaps(paths: List<String>): String {
        val key = paths.joinToString("|") { p ->
            val m = fileSystem.metadataOrNull(p.toPath())
            "$p:${m?.size}:${m?.lastModifiedAtMillis}"
        }
        if (key != this.key) {
            this.key = key
            tiles = MapTiles(paths)
            version = if (paths.isEmpty()) "0" else (key.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
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
    val glyphsUrl: String? get() = fontsDir?.let { "$baseUrl/fonts/{fontstack}/{range}.pbf" }

    /** The answer for a request path, or null (not found). */
    fun respond(path: String): Response? {
        val parts = path.substringBefore('?').trim('/').split('/')
        if (parts.size == 3 && parts[0] == "fonts") return glyphs(urlDecode(parts[1]), parts[2])
        if (parts.size != 5) return null
        val z = parts[2].toIntOrNull() ?: return null
        val x = parts[3].toIntOrNull() ?: return null
        val y = parts[4].substringBefore('.').toIntOrNull() ?: return null
        if (z !in 0..22 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        return when (parts[0]) {
            "osm" -> Response(tiles.tile(z, x, y), PBF)
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
        val dir = fontsDir ?: return null
        if (!RANGE.matches(range)) return null
        // A stack of several fonts ("A,B"): the first one the app has.
        val font = fontstack.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() && '/' !in it && ".." !in it } ?: return null
        val file = "$dir/$font/$range".toPath()
        val bytes = runCatching { fileSystem.read(file) { readByteArray() } }.getOrDefault(ByteArray(0))
        return Response(bytes, PBF)
    }

    private companion object {
        const val PBF = "application/x-protobuf"
        val RANGE = Regex("[0-9]+-[0-9]+[.]pbf")
    }
}
