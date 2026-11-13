package app.maparound.search

import app.maparound.map.MapPoi
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.shared.net.httpGetAsync
import app.maparound.shared.util.fixed
import app.maparound.shared.util.urlEncode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikipedia articles about places near a point (its "geosearch"), for "What's here?": in the
 * user's language, and in English too, with their pictures; and a place's own article in the
 * user's language, through its Wikidata item. Online only; the point goes to wikipedia.org, the
 * item to wikidata.org.
 */
object Wikipedia {
    /** An article about a place; [picture]: its main picture (freely licensed), when it has one. */
    data class Article(
        val lang: String,
        val title: String,
        val lat: Double,
        val lon: Double,
        val distanceM: Double,
        val picture: Commons.Photo? = null,
    ) {
        val url: String get() = articleUrl(lang, title)
    }

    /** A language code fit for a Wikipedia address ("en", "he", "zh-yue"), else English. */
    fun language(code: String?): String {
        val c = code?.lowercase()?.substringBefore('_')?.takeIf { c -> c.length in 2..12 && c.all { it in 'a'..'z' || it == '-' } } ?: return "en"
        return OLD_CODES[c] ?: c
    }

    /** Codes some systems still give (Java's for Hebrew, Indonesian, Yiddish) and Wikipedia's for Norwegian. */
    private val OLD_CODES = mapOf("iw" to "he", "in" to "id", "ji" to "yi", "nb" to "no")

    fun articleUrl(lang: String, title: String): String =
        "https://${language(lang)}.wikipedia.org/wiki/" + urlEncode(title.replace(' ', '_')).replace("+", "%20")

    /** Articles near a point, with where they are and their pictures (small). */
    fun nearbyUrl(lang: String, lat: Double, lon: Double, radiusM: Int = RADIUS_M, limit: Int = 25): String =
        "https://${language(lang)}.wikipedia.org/w/api.php?action=query&format=json&origin=*&generator=geosearch" +
            "&ggscoord=${fixed(lat, 5)}%7C${fixed(lon, 5)}&ggsradius=${radiusM.coerceIn(10, 10_000)}&ggslimit=$limit" +
            "&prop=coordinates%7Cpageimages&colimit=max&piprop=thumbnail%7Cname&pithumbsize=$LIST_PX&pilimit=max"

    /** The articles in a geosearch answer, nearest to [lat], [lon] first. */
    fun parseNearby(json: String, lang: String, lat: Double, lon: Double): List<Article> {
        val l = language(lang)
        val here = GeoPoint(lat, lon)
        return queryPages(json).mapNotNull { o ->
            val title = o.str("title") ?: return@mapNotNull null
            val c = o.first("coordinates") ?: return@mapNotNull null
            val la = c.dbl("lat") ?: return@mapNotNull null
            val lo = c.dbl("lon") ?: return@mapNotNull null
            Article(l, title, la, lo, Geo.distance(here, GeoPoint(la, lo)), picture(o, l))
        }.sortedBy { it.distanceM }
    }

    /** The main picture of a page in a `pageimages` answer. */
    private fun picture(page: JsonObject, lang: String): Commons.Photo? {
        val thumb = (page["thumbnail"] as? JsonObject)?.str("source") ?: return null
        val file = page.str("pageimage") ?: return null
        return Commons.Photo("File:$file", thumb, site = "$lang.wikipedia.org")
    }

    /**
     * Articles near the point: in [userLang], then the English ones about other places. Throws
     * when there's no answer at all (no connection).
     */
    suspend fun nearby(lat: Double, lon: Double, userLang: String): List<Article> {
        val lang = language(userLang)
        val own = parseNearby(httpGetAsync(nearbyUrl(lang, lat, lon)).decodeToString(), lang, lat, lon)
        if (lang == "en") return own
        val english = orNull { parseNearby(httpGetAsync(nearbyUrl("en", lat, lon)).decodeToString(), "en", lat, lon) }.orEmpty()
        // The same place in both: keep the user's language.
        val near = own.map { GeoPoint(it.lat, it.lon) }
        val others = english.filter { a -> near.none { Geo.distance(it, GeoPoint(a.lat, a.lon)) < 50 } }
        return (own + others).sortedBy { it.distanceM }
    }

    /** An article's main picture. */
    fun pictureUrl(lang: String, title: String, sizePx: Int = PICTURE_PX): String =
        "https://${language(lang)}.wikipedia.org/w/api.php?action=query&format=json&origin=*&redirects=1" +
            "&prop=pageimages&piprop=thumbnail%7Cname&pithumbsize=$sizePx&titles=" + urlEncode(title)

    fun parsePicture(json: String, lang: String): Commons.Photo? = queryPages(json).firstOrNull()?.let { picture(it, language(lang)) }

    /** [a] with its main picture, when it has one and Wikipedia answers. */
    suspend fun withPicture(a: Article): Article =
        if (a.picture != null) a else orNull { parsePicture(httpGetAsync(pictureUrl(a.lang, a.title)).decodeToString(), a.lang) }?.let { a.copy(picture = it) } ?: a

