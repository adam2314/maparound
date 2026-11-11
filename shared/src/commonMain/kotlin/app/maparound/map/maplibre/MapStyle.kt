package app.maparound.map.maplibre

import app.maparound.data.Prefs
import app.maparound.map.BaseMap
import app.maparound.map.OnlineSource
import app.maparound.map.satelliteSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The MapLibre style for a [BaseMap]: the base (offline vector map from the in-app tile server,
 * or online raster tiles), then the overlays (routes, tracks, tools, places, party, own
 * position, pin) as GeoJSON sources that [MapLibreSurface] fills.
 *
 * [vectorLayers] draw the offline maps' vector tiles (source [OSM_SOURCE], layers poi, line,
 * area and sea); [glyphs] is the font URL template for their labels.
 */
class MapStyle(private val vectorLayers: List<JsonObject>, private val glyphs: String) {

    /** The whole style document for [base] (null: an empty map with the overlays only). */
    fun build(base: BaseMap?, tilesBaseUrl: String, osmVersion: String): String = buildJsonObject {
        put("version", 8)
        put("name", "MapAround")
        put("glyphs", glyphs)
        val sources = LinkedHashMap<String, JsonElement>()
        val layers = ArrayList<JsonElement>()
        layers += layer("canvas", "background") { put("paint", obj("background-color" to OUTSIDE)) }
        when (base) {
            null -> Unit
            is BaseMap.Online -> {
                sources["online"] = rasterSource(base.source)
                layers += rasterLayer("online", "online")
            }
            is BaseMap.Offline -> {
                sources[OSM_SOURCE] = buildJsonObject {
                    put("type", "vector")
                    putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/osm/$osmVersion/{z}/{x}/{y}.pbf")) }
                    put("minzoom", 0)
                    put("maxzoom", OSM_MAX_ZOOM)
                    put("attribution", "© OpenStreetMap contributors")
                }
                val satellite = base.background == Prefs.BACKGROUND_SATELLITE
                if (satellite) {
                    sources["satellite"] = rasterSource(satelliteSource)
                    layers += rasterLayer("satellite", "satellite")
                }
                // Over imagery only lines, symbols and labels stay ("hybrid"), or nothing at all.
                layers += when {
                    !satellite -> vectorLayers
                    base.vectorOnTop -> vectorLayers.filter { !it.isLandCover() }
                    else -> emptyList()
                }
            }
        }
        for (id in OVERLAY_SOURCES) {
            sources[id] = buildJsonObject {
                put("type", "geojson")
                put("data", JsonObject(mapOf("type" to JsonPrimitive("FeatureCollection"), "features" to JsonArray(emptyList()))))
            }
        }
        layers += overlayLayers()
        put("sources", JsonObject(sources))
        // Theme layers for tiles this map doesn't have (contours, trails) stay out.
        put("layers", JsonArray(layers.filter { l -> (l as JsonObject).source().let { it == null || it in sources } }))
    }.toString()

    private fun overlayLayers(): List<JsonObject> = listOf(
        line("tracks-casing", TRACKS, "#FFFFFF", 7.0),
        line("tracks-line", TRACKS, "#E65100", 4.0),
        line("recording", RECORDING, "#D50000", 4.0),
        line("route-casing", ROUTE, "#FFFFFF", 8.0, role = "line"),
        line("route-line", ROUTE, "#6A1B9A", 5.0, role = "line"),
        line("tools-ring", TOOLS, "rgba(33,33,33,0.78)", 2.0, role = "ring"),
        line("tools-casing", TOOLS, "#FFFFFF", 7.0, role = "line"),
        layer("tools-line", "line") {
            put("source", TOOLS)
            put("filter", eq("role", "line"))
            put("layout", ROUND)
            put("paint", obj(
                "line-color" to arr("match", arr("get", "vis"), "yes", "#2E7D32", "no", "#C62828", "#1565C0"),
                "line-width" to 4.0,
            ))
        },
        layer("me-accuracy", "fill") {
            put("source", ME)
            put("filter", eq("role", "accuracy"))
            put("paint", obj("fill-color" to "rgba(30,136,229,0.19)", "fill-outline-color" to "rgba(30,136,229,0.55)"))
        },
        symbols("${TAP_PREFIX}places", PLACES),
        symbols("${TAP_PREFIX}party", PARTY),
        symbols("tools-points", TOOLS, role = "letter"),
        symbols("${TAP_PREFIX}waypoints", ROUTE, role = "wp"),
        symbols("${TAP_PREFIX}me", ME, role = "dot"),
        symbols("pin", PIN, anchor = "bottom"),
    )

