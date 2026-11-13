package app.maparound.map

import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

// Plain Kotlin: the points of interest the map shows (peaks, springs, huts, cafés…), as the
// platform's map reports them under a tap. Unit-tested.

/**
 * A point of interest on the map, from the offline maps' data: where it is and its OSM tags
 * (names in other languages as `name_de`…). [lang]: the user's language, for its name.
 */
data class MapPoi(val lat: Double, val lon: Double, val tags: Map<String, String>, val lang: String = "") {
    val point: GeoPoint get() = GeoPoint(lat, lon)

    /** Its name where it is. */
    val name: String? get() = tags["name"]?.trim()?.takeIf { it.isNotEmpty() }

    /** Its name in [language], if the map has it. */
    fun nameIn(language: String): String? = tags["name_$language"]?.trim()?.takeIf { it.isNotEmpty() }

    /** What it is, in words in the app's language ("Peak", "Alpine hut"), or null. */
    val kind: String? get() = PoiKinds.label(tags)

    val elevationM: Int? get() = tags["ele"]?.replace(',', '.')?.toDoubleOrNull()?.roundToInt()

    /** Its name in the user's language, or where it is, or what it is. */
    val title: String get() = nameIn(lang) ?: name ?: kind ?: T.poi.pointOfInterest

    /** "Peak · 2085 m" (and its local name, when [title] is another), without what [title] says. */
    val details: String?
        get() = listOfNotNull(
            name?.takeIf { it != title },
            kind?.takeIf { it != title },
            elevationM?.let { "$it ${T.common.unitM}" },
        ).joinToString(" · ").ifEmpty { null }

    /** Its Wikipedia article from its own `wikipedia` tag ("de:Drei Schwestern"): language and title. */
    val wikipedia: Pair<String, String>?
        get() {
            val w = tags["wikipedia"]?.trim() ?: return null
            val lang = w.substringBefore(':', "")
            if (lang.length !in 2..12 || !lang.all { it.isLetter() || it == '-' }) return null
            val title = w.substringAfter(':').trim()
            return if (title.isEmpty()) null else lang to title
        }

    /** Its Wikidata item from its `wikidata` tag ("Q666946"), which knows its articles in every language. */
    val wikidata: String?
        get() = tags["wikidata"]?.substringBefore(';')?.trim()?.takeIf { WIKIDATA_ID.matches(it) }

    /** Whether it has a Wikipedia article of its own (from its tags). */
    val hasArticle: Boolean get() = wikipedia != null || wikidata != null

    companion object {
        private val WIKIDATA_ID = Regex("Q[1-9][0-9]{0,11}")

        /**
         * The points the platform's map reported ([app.maparound.map.maplibre.NativeMap.poisNear]:
         * `[{"lat":…,"lon":…,"p":{tags}}]`), each place once (the map may draw one in several tiles
         * or layers), nearest to [lat], [lon] first.
         */
        fun parseList(json: String, lat: Double, lon: Double, lang: String = ""): List<MapPoi> {
            val items = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
            val seen = HashSet<String>()
            val out = ArrayList<MapPoi>()
            for (item in items) {
                val o = item as? JsonObject ?: continue
                val la = o["lat"]?.jsonPrimitive?.let { runCatching { it.double }.getOrNull() } ?: continue
                val lo = o["lon"]?.jsonPrimitive?.let { runCatching { it.double }.getOrNull() } ?: continue
                val tags = (o["p"] as? JsonObject)?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { k to it }
                }?.toMap().orEmpty()
                val poi = MapPoi(la, lo, tags, lang)
                if (poi.name == null && poi.kind == null) continue
                val key = "${poi.name}|${poi.kind}|${(la * 1e4).roundToInt()}|${(lo * 1e4).roundToInt()}"
                if (seen.add(key)) out += poi
            }
            val here = GeoPoint(lat, lon)
            return out.sortedBy { Geo.distance(here, it.point) }
        }
    }
}

/** What a point of interest is, from its OSM tags, in words. */
object PoiKinds {
    /** The tags that say what a thing is, the most telling first. */
    private val KEYS = listOf(
        "mountain_pass", "natural", "tourism", "historic", "amenity", "leisure", "man_made", "waterway",
        "place", "shop", "railway", "aerialway", "emergency", "sport", "office", "craft", "healthcare",
        "military", "power", "barrier", "highway", "landuse", "building",
    )

    // The words for kinds, in the app's language: [app.maparound.i18n.PoiTexts.kinds].

    /** Kinds that say little ("an attraction"): only when nothing more telling is there. */
    private val GENERIC = setOf("tourism=attraction", "historic=yes", "building=yes", "landuse=yes", "amenity=yes")

    fun label(tags: Map<String, String>): String? {
        var generic: String? = null
        for (k in KEYS) {
            val v = tags[k]?.trim() ?: continue
            if (v.isEmpty() || v == "no") continue
            val word = T.poi.kinds["$k=$v"] ?: humanize(if (v == "yes") k else v)
            if ("$k=$v" in GENERIC) {
                if (generic == null) generic = word
                continue
            }
            return word
        }
        return generic
    }

    private fun humanize(s: String) = s.replace('_', ' ').replace(';', ',').replaceFirstChar { it.uppercase() }
}
