package app.maparound.stats

import app.maparound.tools.MapGrid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

// Plain Kotlin: cameras as OpenStreetMap's volunteers have mapped them (speed cameras, security
// cameras, webcams), read for the area on screen through an Overpass server. Unit-tested.

object Cameras {
    /** A camera's tap key: "data:k:node/123". */
    const val TAP = "data:k:"

    /** What a camera is (the legend's classes, in this order). */
    enum class Kind { SPEED, SECURITY, WEBCAM }

    /**
     * A camera: its OpenStreetMap object ("node/123"), what it is, where, and what its tags say:
     * its name, operator, the speed limit it enforces, which way it looks (degrees from north), its
     * webcam's page, what it watches ("public", "outdoor", "indoor") and its type ("camera", "ALPR").
     */
    data class Camera(
        val id: String,
        val kind: Kind,
        val lat: Double,
        val lon: Double,
        val name: String? = null,
        val operator: String? = null,
        val maxspeed: String? = null,
        val direction: Double? = null,
        val webcam: String? = null,
        val watches: String? = null,
        val type: String? = null,
    )

    /** The legend's colours: speed cameras red, security cameras purple, webcams blue. */
    private val COLORS = listOf("#D32F2F", "#6A1B9A", "#1565C0")

    val classes: List<StatClass>
        get() = Kind.entries.map { StatClass(app.maparound.i18n.T.live.cameraKinds[it.ordinal], COLORS[it.ordinal]) }

    /** The area to ask for around [view]: a margin each way, out to whole twentieths of a degree. */
    fun around(view: MapGrid.View, margin: Double = 0.2): MapGrid.View {
        val dLat = (view.north - view.south) * margin
        val dLon = (view.east - view.west) * margin
        // (A hair's tolerance: 32.15 × 20 is just over 643 in floating point.)
        fun down(v: Double) = floor(v * 20 + 1e-9) / 20
        fun up(v: Double) = ceil(v * 20 - 1e-9) / 20
        return MapGrid.View(
            down(view.south - dLat).coerceAtLeast(-90.0), down(view.west - dLon).coerceAtLeast(-180.0),
            up(view.north + dLat).coerceAtMost(90.0), up(view.east + dLon).coerceAtMost(180.0),
        )
    }

    /** Whether [outer] has all of [inner]. */
    fun covers(outer: MapGrid.View, inner: MapGrid.View): Boolean =
        inner.south >= outer.south && inner.north <= outer.north && inner.west >= outer.west && inner.east <= outer.east

    fun inside(c: Camera, v: MapGrid.View): Boolean = c.lat in v.south..v.north && c.lon in v.west..v.east

    /** The Overpass query for the cameras in [area] (ways and areas by their centre). */
    fun query(area: MapGrid.View): String {
        val b = "${fmt(area.south)},${fmt(area.west)},${fmt(area.north)},${fmt(area.east)}"
        return "[out:json][timeout:25];(" +
            "node[\"highway\"=\"speed_camera\"]($b);" +
            "node[\"man_made\"=\"surveillance\"]($b);" +
            "nwr[\"contact:webcam\"]($b);" +
            "nwr[\"webcam\"]($b);" +
            ");out center tags 5000;"
    }

    /** [query] sent to the Overpass server at [base]. */
    fun url(base: String, area: MapGrid.View): String = "$base?data=" + app.maparound.shared.util.urlEncode(query(area))

    /** The cameras in an Overpass answer; an error (its "remark") throws, so another server can be tried. */
    fun parse(json: String): List<Camera> {
        val root = Json.parseToJsonElement(json) as? JsonObject ?: error("not an Overpass answer")
        val remark = (root["remark"] as? JsonPrimitive)?.content
        val elements = root["elements"] as? JsonArray ?: error("no elements")
        if (remark != null && elements.isEmpty() && ("error" in remark || "timed out" in remark)) error(remark)
        return elements.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val type = (o["type"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val at = (o["center"] as? JsonObject) ?: o
            val lat = (at["lat"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lon = (at["lon"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val tags = (o["tags"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
            val webcam = (tags["contact:webcam"] ?: tags["webcam"])?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val kind = when {
                webcam != null || tags["surveillance:type"] == "webcam" -> Kind.WEBCAM
                tags["highway"] == "speed_camera" || tags["enforcement"] == "maxspeed" -> Kind.SPEED
                tags["man_made"] == "surveillance" -> Kind.SECURITY
                else -> return@mapNotNull null
            }
            Camera(
                id = "$type/$id",
                kind = kind,
                lat = lat,
                lon = lon,
                name = tags["name"]?.takeIf { it.isNotBlank() },
                operator = tags["operator"]?.takeIf { it.isNotBlank() },
                maxspeed = tags["maxspeed"]?.takeIf { it.isNotBlank() },
                direction = direction(tags["camera:direction"] ?: tags["direction"]),
                webcam = webcam,
                watches = tags["surveillance"]?.takeIf { it.isNotBlank() },
                type = tags["surveillance:type"]?.takeIf { it.isNotBlank() },
            )
        }.distinctBy { it.id }
    }

    /** A direction tag in degrees ("135") or as a compass point ("SE"), or null. */
    fun direction(tag: String?): Double? {
        val t = tag?.trim()?.uppercase() ?: return null
        t.toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
        val i = POINTS.indexOf(t)
        return if (i >= 0) i * 22.5 else null
    }

    private val POINTS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")

    /** The cameras as GeoJSON, without the [hidden] kinds; a speed camera labelled with its limit. */
    fun geoJson(cameras: List<Camera>, hidden: Set<Int>): String {
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (c in cameras) {
            if (c.kind.ordinal in hidden) continue
            if (!first) sb.append(',')
            first = false
            val label = if (c.kind == Kind.SPEED) c.maxspeed?.substringBefore(' ').orEmpty() else ""
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                .append(c.lon).append(',').append(c.lat)
                .append("""]},"properties":{"tap":"$TAP${c.id}","color":"${COLORS[c.kind.ordinal]}","label":""")
                .append(JsonPrimitive(label).toString()).append("}}")
        }
        return sb.append("]}").toString()
    }

    /** The camera's page on OpenStreetMap (who mapped it, its tags, its history). */
    fun osmUrl(c: Camera): String = "https://www.openstreetmap.org/${c.id}"

    private fun fmt(v: Double): String = ((v * 1e4).roundToLong() / 1e4).toString()
}
