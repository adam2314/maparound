package app.maparound.data

import app.maparound.shared.net.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.concurrent.Volatile

/**
 * MapAround's own maps (github.com/adam2314/my-pipeline): OpenStreetMap data as mapsforge maps with
 * marked trails, cliffs, path and track grades and more, built every month and listed in
 * `catalog.json` on that repository's `maps-latest` release. Regions too big for the free build
 * machines are listed as needing funding. Same paths and file names as the mapsforge.org maps, so
 * maps from there get updated by these. While the list can't be read (offline, or not published
 * yet) the mapsforge.org maps ([fallback]) are offered.
 */
class MapAroundCatalog(
    private val catalogUrl: String = CATALOG_URL,
    private val fallback: CatalogSource = MapCatalog(),
) : CatalogSource {
    @Volatile
    private var maps: List<CatalogEntry>? = null

    @Volatile
    private var usingFallback = false

    override val attribution: String
        get() = if (usingFallback) fallback.attribution else "OpenStreetMap data with marked trails, built by MapAround"

    override suspend fun list(path: String, forceRefresh: Boolean): List<CatalogEntry> {
        val all = try {
            maps.takeUnless { forceRefresh || it == null }
                ?: parse(withContext(Dispatchers.Default) { httpGetText(catalogUrl, timeoutMs = 20_000) }).also { maps = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (all == null) {
            usingFallback = true
            return fallback.list(path, forceRefresh)
        }
        usingFallback = false
        return listing(all, path)
    }

    override fun urlFor(entry: CatalogEntry): String = entry.url ?: fallback.urlFor(entry)

    companion object {
        const val CATALOG_URL = "https://github.com/adam2314/my-pipeline/releases/download/maps-latest/catalog.json"

        /** The maps in a catalog.json (version 1), as .map entries with their paths. */
        fun parse(json: String): List<CatalogEntry> {
            val root = Json.parseToJsonElement(json).jsonObject
            return root["maps"]!!.jsonArray.mapNotNull { e ->
                val m = e as? JsonObject ?: return@mapNotNull null
                val path = m.string("path") ?: return@mapNotNull null
                val url = m.string("url")
                val size = m["size"]?.jsonPrimitive?.longOrNull
                CatalogEntry(
                    path = "$path.map",
                    isDirectory = false,
                    sizeLabel = size?.let(::sizeLabel),
                    sizeBytes = size,
                    modified = m.string("date")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
                    url = url,
                    unavailable = when {
                        url != null -> null
                        m.string("status") == "needs-funding" -> CatalogEntry.Unavailable.NEEDS_FUNDING
                        else -> CatalogEntry.Unavailable.NOT_BUILT
                    },
                )
            }
        }

        /** What's in folder [path] ("" or e.g. "europe/germany/"): its maps and subfolders, by name. */
        fun listing(all: List<CatalogEntry>, path: String): List<CatalogEntry> {
            val out = LinkedHashMap<String, CatalogEntry>()
            for (m in all) {
                if (!m.path.startsWith(path)) continue
                val rest = m.path.removePrefix(path)
                val slash = rest.indexOf('/')
                if (slash < 0) {
                    out[m.path] = m
                } else {
                    val dir = path + rest.substring(0, slash + 1)
                    out.getOrPut(dir) { CatalogEntry(dir, isDirectory = true, sizeLabel = null, sizeBytes = null, modified = null) }
                }
            }
            return out.values.sortedBy { it.path }
        }

        /** As the mapsforge server prints sizes: "850K", "43M", "1.2G". */
        fun sizeLabel(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return when {
                mb < 1 -> "${(bytes / 1024).coerceAtLeast(1)}K"
                mb < 10 -> "${(mb * 10).toLong() / 10.0}M"
                mb < 1024 -> "${mb.toLong()}M"
                else -> "${(mb / 1024 * 10).toLong() / 10.0}G"
            }
        }

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
    }
}
