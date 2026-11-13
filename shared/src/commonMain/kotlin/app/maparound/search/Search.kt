package app.maparound.search

import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.shared.util.nfd

// Plain Kotlin: matching and ranking search results. Unit-tested.

data class SearchResult(
    val name: String,
    /** What it is and where ("Peak · 812 m", "Village, Israel"…). */
    val detail: String,
    val lat: Double,
    val lon: Double,
    val source: Source,
    /** Lower is a better text match (see [SearchText.score]). */
    val score: Int = 0,
    /** A saved place's id, for places. */
    val placeId: String? = null,
) {
    enum class Source {
        COORDINATES, PLACES, MAP, ONLINE;

        /** The heading of its results, in the app's language. */
        val label: String
            get() = when (this) {
                COORDINATES -> T.poi.sourceCoordinates
                PLACES -> T.poi.sourcePlaces
                MAP -> T.poi.sourceOfflineMap
                ONLINE -> T.poi.sourceOnline
            }
    }

    val point: GeoPoint get() = GeoPoint(lat, lon)
}

object SearchText {
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[\"'`´׳״״’‘.,;:!?()\\[\\]{}/\\\\_-]+")
    private val spaces = Regex("\\s+")

    /** Lower case, no accents or Hebrew vowel points, no punctuation (צה"ל → צהל), single spaces. */
    fun normalize(s: String): String =
        nfd(s)
            .replace(marks, "")
            .lowercase()
            .replace(punctuation, " ")
            .replace(spaces, " ")
            .trim()

    /**
     * How well [name] matches [query] (already normalized): 0 exact, 1 starts with it, 2 a word
     * starts with it, 3 contains it, 4 all query words found; null if it doesn't match.
     */
    fun score(name: String, query: String): Int? {
        if (query.isEmpty()) return null
        val n = normalize(name)
        if (n.isEmpty()) return null
        return when {
            n == query -> 0
            n.startsWith(query) -> 1
            n.split(' ').any { it.startsWith(query) } || n.contains(" $query") -> 2
            n.contains(query) -> 3
            query.contains(' ') && query.split(' ').all { w -> n.contains(w) } -> 4
            else -> null
        }
    }

    /** The best score over a name's language variants. */
    fun bestScore(names: Collection<String>, query: String): Int? = names.mapNotNull { score(it, query) }.minOrNull()

    /**
     * Best matches first, then nearest. Rivers and streets come in many pieces: entries with the
     * same name and kind within 10 km count once (the nearest piece is kept).
     */
    fun rank(results: List<SearchResult>, from: GeoPoint?, limit: Int = 50): List<SearchResult> {
        val sorted = results.sortedWith(compareBy<SearchResult> { it.score }.thenBy { r -> from?.let { Geo.distance(it, r.point) } ?: 0.0 })
        val out = ArrayList<SearchResult>()
        for (r in sorted) {
            if (out.size >= limit) break
            val dup = out.any {
                it.source == r.source && normalize(it.name) == normalize(r.name) &&
                    Geo.distance(it.point, r.point) < (if (it.detail == r.detail) 10_000.0 else 150.0)
            }
            if (!dup) out += r
        }
        return out
    }
}
