package app.maparound.search

import android.util.Log
import app.maparound.AppGraph
import app.maparound.USER_AGENT
import app.maparound.data.MapNames
import app.maparound.routing.GeoPoint
import app.maparound.share.Shared
import app.maparound.share.SharedLinks
import app.maparound.tools.Coords
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.reader.MapFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos

/** Finding places: your saved places, coordinates, the offline maps (around the map) and OpenStreetMap online. */
object SearchEngine {
    private const val TAG = "SearchEngine"

    /** Saved places, coordinates in any format and map links: instant. */
    fun quick(query: String, near: GeoPoint?): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        Coords.parse(query, near)?.let {
            out += SearchResult(Coords.format(it.point, it.format) ?: query, it.format.label, it.point.lat, it.point.lon, SearchResult.Source.COORDINATES)
        } ?: (SharedLinks.parse(query) as? Shared.Point)?.let {
            out += SearchResult(it.label ?: "Shared location", "From the link", it.lat, it.lon, SearchResult.Source.COORDINATES)
        }
        val q = SearchText.normalize(query)
        for (p in AppGraph.places.places.value) {
            val s = SearchText.bestScore(listOf(p.name, p.note), q) ?: continue
            out += SearchResult(p.name, p.category.label, p.lat, p.lon, SearchResult.Source.PLACES, s, placeId = p.id)
        }
        return out
    }

    /**
     * Names in the offline maps within [radiusKm] of [center]: towns, peaks, springs, streets,
     * paths, shops… (mapsforge keeps names, not a search index, so we read the area). Blocking.
     */
    fun offline(
        query: String,
        center: GeoPoint,
        radiusKm: Double = 25.0,
        files: List<java.io.File> = AppGraph.mapFiles.files.value.map { it.file }.filterNot { MapNames.isWorld(it.name) },
        isActive: () -> Boolean = { true },
    ): List<SearchResult> {
        val q = SearchText.normalize(query)
        if (q.length < 2) return emptyList()
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(center.lat)).coerceAtLeast(0.1))
        val box = BoundingBox(
            (center.lat - dLat).coerceAtLeast(-85.0), (center.lon - dLon).coerceAtLeast(-180.0),
            (center.lat + dLat).coerceAtMost(85.0), (center.lon + dLon).coerceAtMost(180.0),
        )
        // Points such as hotels, shops and springs only appear at the top zoom levels: read those.
        val z: Byte = 17
        val lang = Locale.getDefault().language
        val out = ArrayList<SearchResult>()
        for (f in files) {
            if (!isActive()) break
            val map = runCatching { MapFile(f) }.getOrNull() ?: continue
            try {
                val mb = map.boundingBox()
                if (!mb.intersects(box)) continue
                val area = BoundingBox(
                    maxOf(box.minLatitude, mb.minLatitude), maxOf(box.minLongitude, mb.minLongitude),
                    minOf(box.maxLatitude, mb.maxLatitude), minOf(box.maxLongitude, mb.maxLongitude),
                )
                val ul = Tile(MercatorProjection.longitudeToTileX(area.minLongitude, z), MercatorProjection.latitudeToTileY(area.maxLatitude, z), z, 256)
                val lr = Tile(MercatorProjection.longitudeToTileX(area.maxLongitude, z), MercatorProjection.latitudeToTileY(area.minLatitude, z), z, 256)
                val data = map.readNamedItems(ul, lr) ?: continue
                for (poi in data.pois) {
                    val raw = poi.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    val s = SearchText.bestScore(variants(raw), q) ?: continue
                    out += SearchResult(display(raw, lang), describe(poi.tags), poi.position.latitude, poi.position.longitude, SearchResult.Source.MAP, s)
                }
                for (way in data.ways) {
                    val raw = way.tags.firstOrNull { it.key == "name" }?.value ?: continue
                    val s = SearchText.bestScore(variants(raw), q) ?: continue
                    val at = way.labelPosition ?: way.latLongs.firstOrNull()?.let { ring -> ring[ring.size / 2] } ?: continue
                    out += SearchResult(display(raw, lang), describe(way.tags), at.latitude, at.longitude, SearchResult.Source.MAP, s)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Searching ${f.name} failed", e)
            } finally {
                runCatching { map.close() }
            }
        }
        return SearchText.rank(out, center, 40)
    }

    /** OpenStreetMap search online: Photon (results near [near] first), Nominatim if that fails. Blocking. */
    fun online(query: String, near: GeoPoint?): List<SearchResult> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return runCatching { photon(q, near) }.getOrElse { e ->
            Log.w(TAG, "Photon failed, trying Nominatim", e)
            nominatim(q, near)
        }
    }

    private fun photon(q: String, near: GeoPoint?): List<SearchResult> {
        val url = "https://photon.komoot.io/api/?limit=15&q=" + URLEncoder.encode(q, "UTF-8") +
            (near?.let { String.format(Locale.ROOT, "&lat=%.5f&lon=%.5f", it.lat, it.lon) } ?: "")
        val json = JSONObject(get(url))
        val features = json.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = listOfNotNull(p.str("street"), p.str("housenumber")).joinToString(" ").ifEmpty { null }
            val name = p.str("name") ?: street ?: p.str("city") ?: return@mapNotNull null
            val where = listOfNotNull(p.str("city")?.takeIf { it != name }, p.str("state"), p.str("country")).distinct().joinToString(", ")
            SearchResult(
                name, listOfNotNull(p.str("osm_value")?.let(::humanize), where.ifEmpty { null }).joinToString(" · "),
                c.getDouble(1), c.getDouble(0), SearchResult.Source.ONLINE, i,
            )
        }
    }

    private fun nominatim(q: String, near: GeoPoint?): List<SearchResult> {
        val box = near?.let { String.format(Locale.ROOT, "&viewbox=%.4f,%.4f,%.4f,%.4f", it.lon - 1, it.lat + 1, it.lon + 1, it.lat - 1) } ?: ""
        val arr = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&q=" + URLEncoder.encode(q, "UTF-8") + box))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            SearchResult(
                o.str("name") ?: display.substringBefore(','),
                listOfNotNull(o.str("type")?.let(::humanize), display.substringAfter(", ", "").ifEmpty { null }).joinToString(" · "),
                o.getString("lat").toDouble(), o.getString("lon").toDouble(), SearchResult.Source.ONLINE, i,
            )
        }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.str(key: String): String? = optString(key, "").takeIf { it.isNotBlank() }

    /** mapsforge stores names in several languages as "local\ren\bEnglish\r…". */
    private fun variants(raw: String): List<String> = raw.split('\r').map { it.substringAfter('\b') }.filter { it.isNotBlank() }

    private fun display(raw: String, lang: String): String =
        runCatching { MapDataStore.extract(raw, lang) }.getOrNull()?.takeIf { it.isNotBlank() } ?: variants(raw).firstOrNull() ?: raw

    /** "Peak · 812 m", "Spring", "Village", "Path"… from the most telling tag. */
    private fun describe(tags: List<Tag>): String {
        val t = tags.associate { it.key to it.value }
        val kind = when {
            t["place"] != null -> humanize(t["place"]!!)
            t["natural"] != null -> humanize(t["natural"]!!)
            t["tourism"] != null -> humanize(t["tourism"]!!)
            t["historic"] != null -> humanize(t["historic"]!!)
            t["amenity"] != null -> humanize(t["amenity"]!!)
            t["shop"] != null -> "Shop: " + humanize(t["shop"]!!)
            t["leisure"] != null -> humanize(t["leisure"]!!)
            t["waterway"] != null -> humanize(t["waterway"]!!)
            t["highway"] in setOf("path", "footway", "track", "bridleway", "steps") -> "Path"
            t["highway"] != null -> "Street"
            t["route"] != null -> "Trail"
            t["landuse"] != null -> humanize(t["landuse"]!!)
            t["building"] != null -> "Building"
            else -> "Place"
        }
        return listOfNotNull(kind, t["ele"]?.toDoubleOrNull()?.let { "${it.toInt()} m" }).joinToString(" · ")
    }

    private fun humanize(v: String) = v.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
