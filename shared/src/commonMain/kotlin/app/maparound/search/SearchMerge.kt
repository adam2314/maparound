package app.maparound.search

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint

/**
 * Every source's results as one short list to choose from at a glance (the car screen): typed
 * coordinates first, then the best matching names, nearer first among equals; the same place from
 * two sources (a saved place, the offline map, online) once.
 */
object SearchMerge {
    /** The same name within this far (metres) is the same place. */
    private const val SAME_PLACE_M = 300.0

    fun merge(lists: List<List<SearchResult>>, near: GeoPoint?, limit: Int): List<SearchResult> {
        fun away(r: SearchResult) = near?.let { Geo.distance(it.lat, it.lon, r.lat, r.lon) } ?: 0.0
        val sorted = lists.flatten().sortedWith(
            compareBy<SearchResult>({ it.source != SearchResult.Source.COORDINATES }, { it.score }, { away(it) }),
        )
        val out = ArrayList<SearchResult>()
        for (r in sorted) {
            if (out.size >= limit) break
            val name = SearchText.normalize(r.name)
            if (out.any { SearchText.normalize(it.name) == name && Geo.distance(it.lat, it.lon, r.lat, r.lon) < SAME_PLACE_M }) continue
            out += r
        }
        return out
    }

    /**
     * The one the driver needn't choose: the only result, typed coordinates, or the one saved place
     * of exactly that name ("navigate home"); null when there's a choice to make.
     */
    fun clear(results: List<SearchResult>): SearchResult? = when {
        results.size == 1 -> results[0]
        results.firstOrNull()?.source == SearchResult.Source.COORDINATES -> results[0]
        else -> results.filter { it.source == SearchResult.Source.PLACES && it.score == 0 }.singleOrNull()
    }
}
