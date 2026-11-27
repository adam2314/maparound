package app.maparound.car

import app.maparound.routing.GeoPoint
import app.maparound.search.SearchMerge
import app.maparound.search.SearchResult
import app.maparound.search.SearchService
import app.maparound.sources.SourceKind
import app.maparound.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The car's search: the phone's, every source at once (saved places, the downloaded maps' names
 * around [near], online when there's a connection and it isn't switched off), merged into one short
 * list ([SearchMerge]).
 */
object CarSearch {
    /** Around a car: further than the phone's search looks in the downloaded maps. */
    private const val OFFLINE_RADIUS_KM = 60.0

    suspend fun find(query: String, near: GeoPoint?, limit: Int): List<SearchResult> = coroutineScope {
        val quick = SearchService.quick(query, near)
        val offline = async(Dispatchers.IO) {
            val ctx = coroutineContext
            near?.let { SearchService.offline(query, it, OFFLINE_RADIUS_KM, isActive = { ctx.isActive }) }.orEmpty()
        }
        val online = async(Dispatchers.IO) {
            if (Sources.isOff(SourceKind.SEARCH)) emptyList()
            else runCatching { SearchService.online(query, near) }.getOrDefault(emptyList())
        }
        withContext(Dispatchers.Default) { SearchMerge.merge(listOf(quick, offline.await(), online.await()), near, limit) }
    }
}
