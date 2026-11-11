package app.maparound.map.maplibre

import app.maparound.routing.GeoPoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Small GeoJSON builder for the map's overlays (coordinates are written lon, lat). */
internal object GeoJson {
    val empty: String = collection(emptyList())

    fun collection(features: List<JsonObject>): String = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }.toString()

    fun point(lat: Double, lon: Double, props: Map<String, Any>): JsonObject =
        feature(props, "Point", position(lat, lon))

    /** A line; null when it has fewer than two points. */
    fun line(points: List<GeoPoint>, props: Map<String, Any>): JsonObject? =
        if (points.size < 2) null
        else feature(props, "LineString", JsonArray(points.map { position(it.lat, it.lon) }))

    /** A closed ring as a polygon (for fills) or as a line (for outlines). */
    fun ring(ring: List<GeoPoint>, props: Map<String, Any>, polygon: Boolean): JsonObject {
        val coords = JsonArray((ring + ring.first()).map { position(it.lat, it.lon) })
        return if (polygon) feature(props, "Polygon", JsonArray(listOf(coords))) else feature(props, "LineString", coords)
    }

    /** Points on a circle of [radiusM] metres around a centre (on the sphere). */
    fun circle(lat: Double, lon: Double, radiusM: Double, segments: Int = 72): List<GeoPoint> {
        val d = radiusM / EARTH_RADIUS_M
        val la = lat * PI / 180
        val lo = lon * PI / 180
        return List(segments) { i ->
            val bearing = 2 * PI * i / segments
            val lat2 = asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(bearing))
            val lon2 = lo + atan2(sin(bearing) * sin(d) * cos(la), cos(d) - sin(la) * sin(lat2))
            GeoPoint(lat2 * 180 / PI, lon2 * 180 / PI)
        }
    }

    private fun position(lat: Double, lon: Double) = buildJsonArray {
        add(JsonPrimitive(lon))
        add(JsonPrimitive(lat))
    }

    private fun feature(props: Map<String, Any>, type: String, coordinates: JsonArray) = buildJsonObject {
        put("type", "Feature")
        put("properties", buildJsonObject {
            for ((k, v) in props) {
                when (v) {
                    is String -> put(k, v)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        })
        put("geometry", buildJsonObject {
            put("type", type)
            put("coordinates", coordinates)
        })
    }

    private const val EARTH_RADIUS_M = 6_371_008.8
}
