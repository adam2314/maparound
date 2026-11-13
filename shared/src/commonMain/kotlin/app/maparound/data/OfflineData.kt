package app.maparound.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.openFile
import app.maparound.terrain.GeoBounds
import kotlinx.coroutines.flow.StateFlow
import okio.use

// What the Offline maps screen works with: installed maps, downloads, the catalog, terrain,
// marked trails and routing data. Android implements these with its repositories
// (DownloadManager, files in the app's folder); iOS with URLSession downloads (phase 3).

/** A mapsforge map on the device. */
data class LocalMap(val path: String, val sizeBytes: Long, val modified: Long) {
    val fileName: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    val title: String get() = MapNames.title(fileName)
    val region: String? get() = MapNames.region(fileName)
    val isWorld: Boolean get() = MapNames.isWorld(fileName)
}

/** The area a map covers, from its header; null if it isn't a readable mapsforge map. */
fun mapBounds(path: String): GeoBounds? = runCatching {
    openFile(path).use { h -> MapsforgeFile(h).header.let { GeoBounds(it.minLat, it.minLon, it.maxLat, it.maxLon) } }
}.getOrNull()

/** A map download in progress. */
data class DownloadInfo(
    val id: Long,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    /** "Downloading", "Queued", "Waiting for Wi‑Fi"… */
    val statusText: String,
) {
    val title: String get() = MapNames.title(fileName)
    val progress: Float? get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else null
}

/** Progress of a terrain or trails download (1° cells). */
data class DownloadProgress(val label: String, val done: Int, val total: Int, val failed: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

data class TerrainState(
    val tiles: Int = 0,
    val bytes: Long = 0,
    /** Bumped whenever the set of tiles changes, so map layers know to rebuild. */
    val version: Int = 0,
    val progress: DownloadProgress? = null,
)

data class TrailState(val cells: Int = 0, val bytes: Long = 0, val version: Int = 0, val progress: DownloadProgress? = null)

data class RoutingSegment(val name: String, val bytes: Long)

data class RoutingDownload(val id: Long, val name: String, val done: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
}

data class RoutingDataState(val installed: List<RoutingSegment> = emptyList(), val downloads: List<RoutingDownload> = emptyList())

interface InstalledMaps {
    val files: StateFlow<List<LocalMap>>
    fun delete(map: LocalMap)

    /** Free space where maps are stored, bytes. */
    fun freeBytes(): Long

    /** The platform's file picker for a .map file, which is copied into the app's map folder. */
    @Composable
    fun rememberImporter(onStart: () -> Unit, onResult: (Result<LocalMap>) -> Unit): () -> Unit
}

interface MapDownloads {
    /** Returns an error message, or null if the download was queued. */
    fun enqueue(entry: CatalogEntry, url: String, wifiOnly: Boolean): String?
    fun cancel(id: Long)

    /** Downloads can wait for Wi‑Fi (not the browser's: it downloads at once). */
    val canWaitForWifi: Boolean get() = true

    /** Current downloads (also finishes completed ones). Blocking. */
    fun poll(): List<DownloadInfo>
}

interface CatalogSource {
    suspend fun list(path: String, forceRefresh: Boolean = false): List<CatalogEntry>
    fun urlFor(entry: CatalogEntry): String

    /** Where the listed maps come from, for the list's heading (after [list]). */
    val attribution: String get() = "OpenStreetMap data via mapsforge.org"

    /** Another list to offer instead (MapAround's catalog: the mapsforge.org maps), or null. */
    val alternative: CatalogSource? get() = null
}

/**
 * MapAround's own maps (github.com/adam2314/my-pipeline) say so in their header comment; they have
 * the marked trails built in. [MARKER] is the pipeline's MARKER (maps.py): keep them the same.
 */
object MapAroundMaps {
    const val MARKER = "MapAround maps"

    /** Whether the map at [path] is one of MapAround's, with marked trails (reads its header). */
    fun hasTrails(path: String): Boolean = runCatching {
        openFile(path).use { h -> MapsforgeFile(h).header.comment?.contains(MARKER) == true }
    }.getOrDefault(false)
}

interface TerrainData {
    val state: StateFlow<TerrainState>

    /** All 1° cells overlapping an area. */
    fun cellsFor(bounds: GeoBounds): List<Pair<Int, Int>>
    fun missingCells(bounds: GeoBounds): List<Pair<Int, Int>>
    fun estimateBytes(cells: Int, highDetail: Boolean): Long

    /** Starts downloading the missing tiles. Returns an error message, or null. */
    fun download(label: String, bounds: GeoBounds, highDetail: Boolean): String?
    fun cancel()
    fun deleteAll()

    /** The detail of downloads nobody asked for ([ensure]): the user's last choice where there is one. */
    val autoHighDetail: Boolean get() = false

    /** Whether high detail (1″, ~30 m) can be had here, or standard (3″, ~90 m) only. */
    val offersHighDetail: Boolean get() = true

    /**
     * Gets the elevation data [bounds] needs without asking (the 3D view, line of sight, viewshed):
     * starts downloading its missing cells, a few at most. Null when that's under way (or nothing's
     * missing); otherwise why not (Wi‑Fi only, no space, too big an area…). The data's [state]
     * version changes when it arrives.
     */
    fun ensure(label: String, bounds: GeoBounds): String? {
        val missing = missingCells(bounds)
        if (missing.isEmpty() || state.value.progress != null) return null
        if (missing.size > AUTO_MAX_CELLS) {
            return "This area needs ${missing.size} terrain tiles: download them with the map (Offline maps \u2192 \u22ee \u2192 Terrain)."
        }
        return download(label, bounds, autoHighDetail)
    }

    companion object {
        /** The most 1° cells [ensure] downloads at a time. */
        const val AUTO_MAX_CELLS = 6
    }
}

interface TrailData {
    val state: StateFlow<TrailState>
    fun cellsFor(bounds: GeoBounds): List<Pair<Int, Int>>
    fun missingCells(bounds: GeoBounds): List<Pair<Int, Int>>

    /** Returns an error message, or null when the download started. */
    fun download(label: String, bounds: GeoBounds): String?
    fun cancel()
    fun deleteAll()
}

interface RoutingData {
    val state: StateFlow<RoutingDataState>
    fun isInstalled(name: String): Boolean
    fun segmentsFor(bounds: GeoBounds): List<String>

    /** File sizes on the server; names without data (sea) are missing. */
    suspend fun remoteSizes(): Map<String, Long>

    /** Returns an error message, or null if queued. */
    fun download(names: List<String>, wifiOnly: Boolean): String?
    fun cancel(id: Long)
    fun delete(name: String)

    /** Updates download progress (and finishes completed ones). Blocking. */
    fun poll()
}

/** Everything the Offline maps screen needs from the platform. */
class OfflineData(
    val maps: InstalledMaps,
    val downloads: MapDownloads,
    val catalog: CatalogSource,
    val terrain: TerrainData,
    val trails: TrailData,
    val routing: RoutingData,
    /**
     * The app has its own world map (land and sea, lakes, rivers, borders, names), so mapsforge's
     * world overview map isn't needed (Android).
     */
    val builtInWorld: Boolean = false,
    /** Offline routing and marked trails can be added to a map (not in the web app yet). */
    val addOns: Boolean = true,
    /** Terrain (contour lines & hill shading) can be added to a map. */
    val terrainAddOn: Boolean = addOns,
)

val LocalOfflineData = staticCompositionLocalOf<OfflineData> { error("No OfflineData provided") }
