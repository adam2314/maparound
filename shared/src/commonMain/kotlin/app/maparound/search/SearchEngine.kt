package app.maparound.search

import app.maparound.i18n.T
import app.maparound.places.Place
import app.maparound.routing.GeoPoint
import app.maparound.share.Shared
import app.maparound.share.SharedLinks
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mapsforge.MfSelector
import app.maparound.shared.mapsforge.MfTag
import app.maparound.shared.openFile
import app.maparound.shared.util.fixed
import app.maparound.shared.util.toRadians
import app.maparound.shared.util.urlEncode
import app.maparound.tools.Coords
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos

// Plain Kotlin (Android and iOS). Fetching from the internet is the platform's part: online
// search takes a function that downloads a URL.

/** Finding places: your saved places, coordinates, the offline maps (around the map) and OpenStreetMap online. */
object SearchEngine {

    /** Saved places, coordinates in any format and map links: instant. */
    fun quick(query: String, near: GeoPoint?, places: List<Place>): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        Coords.parse(query, near)?.let {
            out += SearchResult(Coords.format(it.point, it.format) ?: query, it.format.label, it.point.lat, it.point.lon, SearchResult.Source.COORDINATES)
        } ?: (SharedLinks.parse(query) as? Shared.Point)?.let {
            out += SearchResult(it.label ?: T.poi.sharedLocation, T.poi.fromTheLink, it.lat, it.lon, SearchResult.Source.COORDINATES)
        }
        val q = SearchText.normalize(query)
        for (p in places) {
            val s = SearchText.bestScore(listOf(p.name, p.note), q) ?: continue
            out += SearchResult(p.name, p.category.label, p.lat, p.lon, SearchResult.Source.PLACES, s, placeId = p.id)
        }
        return out
    }

    /**
     * Names in the offline maps ([mapPaths], mapsforge files) within [radiusKm] of [center]:
     * towns, peaks, springs, streets, paths, shops… mapsforge keeps names, not a search index,
     * so the area is read. Names are shown in [lang] where the map has it. Blocking.
     */
    fun offline(
        query: String,
        center: GeoPoint,
        mapPaths: List<String>,
        lang: String,
        radiusKm: Double = 25.0,
        isActive: () -> Boolean = { true },
    ): List<SearchResult> {
        val q = SearchText.normalize(query)
        if (q.length < 2) return emptyList()
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(toRadians(center.lat)).coerceAtLeast(0.1))
        val south = (center.lat - dLat).coerceAtLeast(-85.0)
        val west = (center.lon - dLon).coerceAtLeast(-180.0)
        val north = (center.lat + dLat).coerceAtMost(85.0)
        val east = (center.lon + dLon).coerceAtMost(180.0)
        val out = ArrayList<SearchResult>()
        for (path in mapPaths) {
            if (!isActive()) break
            val handle = runCatching { openFile(path) }.getOrNull() ?: continue
            try {
                val map = MapsforgeFile(handle)
                val h = map.header
                if (h.minLat > north || h.maxLat < south || h.minLon > east || h.maxLon < west) continue
                // Points such as hotels, shops and springs only appear at the top zoom levels: read those.
                val data = map.readArea(
                    17, maxOf(south, h.minLat), maxOf(west, h.minLon), minOf(north, h.maxLat), minOf(east, h.maxLon), MfSelector.NAMED,
                )
                for (poi in data.pois) {
                    val raw = poi.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    val s = SearchText.bestScore(variants(raw), q) ?: continue
                    out += SearchResult(display(raw, lang), describe(poi.tags), poi.lat, poi.lon, SearchResult.Source.MAP, s)
                }
                for (way in data.ways) {
                    val raw = way.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    val s = SearchText.bestScore(variants(raw), q) ?: continue
                    val ring = way.rings.firstOrNull() ?: continue
                    val mid = (ring.size / 2 / 2) * 2
                    val lat = way.labelLat ?: ring.getOrNull(mid) ?: continue
                    val lon = way.labelLon ?: ring.getOrNull(mid + 1) ?: continue
                    out += SearchResult(display(raw, lang), describe(way.tags), lat, lon, SearchResult.Source.MAP, s)
                }
            } catch (e: Exception) {
                // A damaged or unreadable map: search the others.
            } finally {
                runCatching { handle.close() }
            }
        }
        return SearchText.rank(out, center, 40)
    }

    // ---- online ----------------------------------------------------------------------------------

    /**
     * OpenStreetMap search online: Photon (results near [near] first), Nominatim if that fails.
     * [fetch] downloads a URL as text (throwing on errors). Blocking.
     */
    fun online(query: String, near: GeoPoint?, fetch: (url: String) -> String): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return runCatching { parsePhoton(fetch(photonUrl(q, near))) }.getOrElse {
            parseNominatim(fetch(nominatimUrl(q, near)))
        }
    }

    fun photonUrl(q: String, near: GeoPoint?): String =
        "https://photon.komoot.io/api/?limit=15&q=" + urlEncode(q) +
            (near?.let { "&lat=${fixed(it.lat, 5)}&lon=${fixed(it.lon, 5)}" } ?: "")

    fun nominatimUrl(q: String, near: GeoPoint?): String =
        "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&q=" + urlEncode(q) +
            (near?.let { "&viewbox=${fixed(it.lon - 1, 4)},${fixed(it.lat + 1, 4)},${fixed(it.lon + 1, 4)},${fixed(it.lat - 1, 4)}" } ?: "")

    /** Photon's GeoJSON answer. */
    fun parsePhoton(json: String): List<SearchResult> {
        val features = (Json.parseToJsonElement(json).jsonObject["features"] as? JsonArray) ?: return emptyList()
        return features.mapIndexedNotNull { i, el ->
            val f = el as? JsonObject ?: return@mapIndexedNotNull null
            val c = ((f["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapIndexedNotNull null
            val p = f["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val street = listOfNotNull(p.str("street"), p.str("housenumber")).joinToString(" ").ifEmpty { null }
            val name = p.str("name") ?: street ?: p.str("city") ?: return@mapIndexedNotNull null
            val where = listOfNotNull(p.str("city")?.takeIf { it != name }, p.str("state"), p.str("country")).distinct().joinToString(", ")
            SearchResult(
                name, listOfNotNull(p.str("osm_value")?.let { kindOf(p.str("osm_key"), it) }, where.ifEmpty { null }).joinToString(" · "),
                c[1].jsonPrimitive.double, c[0].jsonPrimitive.double, SearchResult.Source.ONLINE, i,
            )
        }
    }

    /** Nominatim's jsonv2 answer. */
    fun parseNominatim(json: String): List<SearchResult> =
        Json.parseToJsonElement(json).jsonArray.mapIndexed { i, el ->
            val o = el.jsonObject
            val display = o.str("display_name") ?: ""
            SearchResult(
                o.str("name") ?: display.substringBefore(','),
                listOfNotNull(o.str("type")?.let { kindOf(o.str("category"), it) }, display.substringAfter(", ", "").ifEmpty { null }).joinToString(" · "),
                o.getValue("lat").jsonPrimitive.content.toDouble(), o.getValue("lon").jsonPrimitive.content.toDouble(),
                SearchResult.Source.ONLINE, i,
            )
        }

    /** A non-blank string (or number) field, like org.json's optString. */
    private fun JsonObject.str(key: String): String? {
        val v: JsonElement = this[key] ?: return null
        if (v is JsonNull || v !is JsonPrimitive) return null
        return v.content.takeIf { it.isNotBlank() }
    }

    // ---- names and kinds -------------------------------------------------------------------------

    /** mapsforge stores names in several languages as "local\ren\bEnglish\r…". */
    fun variants(raw: String): List<String> = raw.split('\r').map { it.substringAfter('\b') }.filter { it.isNotBlank() }

    /** The name in [lang] if the map has it (as mapsforge's MapDataStore.extract), else the local one. */
    fun display(raw: String, lang: String): String {
        val names = raw.split('\r')
        var picked: String? = names[0]
        if (lang.isNotBlank()) {
            var fallback: String? = null
            for (entry in names.drop(1)) {
                val parts = entry.split('\b')
                if (parts.size != 2) continue
                if (parts[0].equals(lang, ignoreCase = true)) {
                    picked = parts[1]
                    fallback = null
                    break
                }
                if (fallback == null && !parts[0].contains('-') && (lang.contains('-') || lang.contains('_')) &&
                    lang.lowercase().startsWith(parts[0].lowercase())
                ) fallback = parts[1]
            }
            if (fallback != null) picked = fallback
        }
        return picked?.takeIf { it.isNotBlank() } ?: variants(raw).firstOrNull() ?: raw
    }

    /** "Peak · 812 m", "Spring", "Village", "Path"… from the most telling tag, in the app's language. */
    fun describe(tags: List<MfTag>): String {
        val t = tags.associate { it.key to it.value }
        val words = T.poi
        val kind = when {
            t["place"] != null -> kindOf("place", t.getValue("place"))
            t["natural"] != null -> kindOf("natural", t.getValue("natural"))
            t["tourism"] != null -> kindOf("tourism", t.getValue("tourism"))
            t["historic"] != null -> kindOf("historic", t.getValue("historic"))
            t["amenity"] != null -> kindOf("amenity", t.getValue("amenity"))
            t["shop"] != null -> words.shop(kindOf("shop", t.getValue("shop")))
            t["leisure"] != null -> kindOf("leisure", t.getValue("leisure"))
            t["waterway"] != null -> kindOf("waterway", t.getValue("waterway"))
            t["highway"] in setOf("path", "footway", "track", "bridleway", "steps") -> words.path
            t["highway"] != null -> words.street
            t["route"] != null -> words.trail
            t["landuse"] != null -> kindOf("landuse", t.getValue("landuse"))
            t["building"] != null -> words.building
            else -> words.place
        }
        return listOfNotNull(kind, t["ele"]?.toDoubleOrNull()?.let { "${it.toInt()} ${T.common.unitM}" }).joinToString(" · ")
    }

    /** What an OSM tag ([key]=[value]) says a place is, in the app's language ("Peak"; else the value in words). */
    private fun kindOf(key: String?, value: String): String = T.poi.searchKind("$key=$value", humanize(value))

    fun humanize(v: String) = v.replace('_', ' ').replaceFirstChar { it.titlecase() }
}
