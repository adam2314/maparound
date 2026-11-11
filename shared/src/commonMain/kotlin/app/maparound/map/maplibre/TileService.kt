package app.maparound.map.maplibre

import app.maparound.shared.MapTiles
import app.maparound.shared.fileSystem
import okio.Path.Companion.toPath
import kotlin.concurrent.Volatile

/**
 * What the in-app tile server answers MapLibre with (iOS: TileServer.swift on 127.0.0.1):
 * `/osm/<version>/<z>/<x>/<y>.pbf` vector tiles from the installed mapsforge maps.
 *
 * [respond] runs on the server's own queue, one request at a time; [setMaps] on the main thread.
 */
class TileService {
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

    /** The answer for a request path, or null (not found). */
    fun respond(path: String): Response? {
        val parts = path.substringBefore('?').trim('/').split('/')
        return when (parts.firstOrNull()) {
            "osm" -> {
                if (parts.size != 5) return null
                val z = parts[2].toIntOrNull() ?: return null
                val x = parts[3].toIntOrNull() ?: return null
                val y = parts[4].substringBefore('.').toIntOrNull() ?: return null
                if (z !in 0..22 || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
                Response(tiles.tile(z, x, y), "application/x-protobuf")
            }
            else -> null
        }
    }
}
