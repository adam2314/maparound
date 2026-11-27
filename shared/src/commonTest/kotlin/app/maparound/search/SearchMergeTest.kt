package app.maparound.search

import app.maparound.routing.GeoPoint
import app.maparound.search.SearchResult.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchMergeTest {
    private fun r(name: String, lat: Double, lon: Double, source: Source, score: Int = 0) = SearchResult(name, "", lat, lon, source, score)

    @Test
    fun bestFirstNearerAmongEqualsSamePlaceOnce() {
        val here = GeoPoint(32.80, 35.00)
        val places = listOf(r("Haifa Port", 32.82, 35.0, Source.PLACES, 1))
        val offline = listOf(r("Haifa", 32.79, 34.99, Source.MAP, 0), r("Haifa Bay", 32.85, 35.05, Source.MAP, 1))
        // The same city from online, and one far away.
        val online = listOf(r("Haifa", 32.7895, 34.9895, Source.ONLINE, 0), r("Haifa", 40.0, -75.0, Source.ONLINE, 0))
        val merged = SearchMerge.merge(listOf(places, offline, online), here, limit = 10)
        assertEquals(listOf("Haifa", "Haifa", "Haifa Port", "Haifa Bay"), merged.map { it.name })
        assertEquals(Source.MAP, merged[0].source)
        assertEquals(40.0, merged[1].lat)
        assertEquals(2, SearchMerge.merge(listOf(places, offline, online), here, limit = 2).size)
        // Typed coordinates come first.
        val coords = r("32.1, 34.8", 32.1, 34.8, Source.COORDINATES, 9)
        assertEquals(Source.COORDINATES, SearchMerge.merge(listOf(offline, listOf(coords)), here, 10).first().source)
    }

    @Test
    fun aClearChoiceOrNone() {
        val one = r("Home", 1.0, 1.0, Source.PLACES, 0)
        assertEquals(one, SearchMerge.clear(listOf(one)))
        // The saved place of exactly that name among others.
        assertEquals(one, SearchMerge.clear(listOf(one, r("Homel", 2.0, 2.0, Source.ONLINE, 1))))
        assertNull(SearchMerge.clear(listOf(r("Cafe", 1.0, 1.0, Source.MAP), r("Cafe", 2.0, 2.0, Source.MAP))))
        assertNull(SearchMerge.clear(emptyList()))
    }
}
