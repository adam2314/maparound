@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.maparound.data.CatalogEntry
import app.maparound.data.DownloadInfo
import app.maparound.data.InstalledMaps
import app.maparound.data.LocalMap
import app.maparound.data.MapDownloads
import app.maparound.data.RoutingData
import app.maparound.data.RoutingDataState
import app.maparound.data.TerrainData
import app.maparound.data.TerrainState
import app.maparound.data.TrailData
import app.maparound.data.TrailState
import app.maparound.places.PlaceRepository
import app.maparound.routing.GeoPoint
import app.maparound.routing.Segments
import app.maparound.search.SearchEngine
import app.maparound.search.SearchResult
import app.maparound.shared.fileSystem
import app.maparound.shared.net.httpGetText
import app.maparound.terrain.DemIndex
import app.maparound.terrain.GeoBounds
import app.maparound.ui.Searcher
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import kotlin.math.floor

/** Free space on the volume of [path], bytes. */
fun freeBytes(path: String): Long {
    val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path, null) ?: return 0
    return (attrs[NSFileSystemFreeSize] as? NSNumber)?.longLongValue ?: 0
}

/** The .map files in the app's Documents/maps folder. */
class IosInstalledMaps(val dir: String) : InstalledMaps {
    private val _files = MutableStateFlow<List<LocalMap>>(emptyList())
    override val files: StateFlow<List<LocalMap>> = _files.asStateFlow()

    init {
        fileSystem.createDirectories(dir.toPath())
        refresh()
    }

    fun refresh() {
        _files.value = fileSystem.list(dir.toPath())
            .filter { it.name.endsWith(".map") }
            .mapNotNull { p ->
                val m = fileSystem.metadataOrNull(p) ?: return@mapNotNull null
                if (!m.isRegularFile) null else LocalMap(p.toString(), m.size ?: 0, m.lastModifiedAtMillis ?: 0)
            }
            .sortedWith(compareBy<LocalMap> { !it.isWorld }.thenBy { it.title })
    }

    override fun delete(map: LocalMap) {
        fileSystem.delete(map.path.toPath(), mustExist = false)
        refresh()
    }

    override fun freeBytes(): Long = freeBytes(dir)

    @Composable
    override fun rememberImporter(onStart: () -> Unit, onResult: (Result<LocalMap>) -> Unit): () -> Unit {
        val done = rememberUpdatedState(onResult)
        return remember { { done.value(Result.failure(Exception(LATER))) } }
    }
}

/** Search: the shared engine over this phone's places and maps, online through NSURLSession. */
class IosSearcher(private val places: PlaceRepository, private val maps: InstalledMaps) : Searcher {
    override fun quick(query: String, near: GeoPoint?): List<SearchResult> = SearchEngine.quick(query, near, places.places.value)

    override fun offline(query: String, center: GeoPoint, isActive: () -> Boolean): List<SearchResult> =
        SearchEngine.offline(query, center, maps.files.value.filterNot { it.isWorld }.map { it.path }, language(), 25.0, isActive)

    override fun online(query: String, near: GeoPoint?): List<SearchResult> =
        SearchEngine.online(query, near) { url -> httpGetText(url, mapOf("Accept-Language" to "${language()},en;q=0.8"), 12_000) }

    private fun language(): String = NSLocale.currentLocale.languageCode
}

private const val LATER = "Coming to iPhone in a later version"

// What phase 3 hasn't built yet on iOS: answers that say so instead of doing nothing.

object PendingDownloads : MapDownloads {
    override fun enqueue(entry: CatalogEntry, url: String, wifiOnly: Boolean): String = "Map downloads: $LATER"
    override fun cancel(id: Long) = Unit
    override fun poll(): List<DownloadInfo> = emptyList()
}

private fun cells(bounds: GeoBounds): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (lat in floor(bounds.minLat).toInt()..floor(bounds.maxLat - 1e-9).toInt()) {
        for (lon in floor(bounds.minLon).toInt()..floor(bounds.maxLon - 1e-9).toInt()) out += lat to lon
    }
    return out
}

class PendingTerrain(private val dir: String) : TerrainData {
    override val state: StateFlow<TerrainState> = MutableStateFlow(TerrainState())
    override fun cellsFor(bounds: GeoBounds) = cells(bounds)
    override fun missingCells(bounds: GeoBounds) =
        cells(bounds).filterNot { (lat, lon) -> fileSystem.exists("$dir/${DemIndex.nameFor(lat, lon)}.hgt".toPath()) }
    override fun estimateBytes(cells: Int, highDetail: Boolean): Long = cells * if (highDetail) 3601L * 3601 * 2 else 1201L * 1201 * 2
    override fun download(label: String, bounds: GeoBounds, highDetail: Boolean): String = "Terrain downloads: $LATER"
    override fun cancel() = Unit
    override fun deleteAll() = Unit
}

object PendingTrails : TrailData {
    override val state: StateFlow<TrailState> = MutableStateFlow(TrailState())
    override fun cellsFor(bounds: GeoBounds) = cells(bounds)
    override fun missingCells(bounds: GeoBounds) = cells(bounds)
    override fun download(label: String, bounds: GeoBounds): String = "Marked trails: $LATER"
    override fun cancel() = Unit
    override fun deleteAll() = Unit
}

object PendingRouting : RoutingData {
    override val state: StateFlow<RoutingDataState> = MutableStateFlow(RoutingDataState())
    override fun isInstalled(name: String) = false
    override fun segmentsFor(bounds: GeoBounds): List<String> = Segments.namesFor(bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon)
    override suspend fun remoteSizes(): Map<String, Long> = emptyMap()
    override fun download(names: List<String>, wifiOnly: Boolean): String = "Offline routing: $LATER"
    override fun cancel(id: Long) = Unit
    override fun delete(name: String) = Unit
    override fun poll() = Unit
}
