package app.maparound.map.maplibre

import app.maparound.data.Prefs
import app.maparound.map.BaseMap
import app.maparound.map.OnlineSource
import app.maparound.map.TileSources
import app.maparound.map.satelliteSource
import app.maparound.shared.mvt.MapsforgeToMvt
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
 * area and sea); [glyphs] is the font URL template for their labels when the tile server has
 * no fonts of its own.
 */
class MapStyle(
    private val vectorLayers: List<JsonObject>,
    private val glyphs: String,
    /** Where hill shading goes among [vectorLayers] (the theme says), and its zoom range. */
    private val hillshade: ThemeStyle.Hillshade? = null,
    /**
     * The theme drawn with only some rule categories (its options, see StyleMenu); null when
     * [vectorLayers] is all there is. Results are kept per set of categories.
     */
    private val variant: ((Set<String>) -> ThemeStyle.Result)? = null,
) {

    private val variants = HashMap<Set<String>, ThemeStyle.Result>()

    /**
     * The whole style document for [base] (null: an empty map with the overlays only), tiles from
     * [tiles]; with the online marked-trails overlay when [trailOverlay]. [forTextures]: the map
     * as a picture for the 3D view's terrain: no labels and icons (the 3D view stands them up), no
     * hill shading (its light shades the relief) and no overlays.
     */
    fun build(base: BaseMap?, tiles: TileService, trailOverlay: Boolean = false, forTextures: Boolean = false): String = buildJsonObject {
        val tilesBaseUrl = tiles.baseUrl
        put("version", 8)
        put("name", "MapAround")
        put("glyphs", tiles.glyphsUrl ?: glyphs)
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
                    putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/osm/${tiles.osmVersion}/{z}/{x}/{y}.pbf")) }
                    put("minzoom", 0)
                    put("maxzoom", OSM_MAX_ZOOM)
                    put("attribution", "© OpenStreetMap contributors")
                }
                val terrain = tiles.terrainVersion
                val theme = base.categories?.let { cats -> variant?.let { v -> variants.getOrPut(cats) { v(cats) } } }
                val shading = theme?.hillshade ?: hillshade
                val themeLayers = (theme?.layers ?: vectorLayers).toMutableList()
                if (terrain != null && base.contours) {
                    sources[ThemeStyle.CONTOURS] = buildJsonObject {
                        put("type", "vector")
                        putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/contours/$terrain/{z}/{x}/{y}.pbf")) }
                        put("minzoom", TerrainTiles.CONTOUR_MIN_ZOOM)
                        put("maxzoom", TerrainTiles.CONTOUR_MAX_ZOOM)
                    }
                }
                if (terrain != null && base.hillshade && !forTextures) {
                    sources[DEM_SOURCE] = buildJsonObject {
                        put("type", "raster-dem")
                        put("encoding", "terrarium")
                        putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/dem/$terrain/{z}/{x}/{y}.png")) }
                        put("tileSize", TerrainTiles.DEM_SIZE)
                        put("maxzoom", TerrainTiles.DEM_MAX_ZOOM)
                    }
                    val at = (shading?.index ?: 1).coerceIn(0, themeLayers.size)
                    themeLayers.add(at, hillshadeLayer(shading))
                }
                // Marked trails built into the maps (MapAround's own), when they're switched on.
                if (!base.trails || !base.drawsTerrain) {
                    themeLayers.removeAll { it.source() == OSM_SOURCE && it.sourceLayer() == MapsforgeToMvt.TRAILS }
                }
                // Downloaded marked trails (the platform serves them), for the styles that draw them.
                val trails = tiles.trailsVersion
                if (trails != null && base.trails && base.drawsTerrain) {
                    sources[ThemeStyle.TRAILS] = buildJsonObject {
                        put("type", "vector")
                        putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/trails/$trails/{z}/{x}/{y}.pbf")) }
                        put("minzoom", TRAILS_MIN_ZOOM)
                        put("maxzoom", OSM_MAX_ZOOM)
                    }
                }
                val satellite = base.background == Prefs.BACKGROUND_SATELLITE
                val raster = base.background.takeIf { it.startsWith(Prefs.BACKGROUND_RASTER) }
                    ?.let { id -> base.rasterMaps.firstOrNull { it.id == id.removePrefix(Prefs.BACKGROUND_RASTER) } }
                if (satellite) {
                    sources["satellite"] = rasterSource(satelliteSource)
                    layers += rasterLayer("satellite", "satellite")
                } else if (raster != null) {
                    sources["raster"] = buildJsonObject {
                        put("type", "raster")
                        putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/raster/${raster.id}/{z}/{x}/{y}.png")) }
                        put("tileSize", 256)
                        putJsonArray("bounds") {
                            add(JsonPrimitive(raster.west)); add(JsonPrimitive(raster.south))
                            add(JsonPrimitive(raster.east)); add(JsonPrimitive(raster.north))
                        }
                        put("maxzoom", RASTER_MAX_ZOOM)
                    }
                    layers += rasterLayer("raster", "raster")
                }
                // Over imagery only lines, symbols and labels stay ("hybrid"), or nothing at all.
                layers += when {
                    !satellite && raster == null -> themeLayers
                    base.vectorOnTop -> themeLayers.filter { !it.isLandCover() }
                    else -> emptyList()
                }
            }
        }
        if (trailOverlay) {
            sources["trails-online"] = rasterSource(TileSources.trailsOverlay)
            // From the zoom the downloaded trails start at: zoomed out, Europe's long-distance routes
            // would cover the map.
            layers += rasterLayer("trails-online", "trails-online", minZoom = TRAILS_MIN_ZOOM)
        }
        tiles.viewshed?.takeUnless { forTextures }?.let { vs ->
            sources[VIEWSHED] = buildJsonObject {
                put("type", "image")
                put("url", "$tilesBaseUrl/viewshed/${viewshedKey(vs)}.png")
                putJsonArray("coordinates") {
                    for ((lon, lat) in listOf(vs.west to vs.north, vs.east to vs.north, vs.east to vs.south, vs.west to vs.south)) {
                        add(arr(lon, lat))
                    }
                }
            }
            layers += layer(VIEWSHED, "raster") {
                put("source", VIEWSHED)
                put("paint", obj("raster-fade-duration" to 0))
            }
        }
        if (!forTextures) {
            for (id in OVERLAY_SOURCES) {
                sources[id] = buildJsonObject {
                    put("type", "geojson")
                    put("data", JsonObject(mapOf("type" to JsonPrimitive("FeatureCollection"), "features" to JsonArray(emptyList()))))
                }
            }
            layers += overlayLayers()
        } else {
            layers.removeAll { (it as JsonObject).type() == "symbol" }
        }
        put("sources", JsonObject(sources))
        // Theme layers for tiles this map doesn't have (contours, trails) stay out.
        put("layers", JsonArray(layers.filter { l -> (l as JsonObject).source().let { it == null || it in sources } }))
    }.toString()

    /** MapLibre's own hill shading from the elevation tiles, in the Topo style's browns. */
    private fun hillshadeLayer(hillshade: ThemeStyle.Hillshade?) = layer("hillshade", "hillshade") {
        put("source", DEM_SOURCE)
        put("minzoom", maxOf((hillshade?.minZoom ?: 8) - 1, 0))
        hillshade?.maxZoom?.takeIf { it < 99 }?.let { put("maxzoom", it) }
        put("paint", obj(
            "hillshade-exaggeration" to 0.45,
            "hillshade-shadow-color" to "#5B4A33",
            "hillshade-highlight-color" to "rgba(255,255,255,0.35)",
            "hillshade-accent-color" to "#5B4A33",
        ))
    }

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
        const val DEM_SOURCE = "dem"

        /** Deepest zoom the tile server is asked for; MapLibre scales those tiles further. */
        const val OSM_MAX_ZOOM = 16

        /** Marked trails from here (MapLibre zooms; the trail data starts at mapsforge's 11). */
        const val TRAILS_MIN_ZOOM = 10

        /** Raster maps are asked for up to here, then scaled. */
        const val RASTER_MAX_ZOOM = 19

        /** The viewshed image's source and layer. */
        const val VIEWSHED = "mr-viewshed"

        /** Names the viewshed image: a new one gets a new URL. */
        fun viewshedKey(vs: app.maparound.tools.Viewshed): String = (vs.hashCode().toLong() and 0xFFFFFFFFL).toString(16)

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
        private fun JsonObject.sourceLayer(): String? = (this["source-layer"] as? JsonPrimitive)?.content

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

        private fun rasterLayer(id: String, source: String, minZoom: Int? = null) = layer(id, "raster") {
            put("source", source)
            if (minZoom != null) put("minzoom", minZoom)
        }

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
