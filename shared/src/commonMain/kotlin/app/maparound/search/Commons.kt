package app.maparound.search

import app.maparound.shared.net.httpGetAsync
import app.maparound.shared.util.fixed
import app.maparound.shared.util.urlEncode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Photos on Wikimedia Commons (freely licensed): those taken near a point (its "geosearch" of
 * files with coordinates), and a photo's author, licence and description for its credit. Online
 * only; the point goes to commons.wikimedia.org.
 */
object Commons {
    /**
     * A photo: its file ("File:Schloss Vaduz.jpg") on [site] (Commons, or the Wikipedia whose
     * article shows it), a small picture of it, and where it was taken when known.
     */
    data class Photo(val file: String, val thumb: String, val lat: Double? = null, val lon: Double? = null, val site: String = SITE) {
        /** Its name without "File:" and the file ending ("Schloss Vaduz"). */
        val name: String get() = file.substringAfter(':').substringBeforeLast('.').replace('_', ' ')
    }

    /** What a photo's page says: a bigger picture of it, the page, its author, licence and description. */
    data class Details(val image: String, val pageUrl: String, val author: String?, val license: String?, val description: String?)

    const val SITE = "commons.wikimedia.org"

    /** How wide the small pictures are, in pixels. */
    const val THUMB_PX = 320

    fun nearbyUrl(lat: Double, lon: Double, radiusM: Int = 10_000, limit: Int = 30): String =
        "https://$SITE/w/api.php?action=query&format=json&origin=*&generator=geosearch&ggsnamespace=6" +
            "&ggscoord=${fixed(lat, 5)}%7C${fixed(lon, 5)}&ggsradius=${radiusM.coerceIn(10, 10_000)}&ggslimit=$limit" +
            "&prop=imageinfo%7Ccoordinates&colimit=max&iiprop=url%7Cmime&iiurlwidth=$THUMB_PX"

    /** The photos in a geosearch answer, nearest first (not drawings, maps as SVG, sounds or videos). */
    fun parseNearby(json: String): List<Photo> =
        queryPages(json).mapNotNull { o ->
            val title = o.str("title") ?: return@mapNotNull null
            val info = o.first("imageinfo") ?: return@mapNotNull null
            if (info.str("mime") !in PHOTOS) return@mapNotNull null
            val thumb = info.str("thumburl") ?: return@mapNotNull null
            val c = o.first("coordinates")
            (o.int("index") ?: Int.MAX_VALUE) to Photo(title, thumb, c?.dbl("lat"), c?.dbl("lon"))
        }.sortedBy { it.first }.map { it.second }

    /** Photos taken within [radiusM] of the point, nearest first. Throws when offline. */
    suspend fun nearby(lat: Double, lon: Double, radiusM: Int = 10_000): List<Photo> =
        parseNearby(httpGetAsync(nearbyUrl(lat, lon, radiusM)).decodeToString())

    fun detailsUrl(file: String, site: String = SITE, widthPx: Int = 1280): String =
        "https://$site/w/api.php?action=query&format=json&origin=*&prop=imageinfo&iiprop=url%7Cextmetadata" +
            "&iiurlwidth=$widthPx&iiextmetadatafilter=Artist%7CLicenseShortName%7CImageDescription&titles=" + urlEncode(file)

    fun parseDetails(json: String): Details? {
        val info = queryPages(json).firstOrNull()?.first("imageinfo") ?: return null
        val image = info.str("thumburl") ?: info.str("url") ?: return null
        val meta = info["extmetadata"] as? JsonObject
        fun field(k: String) = (meta?.get(k) as? JsonObject)?.str("value")?.let(::plainText)?.takeIf { it.isNotEmpty() }
        return Details(
            image = image,
            pageUrl = info.str("descriptionurl") ?: return null,
            author = field("Artist")?.take(120),
            license = field("LicenseShortName"),
            description = field("ImageDescription")?.let { if (it.length > 300) it.take(299) + "…" else it },
        )
    }

    /** A bigger picture of [photo] and its credit, or null when that isn't there. Throws when offline. */
    suspend fun details(photo: Photo): Details? = parseDetails(httpGetAsync(detailsUrl(photo.file, photo.site)).decodeToString())

    /** Text from the HTML Commons gives ("<a href=…>Gary Todd</a> from Xinzheng"). */
    fun plainText(html: String): String {
        val s = html.replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("&#(\\d{1,6});")) { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 32..0xFFFF }?.toChar()?.toString() ?: "" }
            .replace("&amp;", "&")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val PHOTOS = setOf("image/jpeg", "image/png", "image/webp")
}

/** The pages of a MediaWiki query answer (`query.pages`, an object keyed by page id). */
internal fun queryPages(json: String): List<JsonObject> {
    val pages = runCatching { Json.parseToJsonElement(json).jsonObject["query"]?.jsonObject?.get("pages") }.getOrNull()
    return when (pages) {
        is JsonObject -> pages.values.mapNotNull { it as? JsonObject }
        is JsonArray -> pages.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

internal fun JsonObject.dbl(key: String): Double? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull

/** The first object of the array under [key] ("imageinfo", "coordinates"). */
internal fun JsonObject.first(key: String): JsonObject? = (this[key] as? JsonArray)?.firstOrNull() as? JsonObject

/** [block]'s answer, or null when it fails (offline, a bad answer); cancelling still cancels. */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
