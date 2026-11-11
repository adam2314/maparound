@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import app.maparound.data.InstalledMaps
import app.maparound.data.LocalMap
import app.maparound.data.mapBounds
import app.maparound.shared.ioDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.maparound.data.RoutingData
import app.maparound.data.RoutingDataState
import app.maparound.data.TrailData
import app.maparound.data.TrailState
import app.maparound.places.PlaceRepository
import app.maparound.routing.GeoPoint
import app.maparound.routing.Segments
import app.maparound.search.SearchEngine
import app.maparound.search.SearchResult
import app.maparound.shared.fileSystem
import app.maparound.shared.net.httpGetText
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
        val start = rememberUpdatedState(onStart)
        val done = rememberUpdatedState(onResult)
        val scope = rememberCoroutineScope()
        return remember {
            {
                pickDocument { url ->
                    val path = url?.path
                    if (path != null) {
                        start.value()
                        scope.launch { done.value(withContext(ioDispatcher) { import(path, url?.lastPathComponent, move = true) }) }
                    }
                }
            }
        }
    }

    /**
     * Adds a mapsforge .map file (e.g. from OpenAndroMaps) to the map folder: moved when it's a
     * copy made for us, otherwise copied. Blocking.
     */
    fun import(path: String, displayName: String?, move: Boolean): Result<LocalMap> = runCatching {
        var name = (displayName ?: "imported.map").replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.endsWith(".map")) name += ".map"
        if (mapBounds(path) == null) error("Not a mapsforge .map file")
        val source = path.toPath()
        val target = "$dir/$name".toPath()
        fileSystem.delete(target, mustExist = false)
        if (move) {
            runCatching { fileSystem.atomicMove(source, target) }.getOrElse {
                fileSystem.copy(source, target)
                fileSystem.delete(source, mustExist = false)
            }
        } else {
            fileSystem.copy(source, target)
        }
        refresh()
        val m = fileSystem.metadata(target)
        LocalMap(target.toString(), m.size ?: 0, m.lastModifiedAtMillis ?: 0)
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

private fun cells(bounds: GeoBounds): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (lat in floor(bounds.minLat).toInt()..floor(bounds.maxLat - 1e-9).toInt()) {
        for (lon in floor(bounds.minLon).toInt()..floor(bounds.maxLon - 1e-9).toInt()) out += lat to lon
    }
    return out
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
