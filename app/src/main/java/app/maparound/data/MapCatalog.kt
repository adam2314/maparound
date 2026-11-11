package app.maparound.data

import app.maparound.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** One row of the remote map catalog: either a folder (continent / country split in regions) or a .map file. */
data class CatalogEntry(
    val path: String,          // relative to the catalog root, e.g. "europe/germany/" or "europe/austria.map"
    val isDirectory: Boolean,
    val sizeLabel: String?,    // as printed by the server, e.g. "528M"
    val sizeBytes: Long?,      // approximate, parsed from sizeLabel
    val modified: Long?,       // epoch millis of last server-side update
) {
    val name: String get() = path.removeSuffix("/").substringAfterLast('/')
    val title: String get() = MapNames.pretty(name)
    val localFileName: String get() = MapNames.localFileName(path)
}

/**
 * Browses the per-country OpenStreetMap vector maps published by the mapsforge project
 * (https://download.mapsforge.org/maps/v5/), by reading the server's directory listings.
 */
class MapCatalog(val baseUrl: String = "https://download.mapsforge.org/maps/v5/") {
    private val cache = ConcurrentHashMap<String, List<CatalogEntry>>()

    fun urlFor(entry: CatalogEntry): String = baseUrl + entry.path

    suspend fun list(path: String, forceRefresh: Boolean = false): List<CatalogEntry> {
        if (!forceRefresh) cache[path]?.let { return it }
        val html = withContext(Dispatchers.IO) { fetch(baseUrl + path) }
        return parseListing(html, path).also { cache[path] = it }
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

    companion object {
        // Apache autoindex row: <a href="name">..</a></td><td align="right">2026-08-21 15:48  </td><td align="right"> 43M</td>
        private val ROW = Regex(
            """<a href="([^"?/][^"]*)">[^<]*</a>\s*</td>\s*<td[^>]*>\s*(\d{4}-\d{2}-\d{2} \d{2}:\d{2})?\s*</td>\s*<td[^>]*>\s*([\d.]+[KMGT]?|-)\s*</td>""",
        )

        fun parseListing(html: String, path: String): List<CatalogEntry> {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return ROW.findAll(html).mapNotNull { m ->
                val href = URLDecoder.decode(m.groupValues[1], "UTF-8")
                val isDir = href.endsWith("/")
                if (!isDir && !href.endsWith(".map")) return@mapNotNull null
                val sizeLabel = m.groupValues[3].takeIf { it != "-" }
                CatalogEntry(
                    path = path + href,
                    isDirectory = isDir,
                    sizeLabel = sizeLabel,
                    sizeBytes = sizeLabel?.let(::parseSize),
                    modified = m.groupValues[2].takeIf { it.isNotEmpty() }
                        ?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() },
                )
            }.toList()
        }

        fun parseSize(label: String): Long? {
            val unit = label.last()
            val multiplier = when (unit) {
                'K' -> 1L shl 10
                'M' -> 1L shl 20
                'G' -> 1L shl 30
                'T' -> 1L shl 40
                else -> 1L
            }
            val number = if (unit.isDigit()) label else label.dropLast(1)
            return number.toDoubleOrNull()?.let { (it * multiplier).toLong() }
        }
    }
}
