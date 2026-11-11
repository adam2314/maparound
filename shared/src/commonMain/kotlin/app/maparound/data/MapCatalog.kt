package app.maparound.data

import app.maparound.shared.net.httpGetText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Browses the per-country OpenStreetMap vector maps published by the mapsforge project
 * (https://download.mapsforge.org/maps/v5/), by reading the server's directory listings
 * (parsed by the shared [Catalog]).
 */
class MapCatalog(val baseUrl: String = Catalog.BASE_URL) : CatalogSource {
    @Volatile
    private var cache: Map<String, List<CatalogEntry>> = emptyMap()

    override fun urlFor(entry: CatalogEntry): String = baseUrl + entry.path

    override suspend fun list(path: String, forceRefresh: Boolean): List<CatalogEntry> {
        if (!forceRefresh) cache[path]?.let { return it }
        val html = withContext(Dispatchers.Default) { httpGetText(baseUrl + path, timeoutMs = 20_000) }
        return Catalog.parseListing(html, path).also { cache = cache + (path to it) }
    }
}
