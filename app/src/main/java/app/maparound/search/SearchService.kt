package app.maparound.search

import app.maparound.AppGraph
import app.maparound.USER_AGENT
import app.maparound.data.MapNames
import app.maparound.routing.GeoPoint
import app.maparound.ui.Searcher
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** The shared [SearchEngine] with this phone's places, offline maps and internet connection. */
object SearchService : Searcher {
    override fun quick(query: String, near: GeoPoint?): List<SearchResult> =
        SearchEngine.quick(query, near, AppGraph.places.places.value)

    override fun offline(query: String, center: GeoPoint, isActive: () -> Boolean): List<SearchResult> =
        offline(query, center, 25.0, isActive = isActive)

    /** Blocking. */
    fun offline(
        query: String,
        center: GeoPoint,
        radiusKm: Double = 25.0,
        files: List<File> = AppGraph.mapFiles.files.value.map { it.file }.filterNot { MapNames.isWorld(it.name) },
        isActive: () -> Boolean = { true },
    ): List<SearchResult> =
        SearchEngine.offline(query, center, files.map { it.path }, Locale.getDefault().language, radiusKm, isActive)

    /** Blocking. */
    override fun online(query: String, near: GeoPoint?): List<SearchResult> = SearchEngine.online(query, near, ::get)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