    private fun line(id: String, source: String, color: String, width: Double, role: String? = null) = layer(id, "line") {
        put("source", source)
        if (role != null) put("filter", eq("role", role))
        put("layout", ROUND)
        put("paint", obj("line-color" to color, "line-width" to width))
    }

    private fun symbols(id: String, source: String, role: String? = null, anchor: String = "center") = layer(id, "symbol") {
        put("source", source)
        if (role != null) put("filter", eq("role", role))
        put("layout", obj(
            "icon-image" to arr("get", "icon"),
            "icon-anchor" to anchor,
            "icon-allow-overlap" to true,
            "icon-ignore-placement" to true,
        ))
    }

    companion object {
        /** Layers whose id starts with this are tappable; their features carry a `tap` property. */
        const val TAP_PREFIX = "tap-"

        const val OSM_SOURCE = "osm"

        /** Deepest zoom the tile server is asked for; MapLibre scales those tiles further. */
        const val OSM_MAX_ZOOM = 16

        // GeoJSON sources of the overlays.
        const val TRACKS = "mr-tracks"
        const val RECORDING = "mr-recording"
        const val ROUTE = "mr-route"
        const val TOOLS = "mr-tools"
        const val PLACES = "mr-places"
        const val PARTY = "mr-party"
        const val ME = "mr-me"
        const val PIN = "mr-pin"
        val OVERLAY_SOURCES = listOf(TRACKS, RECORDING, ROUTE, TOOLS, PLACES, PARTY, ME, PIN)

        /** Colour around the maps (no data). */
        const val OUTSIDE = "#EDEADA"

        private val AREA_TYPES = setOf("background", "fill", "fill-extrusion")

        private val ROUND = obj("line-join" to "round", "line-cap" to "round")

        private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content
        private fun JsonObject.source(): String? = (this["source"] as? JsonPrimitive)?.content

        /** Fills and their outlines: dropped over imagery. */
        private fun JsonObject.isLandCover(): Boolean =
            type() in AREA_TYPES || (this["metadata"] as? JsonObject)?.containsKey(ThemeStyle.AREA_FLAG) == true

        private fun rasterSource(src: OnlineSource) = buildJsonObject {
            put("type", "raster")
            putJsonArray("tiles") { tileUrls(src).forEach { add(JsonPrimitive(it)) } }
            put("tileSize", 256)
            put("maxzoom", src.maxZoom)
            put("attribution", src.attribution)
        }

        /** MapLibre has no `{s}`: one URL per subdomain instead. */
        fun tileUrls(src: OnlineSource): List<String> =
            if ("{s}" in src.urlTemplate && src.subdomains.isNotEmpty()) src.subdomains.map { src.urlTemplate.replace("{s}", it) }
            else listOf(src.urlTemplate)

        private fun rasterLayer(id: String, source: String) = layer(id, "raster") { put("source", source) }

        private fun layer(id: String, type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("id", id)
            put("type", type)
            body()
        }

        private fun eq(key: String, value: String) = arr("==", arr("get", key), value)

        /** A JSON array from strings, numbers, booleans and JSON elements. */
        internal fun arr(vararg items: Any): JsonArray = buildJsonArray { items.forEach { add(json(it)) } }

        internal fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to json(v) })

        private fun json(v: Any): JsonElement = when (v) {
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> error("Not a JSON value: $v")
        }

        /** The id of a style layer (for tests and filtering). */
        fun idOf(layer: JsonElement): String? = (layer as? JsonObject)?.get("id")?.jsonPrimitive?.content
    }
}
