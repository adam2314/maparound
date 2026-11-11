package app.maparound.search

import app.maparound.routing.GeoPoint
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import kotlin.test.Test

class SearchTest {
    @Test
    fun normalizesAccentsHebrewPointsAndPunctuation() {
        assertEquals("zurich", SearchText.normalize("  Zürich "))
        assertEquals("ירושלים", SearchText.normalize("יְרוּשָׁלַיִם"))
        assertEquals("צה ל", SearchText.normalize("צה\"ל"))
        assertEquals("ein gedi", SearchText.normalize("Ein-Gedi"))
    }

    @Test
    fun scoresExactPrefixWordAndContains() {
        val q = SearchText.normalize("gedi")
        assertEquals(0, SearchText.score("Gedi", q))
        assertEquals(1, SearchText.score("Gedi Spring", q))
        assertEquals(2, SearchText.score("Ein Gedi", q))
        assertEquals(3, SearchText.score("Engedi", q))
        assertNull(SearchText.score("Masada", q))
        assertEquals(4, SearchText.score("Nahal David waterfall", SearchText.normalize("david fall")))
        assertEquals(1, SearchText.bestScore(listOf("עין גדי", "Ein Gedi"), SearchText.normalize("ein")))
    }

    @Test
    fun ranksByMatchThenDistanceAndDropsDuplicates() {
        val here = GeoPoint(31.46, 35.39)
        fun r(name: String, lat: Double, score: Int) = SearchResult(name, "", lat, 35.39, SearchResult.Source.MAP, score)
        val list = listOf(
            r("Ein Gedi far", 32.0, 2),
            r("Ein Gedi near", 31.47, 2),
            r("Gedi", 33.0, 0),
            r("Ein Gedi near", 31.4705, 2), // the same thing from a neighbouring tile
        )
        val ranked = SearchText.rank(list, here)
        assertEquals(listOf("Gedi", "Ein Gedi near", "Ein Gedi far"), ranked.map { it.name })
    }
}
