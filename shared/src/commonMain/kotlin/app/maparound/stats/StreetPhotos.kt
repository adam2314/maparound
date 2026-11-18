package app.maparound.stats

import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Plain Kotlin: street-level photos from Panoramax (the open, federated catalogue: photos people
// took along streets and paths, some all the way round). Unit-tested.

object StreetPhotos {
    /** A photo's tap key: "data:p:<its id>" (the map's tap reads the id from the tiles). */
    const val TAP = "data:p:"

    /** Panoramax's catalogue of every instance (the default; Settings → Sources can give another). */
    const val PANORAMAX = "https://api.panoramax.xyz/api"

    /** The tiles' deepest zoom (MapLibre's); the photos themselves show from [PHOTOS_ZOOM]. */
    const val TILES_MAX_ZOOM = 15

    /** The single photos show from this MapLibre zoom (the lines they were taken along, before). */
    const val PHOTOS_ZOOM = 15

    /** The catalogue's map tiles (vector): the photos, their sequences, and a coverage grid zoomed out. */
    fun tiles(base: String): String = "${base.trimEnd('/')}/map/{z}/{x}/{y}.mvt"

    /** One photo's details. */
    fun pictureUrl(base: String, id: String): String = "${base.trimEnd('/')}/search?ids=$id"

    /** A photo: where and when it was taken, by whom, under which licence, its pictures. */
    data class Picture(
        val id: String,
        val lat: Double,
        val lon: Double,
        val timeMillis: Long?,
        val author: String?,
        val license: String?,
        /** About 2048 px wide; [thumb] about 500; [full]: as taken. */
        val picture: String?,
        val thumb: String?,
        val full: String?,
        /** Taken all the way round (360°): the picture is a panorama, unrolled. */
        val panorama: Boolean,
        /** Which way the camera looked (degrees from north). */
        val heading: Double?,
        /** The Panoramax server that keeps it ("panoramax.openstreetmap.fr"). */
        val instance: String?,
    )

    /** The photo in [pictureUrl]'s answer, or null. */
    fun parse(json: String): Picture? {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
        val f = (root["features"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val id = (f["id"] as? JsonPrimitive)?.content ?: return null
        val c = ((f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return null
        val lon = (c.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (c.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return null
        val p = f["properties"] as? JsonObject
        val assets = f["assets"] as? JsonObject
        fun asset(name: String) = ((assets?.get(name) as? JsonObject)?.get("href") as? JsonPrimitive)?.content
        fun prop(name: String) = (p?.get(name) as? JsonPrimitive)?.content
        val fov = ((p?.get("pers:interior_orientation") as? JsonObject)?.get("field_of_view") as? JsonPrimitive)?.intOrNull
        val producer = (f["providers"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { o -> (o["roles"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == "producer" } == true }
            ?.let { (it["name"] as? JsonPrimitive)?.content }
        val via = (f["links"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["rel"] as? JsonPrimitive)?.content == "via" }
            ?.let { (it["href"] as? JsonPrimitive)?.content }
        return Picture(
            id = id,
            lat = lat,
            lon = lon,
            timeMillis = prop("datetime")?.let(::isoMillis),
            author = producer ?: prop("geovisio:producer"),
            license = prop("license"),
            picture = asset("sd") ?: asset("thumb"),
            thumb = asset("thumb"),
            full = asset("hd"),
            panorama = fov == 360,
            heading = (p?.get("view:azimuth") as? JsonPrimitive)?.doubleOrNull,
            instance = via?.substringAfter("://")?.substringBefore('/'),
        )
    }

    /** The photo in Panoramax's own viewer (it turns all-round photos, and walks along their sequence). */
    fun viewerUrl(p: Picture): String = "https://panoramax.xyz/#focus=pic&pic=${p.id}&map=17/${fmt(p.lat)}/${fmt(p.lon)}"

    /**
     * Google Street View at that place, looking the same way: a plain Google Maps link (no key; its
     * pictures can't be shown inside the app, Google's terms don't allow them on other maps).
     */
    fun streetViewUrl(lat: Double, lon: Double, heading: Double? = null): String =
        "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=${fmt(lat)},${fmt(lon)}" +
            (heading?.let { "&heading=${it.roundToInt()}" } ?: "")

    private fun fmt(v: Double): String = ((v * 1e6).roundToLong() / 1e6).toString()
}

/**
 * An ISO 8601 date and time ("2025-12-14T11:50:59.0818+00:00", "…Z") in milliseconds since 1970,
 * or null when it isn't one.
 */
internal fun isoMillis(s: String): Long? {
    val m = ISO.matchEntire(s.trim()) ?: return null
    val (y, mo, d, h, mi, se) = m.destructured
    val frac = m.groupValues[7]
    val zone = m.groupValues[8]
    val local = runCatching {
        kotlinx.datetime.LocalDateTime(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), se.toInt())
    }.getOrNull() ?: return null
    var ms = local.toInstant(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds()
    if (frac.isNotEmpty()) ms += (frac.take(3).padEnd(3, '0')).toLong()
    if (zone.isNotEmpty() && zone != "Z") {
        val sign = if (zone[0] == '-') -1 else 1
        val hh = zone.substring(1, 3).toInt()
        val mm = zone.substring(4, 6).toInt()
        ms -= sign * (hh * 3_600_000L + mm * 60_000L)
    }
    return ms
}

private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})?""")
