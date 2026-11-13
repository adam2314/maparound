package app.maparound.search

import app.maparound.map.MapPoi
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.shared.net.httpGetText
import app.maparound.shared.util.fixed
import app.maparound.shared.util.urlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia articles about places near a point (its "geosearch"), for "What's here?": in the
 * user's language, and in English too. Online only; the point goes to wikipedia.org.
 */
object Wikipedia {
    data class Article(val lang: String, val title: String, val lat: Double, val lon: Double, val distanceM: Double) {
        val url: String get() = articleUrl(lang, title)
    }

    /** A language code fit for a Wikipedia address ("en", "he", "zh-yue"), else English. */
    fun language(code: String?): String =
        code?.lowercase()?.substringBefore('_')?.takeIf { c -> c.length in 2..12 && c.all { it in 'a'..'z' || it == '-' } } ?: "en"

    fun articleUrl(lang: String, title: String): String =
        "https://${language(lang)}.wikipedia.org/wiki/" + urlEncode(title.replace(' ', '_')).replace("+", "%20")

    fun nearbyUrl(lang: String, lat: Double, lon: Double, radiusM: Int = RADIUS_M, limit: Int = 25): String =
        "https://${language(lang)}.wikipedia.org/w/api.php?action=query&list=geosearch&format=json&origin=*" +
            "&gscoord=${fixed(lat, 5)}%7C${fixed(lon, 5)}&gsradius=${radiusM.coerceIn(10, 10_000)}&gslimit=$limit"

    /** The articles in a geosearch answer, nearest first. */
    fun parseNearby(json: String, lang: String): List<Article> {
        val list = runCatching {
            Json.parseToJsonElement(json).jsonObject["query"]?.jsonObject?.get("geosearch")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Article(language(lang), title, lat, lon, o["dist"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
        }.sortedBy { it.distanceM }
    }

    /**
     * Articles near the point (blocking: network): in [userLang], then the English ones about
     * other places. Throws when there's no answer at all (no connection).
     */
    fun nearby(lat: Double, lon: Double, userLang: String): List<Article> {
        val lang = language(userLang)
        val own = parseNearby(httpGetText(nearbyUrl(lang, lat, lon), timeoutMs = 15_000), lang)
        if (lang == "en") return own
        val english = runCatching { parseNearby(httpGetText(nearbyUrl("en", lat, lon), timeoutMs = 15_000), "en") }.getOrDefault(emptyList())
        // The same place in both: keep the user's language.
        val near = own.map { GeoPoint(it.lat, it.lon) }
        val others = english.filter { a -> near.none { Geo.distance(it, GeoPoint(a.lat, a.lon)) < 50 } }
        return (own + others).sortedBy { it.distanceM }
    }

    /**
     * The article about [poi]: its own `wikipedia` tag, else one with just its name (a "(…)"
     * aside apart) close by. Only that: a place named like a part of another isn't about it.
     */
    fun about(poi: MapPoi, articles: List<Article>): Article? {
        poi.wikipedia?.let { (lang, title) -> return Article(lang, title, poi.lat, poi.lon, 0.0) }
        if (poi.name == null) return null
        return articles.firstOrNull { a ->
            // Its name where it is, or in the article's language.
            val names = listOfNotNull(poi.name, poi.nameIn(a.lang)).map { it.trim().lowercase() }
            a.title.lowercase().substringBefore(" (").trim() in names && Geo.distance(poi.point, GeoPoint(a.lat, a.lon)) < 500
        }
    }

    const val RADIUS_M = 10_000
}
