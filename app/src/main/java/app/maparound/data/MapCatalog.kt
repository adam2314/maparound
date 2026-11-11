package app.maparound.data

import app.maparound.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Browses the per-country OpenStreetMap vector maps published by the mapsforge project
 * (https://download.mapsforge.org/maps/v5/), by reading the server's directory listings
 * (parsed by the shared [Catalog]).
 */
class MapCatalog(val baseUrl: String = Catalog.BASE_URL) {
    private val cache = ConcurrentHashMap<String, List<CatalogEntry>>()

    fun urlFor(entry: CatalogEntry): String = baseUrl + entry.path

    suspend fun list(path: String, forceRefresh: Boolean = false): List<CatalogEntry> {
        if (!forceRefresh) cache[path]?.let { return it }
        val html = withContext(Dispatchers.IO) { fetch(baseUrl + path) }
        return Catalog.parseListing(html, path).also { cache[path] = it }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (conn.responseCode !in 200..299) error("Server returned HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