    fun sitelinksUrl(qid: String): String =
        "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json&origin=*&props=sitelinks&ids=" + urlEncode(qid)

    /** The Wikipedia articles of a Wikidata item (in an answer to [sitelinksUrl]): language to title. */
    fun parseSitelinks(json: String): Map<String, String> {
        val entities = runCatching { Json.parseToJsonElement(json).jsonObject["entities"]?.jsonObject }.getOrNull() ?: return emptyMap()
        val links = (entities.values.firstOrNull() as? JsonObject)?.get("sitelinks") as? JsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((site, v) in links) {
            // "dewiki", "zh_yuewiki"; not the other projects ("commonswiki", "dewikivoyage").
            if (!site.endsWith("wiki")) continue
            val lang = site.removeSuffix("wiki").replace('_', '-')
            if (lang.isEmpty() || lang in NOT_WIKIPEDIAS || !lang.all { it in 'a'..'z' || it == '-' }) continue
            val title = (v as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull ?: continue
            out[lang] = title
        }
        return out
    }

    /**
     * Which of a place's articles ([links]: language to title) to open: in [userLang], else the one
     * its `wikipedia` tag names ([own]), else English, else the one with its local [name], else any
     * (not one a bot wrote from a database, unless that's all there is).
     */
    fun pick(links: Map<String, String>, userLang: String, own: Pair<String, String>?, name: String?): Pair<String, String>? {
        val lang = language(userLang)
        links[lang]?.let { return lang to it }
        own?.let { return it }
        links["en"]?.let { return "en" to it }
        val written = links.entries.filter { it.key !in BOT_WIKIS }
        written.firstOrNull { name != null && it.value.substringBefore(" (").equals(name, ignoreCase = true) }?.let { return it.key to it.value }
        return (written.firstOrNull() ?: links.entries.firstOrNull())?.let { it.key to it.value }
    }

    /** Wikipedias made mostly of bot-written stubs (Cebuano, Waray, Egyptian Arabic, South Azerbaijani). */
    private val BOT_WIKIS = setOf("ceb", "war", "arz", "azb")

    /**
     * The article about [poi] from its own tags, in [userLang] when there is one (asks Wikidata when
     * the place has an item and its `wikipedia` tag isn't in that language). Its `wikipedia` tag's
     * article when Wikidata doesn't answer; null when it has no article.
     */
    suspend fun articleOf(poi: MapPoi, userLang: String): Article? {
        val lang = language(userLang)
        val own = poi.wikipedia
        val chosen = if (own != null && language(own.first) == lang) {
            own
        } else {
            val links = poi.wikidata?.let { q -> orNull { parseSitelinks(httpGetAsync(sitelinksUrl(q), timeoutMs = 10_000).decodeToString()) } }.orEmpty()
            pick(links, lang, own, poi.name)
        } ?: return null
        return Article(language(chosen.first), chosen.second, poi.lat, poi.lon, 0.0)
    }

    /**
     * What the Wikipedia button of [poi] opens (see [articleOf]): its article, else its Wikidata
     * page, which lists its articles. Null when its tags have neither.
     */
    suspend fun linkFor(poi: MapPoi, userLang: String): String? =
        articleOf(poi, userLang)?.url ?: poi.wikidata?.let { "https://www.wikidata.org/wiki/$it" }

    /**
     * The article about [poi] among [articles] (with its picture), or from its own `wikipedia` tag:
     * the one its tag names, else one with just its name (a "(…)" aside apart) close by. Only that:
     * a place named like a part of another isn't about it.
     */
    fun about(poi: MapPoi, articles: List<Article>): Article? {
        poi.wikipedia?.let { (lang, title) ->
            return articles.firstOrNull { it.lang == language(lang) && it.title == title } ?: Article(language(lang), title, poi.lat, poi.lon, 0.0)
        }
        if (poi.name == null) return null
        return articles.firstOrNull { a ->
            // Its name where it is, or in the article's language.
            val names = listOfNotNull(poi.name, poi.nameIn(a.lang)).map { it.trim().lowercase() }
            a.title.lowercase().substringBefore(" (").trim() in names && Geo.distance(poi.point, GeoPoint(a.lat, a.lon)) < 500
        }
    }

    const val RADIUS_M = 10_000

    /** How big the pictures in lists of articles are, in pixels. */
    private const val LIST_PX = 160

    /** How big a place's own picture is (across a phone's screen), in pixels. */
    private const val PICTURE_PX = 640

    /** Wikimedia sites with ids like a Wikipedia's that aren't one. */
    private val NOT_WIKIPEDIAS = setOf(
        "commons", "species", "meta", "wikidata", "mediawiki", "sources", "outreach", "wikimania", "incubator",
        "foundation", "wikifunctions", "test", "test2", "testwikidata", "beta", "nostalgia", "ten", "simple",
    )
}
