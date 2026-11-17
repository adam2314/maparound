package app.maparound.data

import app.maparound.shared.util.nowMillis
import app.maparound.map.GeoBox
import app.maparound.map.MapRegions
import app.maparound.map.RemoteMap
import app.maparound.map.StreamPlan
import app.maparound.shared.fileSystem
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.openFile
import app.maparound.shared.stream.MapStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import okio.use

/**
 * The maps the vector map streams where no installed map reaches ([maps]): for every region of the
 * download list ([MapRegions]), MapAround's map where it's built (from its [catalog], kept in
 * [savedCatalog] for runs without a network), else mapsforge.org's; which ones, see [StreamPlan].
 * None while [enabled] is off, or where the platform doesn't stream ([MapStream.cacheDir] unset).
 */
class MapStreaming(
    private val catalog: MapAroundCatalog,
    enabled: StateFlow<Boolean>,
    installed: StateFlow<List<LocalMap>>,
    scope: CoroutineScope,
    /** A folder to keep the lists in, for runs without a network. */
    private val savedDir: String? = null,
    private val mapsforge: CatalogSource = MapCatalog(),
) {
    /** MapAround's built maps: region path → address. Null until its list is read. */
    private val built = MutableStateFlow<Map<String, String>?>(null)

    /** The maps on mapsforge.org (paths without ".map"). Null until its folders are read. */
    private val basic = MutableStateFlow<Set<String>?>(null)

    val maps: StateFlow<List<RemoteMap>> = combine(enabled, installed, built, basic) { on, local, b, m ->
        if (on && MapStream.enabled && b != null) plan(local, b, m) else emptyList()
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch(Dispatchers.Default) { keep(CATALOG, { catalog.fetchText() }, ::useCatalog) }
        scope.launch(Dispatchers.Default) { keep(MAPSFORGE, ::mapsforgeList, ::useMapsforge) }
    }

    /**
     * A list: what was kept from last time at once, then today's (tried every minute until it's
     * read). One kept less than a day ago is enough: the lists change monthly, and reading them
     * (mapsforge.org's: a few hundred folders) would slow every start.
     */
    private suspend fun keep(name: String, fetch: suspend () -> String?, use: (String) -> Boolean) {
        val saved = savedDir?.let { "$it/$name".toPath() }
        val usedSaved = saved?.let { p -> runCatching { fileSystem.read(p) { readUtf8() } }.getOrNull()?.let(use) } == true
        val savedAt = saved?.let { p -> runCatching { fileSystem.metadataOrNull(p)?.lastModifiedAtMillis }.getOrNull() } ?: 0L
        if (usedSaved && nowMillis() - savedAt < FRESH_MS) return
        while (true) {
            val text = fetch()
            if (text != null && use(text)) {
                saved?.let { p -> runCatching { fileSystem.write(p) { writeUtf8(text) } } }
                return
            }
            delay(RETRY_MS)
        }
    }

    private fun useCatalog(text: String): Boolean {
        val entries = runCatching { MapAroundCatalog.parse(text) }.getOrNull() ?: return false
        built.value = entries.mapNotNull { e -> e.url?.let { e.path.removeSuffix(".map") to it } }.toMap()
        return true
    }

    private fun useMapsforge(text: String): Boolean {
        val paths = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (paths.size < 100) return false
        basic.value = paths
        return true
    }

    /** Every map on mapsforge.org, a path a line, from its folder listings; null when they can't be read. */
    private suspend fun mapsforgeList(): String? {
        val out = ArrayList<String>()
        val folders = ArrayDeque(listOf(""))
        while (folders.isNotEmpty()) {
            val folder = folders.removeFirst()
            val entries = try {
                mapsforge.list(folder)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                return null
            }
            for (e in entries) {
                when {
                    e.isDirectory -> if (".." !in e.path && e.path.length > folder.length && folders.size < 200) folders += e.path
                    e.path.endsWith(".map") -> out += e.path.removeSuffix(".map")
                }
            }
        }
        return out.joinToString("\n")
    }

    private fun plan(local: List<LocalMap>, built: Map<String, String>, mapsforgeMaps: Set<String>?): List<RemoteMap> {
        val regions = MapRegions.bundled ?: return emptyList()
        val maps = local.filterNot { it.isWorld }
        // Installed maps that aren't of a region (imported): where they are.
        val others = maps.filter { regions.byFileName(it.fileName) == null }.mapNotNull { m ->
            runCatching {
                openFile(m.path).use { h ->
                    val b = MapsforgeFile(h).header
                    GeoBox((b.minLat * 1e6).toInt(), (b.minLon * 1e6).toInt(), (b.maxLat * 1e6).toInt(), (b.maxLon * 1e6).toInt())
                }
            }.getOrNull()
        }
        // mapsforge.org's maps of each region: its own, or its parts ("new-zealand-1", "-2"). Before
        // its list is read: its own, if it's there.
        val basic = regions.all.associate { r ->
            val parts = when {
                mapsforgeMaps == null || r.path in mapsforgeMaps -> listOf(r.path)
                else -> (1..9).map { "${r.path}-$it" }.filter { it in mapsforgeMaps }
            }
            r.path to parts.map { Catalog.BASE_URL + it + ".map" }
        }
        val byPath = regions.all.associateBy { it.path }
        return StreamPlan.choose(
            regions = regions.all.associate { it.path to it.box },
            built = built,
            basic = basic,
            installed = maps.map { it.fileName }.toSet(),
            otherMaps = others,
            within = { outer, inner -> byPath.getValue(inner).inner.let { (lat, lon) -> byPath.getValue(outer).contains(lat, lon) } },
        ).map { it.copy(parts = byPath.getValue(it.path).parts) }
    }

    private companion object {
        const val RETRY_MS = 60_000L

        /** A list kept less than this long ago isn't read again at start. */
        const val FRESH_MS = 24 * 3_600_000L
        const val CATALOG = "catalog.json"
        const val MAPSFORGE = "mapsforge-maps.txt"
    }
}
