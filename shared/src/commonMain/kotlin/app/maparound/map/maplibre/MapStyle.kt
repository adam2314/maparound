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
    fun build(
        base: BaseMap?,
        tiles: TileService,
        trailOverlay: Boolean = false,
        forTextures: Boolean = false,
        /** The data view's raster layers (GPS traces, rain radar), drawn over its colours in this order. */
        dataRasters: List<OnlineSource> = emptyList(),
        /** The data view's street photos: their vector tiles' address ({z}/{x}/{y}), or null. */
        photoTiles: String? = null,
    ): String = buildJsonObject {
        val tilesBaseUrl = tiles.baseUrl
        put("version", 8)
        put("name", "MapAround")
        put("glyphs", tiles.glyphsUrl ?: glyphs)
        val sources = LinkedHashMap<String, JsonElement>()
        val layers = ArrayList<JsonElement>()
        layers += layer("canvas", "background") { put("paint", obj("background-color" to OUTSIDE)) }
        when (base) {
            null -> Unit
            // An online vector map (someone else's style, the web's): its layers under ours; until
            // its style is read, nothing.
            is BaseMap.Online -> if (base.source.style != null) {
                base.vectorStyle?.let { OnlineVectorStyle.parts(it, base.language) }?.let { v ->
                    sources += v.sources
                    layers += v.layers
                    v.sprite?.let { put("sprite", it) }
                }
            } else {
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
                val terrain = tiles.terrainKey
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
                val satellite = base.background == Prefs.BACKGROUND_SATELLITE
                val raster = base.background.takeIf { it.startsWith(Prefs.BACKGROUND_RASTER) }
                    ?.let { id -> base.rasterMaps.firstOrNull { it.id == id.removePrefix(Prefs.BACKGROUND_RASTER) } }
                if (satellite) {
                    sources["satellite"] = rasterSource(satelliteSource)
                    layers += rasterLayer("satellite", "satellite")
                } else if (raster != null) {
                    sources["raster"] = buildJsonObject {
                        put("type", "raster")
                        putJsonArray("tiles") { add(JsonPrimitive("$tilesBaseUrl/raster/${raster.id}.${raster.revision}/{z}/{x}/{y}.png")) }
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
            for (src in dataRasters) sources[dataRasterId(src)] = rasterSource(src)
            if (photoTiles != null) {
                sources[DATA_PHOTOS] = buildJsonObject {
                    put("type", "vector")
                    putJsonArray("tiles") { add(JsonPrimitive(photoTiles)) }
                    put("minzoom", 0)
                    put("maxzoom", app.maparound.stats.StreetPhotos.TILES_MAX_ZOOM)
                    put("attribution", "Photos: Panoramax contributors")
                }
            }
            for (id in OVERLAY_SOURCES) {
                sources[id] = buildJsonObject {
                    put("type", "geojson")
                    put("data", JsonObject(mapOf("type" to JsonPrimitive("FeatureCollection"), "features" to JsonArray(emptyList()))))
                }
            }
            layers += overlayLayers(dataRasters)
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

    private fun overlayLayers(dataRasters: List<OnlineSource>): List<JsonObject> = listOf(
        // The data view (the web app): the time zones, or the countries coloured by a figure until
        // zoom 7; rasters over them (GPS traces, rain radar); street photos; Israel's localities
        // from 6; buses; the weather's points; the zones' offsets.
        layer("${TAP_PREFIX}data-zones", "fill") {
            put("source", DATA_ZONES)
            put("maxzoom", 11)
            put("filter", eq("role", "zone"))
            put("paint", obj(
                "fill-color" to arr("get", "color"),
                "fill-opacity" to arr("interpolate", arr("linear"), arr("zoom"), 2, 0.7, 8, 0.35),
            ))
        },
        layer("data-zones-line", "line") {
            put("source", DATA_ZONES)
            put("maxzoom", 11)
            put("filter", eq("role", "zone"))
            put("paint", obj("line-color" to "rgba(255,255,255,0.85)", "line-width" to 0.8))
        },
        layer("${TAP_PREFIX}data-countries", "fill") {
            put("source", DATA_COUNTRIES)
            put("maxzoom", 7)
            put("filter", eq("role", "country"))
            put("paint", obj(
                "fill-color" to arr("get", "color"),
                "fill-opacity" to arr("interpolate", arr("linear"), arr("zoom"), 2, 0.8, 6.5, 0.45),
                "fill-outline-color" to "rgba(255,255,255,0.9)",
            ))
        },
        // The tapped country (its card open): lifted a little, and outlined in white over a dark edge.
        layer("data-selected-fill", "fill") {
            put("source", DATA_SELECTED)
            put("maxzoom", 7)
            put("paint", obj("fill-color" to "#FFFFFF", "fill-opacity" to 0.22))
        },
        layer("data-selected-casing", "line") {
            put("source", DATA_SELECTED)
            put("maxzoom", 7)
            put("layout", ROUND)
            put("paint", obj(
                "line-color" to "rgba(0,0,0,0.6)",
                "line-width" to arr("interpolate", arr("linear"), arr("zoom"), 1, 3.5, 6, 6.0),
            ))
        },
        layer("data-selected-line", "line") {
            put("source", DATA_SELECTED)
            put("maxzoom", 7)
            put("layout", ROUND)
            put("paint", obj(
                "line-color" to "#FFFFFF",
                "line-width" to arr("interpolate", arr("linear"), arr("zoom"), 1, 1.6, 6, 3.0),
            ))
        },
    ) + dataRasters.map { src ->
        layer(dataRasterId(src), "raster") {
            put("source", dataRasterId(src))
            put("paint", obj("raster-fade-duration" to 0, "raster-opacity" to if (src.id.startsWith("rain_radar")) 0.75 else 1.0))
        }
    } + listOf(
        // Street photos (Panoramax): where there are some, zoomed out; the ways they were taken
        // along; each photo (tapped: "data:p:" and its id, the tiles' own key).
        layer("data-photos-grid", "circle") {
            put("source", DATA_PHOTOS)
            put("source-layer", "grid")
            put("maxzoom", 7)
            put("filter", arr(">", arr("get", "coef"), 0))
            put("paint", obj(
                "circle-radius" to arr("interpolate", arr("linear"), arr("zoom"), 1, 2.5, 6, 5),
                "circle-color" to arr("interpolate", arr("linear"), arr("get", "coef"), 0, "#FFA726", 0.5, "#E65100", 1, "#3E2723"),
                "circle-opacity" to 0.8,
            ))
        },
        layer("data-photos-sequences", "line") {
            put("source", DATA_PHOTOS)
            put("source-layer", "sequences")
            put("minzoom", 6)
            put("layout", ROUND)
            put("paint", obj(
                "line-color" to "#FF6F00",
                "line-width" to arr("interpolate", arr("linear"), arr("zoom"), 6, 1.0, 10, 2.0, 14, 3.5, 17, 4.5),
                "line-opacity" to 0.9,
            ))
        },
        layer("${TAP_PREFIX}data-photos", "circle") {
            put("source", DATA_PHOTOS)
            put("source-layer", "pictures")
            put("minzoom", app.maparound.stats.StreetPhotos.PHOTOS_ZOOM.toDouble())
            put("metadata", obj("mr:tap" to app.maparound.stats.StreetPhotos.TAP, "mr:tapKey" to "id"))
            put("paint", obj(
                "circle-color" to "#FF6F00",
                "circle-radius" to arr("interpolate", arr("linear"), arr("zoom"), 15, 4.0, 17, 7.0, 20, 10.0),
                "circle-stroke-color" to "#FFFFFF",
                "circle-stroke-width" to 1.5,
            ))
        },
        layer("${TAP_PREFIX}data-localities", "circle") {
            put("source", DATA_LOCALITIES)
            put("minzoom", 6)
            put("paint", obj(
                "circle-color" to arr("get", "color"),
                // Zoom may only be a top-level interpolation's input: the radius is in each stop.
                "circle-radius" to arr(
                    "interpolate", arr("linear"), arr("zoom"),
                    6, arr("*", arr("get", "r"), 0.45), 9, arr("*", arr("get", "r"), 0.8),
                    12, arr("*", arr("get", "r"), 1.4), 15, arr("*", arr("get", "r"), 2.2),
                ),
                "circle-opacity" to 0.88,
                "circle-stroke-color" to "#FFFFFF",
                "circle-stroke-width" to 0.8,
            ))
        },
        // Buses where they are now: a dot, and the line's number closer in.
        layer("${TAP_PREFIX}data-buses", "circle") {
            put("source", DATA_BUSES)
            put("paint", obj(
                "circle-color" to "#2E7D32",
                "circle-radius" to arr("interpolate", arr("linear"), arr("zoom"), 9, 2.5, 13, 5.5, 16, 8.0),
                "circle-stroke-color" to "#FFFFFF",
                "circle-stroke-width" to 1.2,
            ))
        },
        layer("data-buses-label", "symbol") {
            put("source", DATA_BUSES)
            put("minzoom", 12)
            put("layout", obj(
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 11,
                "text-anchor" to "top",
                "text-offset" to arr(0, 0.6),
                "text-padding" to 2,
            ))
            put("paint", obj("text-color" to "#1B5E20", "text-halo-color" to "rgba(255,255,255,0.95)", "text-halo-width" to 1.6))
        },
        // Cameras (OpenStreetMap's): a dot in their kind's colour, a speed camera's limit closer in.
        layer("${TAP_PREFIX}data-cameras", "circle") {
            put("source", DATA_CAMERAS)
            put("paint", obj(
                "circle-color" to arr("get", "color"),
                "circle-radius" to arr("interpolate", arr("linear"), arr("zoom"), 9, 3.5, 14, 6.0, 17, 8.0),
                "circle-stroke-color" to "#FFFFFF",
                "circle-stroke-width" to 1.5,
            ))
        },
        layer("data-cameras-label", "symbol") {
            put("source", DATA_CAMERAS)
            put("minzoom", 11)
            put("filter", arr("!=", arr("get", "label"), ""))
            put("layout", obj(
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 11,
                "text-anchor" to "top",
                "text-offset" to arr(0, 0.7),
                "text-padding" to 2,
            ))
            put("paint", obj("text-color" to "#B71C1C", "text-halo-color" to "rgba(255,255,255,0.95)", "text-halo-width" to 1.6))
        },
        // The weather at points over the view: a dot in its class's colour, its value above it.
        layer("${TAP_PREFIX}data-weather", "circle") {
            put("source", DATA_WEATHER)
            put("paint", obj(
                "circle-color" to arr("get", "color"),
                "circle-radius" to 6.5,
                "circle-stroke-color" to "#FFFFFF",
                "circle-stroke-width" to 1.5,
            ))
        },
        // The wind's arrow, over its dot, pointing where it blows.
        layer("data-weather-arrow", "symbol") {
            put("source", DATA_WEATHER)
            put("filter", arr("has", "rot"))
            put("layout", obj(
                "text-field" to "↑",
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 17,
                "text-rotate" to arr("get", "rot"),
                "text-rotation-alignment" to "map",
                "text-allow-overlap" to true,
                "text-ignore-placement" to true,
            ))
            put("paint", obj("text-color" to "#1B1B1B", "text-halo-color" to "rgba(255,255,255,0.95)", "text-halo-width" to 1.5))
        },
        layer("data-weather-label", "symbol") {
            put("source", DATA_WEATHER)
            put("layout", obj(
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 13,
                "text-anchor" to "bottom",
                "text-offset" to arr(0, -0.8),
                "text-padding" to 1,
                "text-allow-overlap" to true,
            ))
            put("paint", obj("text-color" to "#1B1B1B", "text-halo-color" to "rgba(255,255,255,0.95)", "text-halo-width" to 1.8))
        },
        // The countries' names (in the app's language), each from the zoom its size allows.
        layer("data-countries-label", "symbol") {
            put("source", DATA_COUNTRIES)
            put("maxzoom", 7)
            put("filter", arr("all", eq("role", "label"), arr(">=", arr("zoom"), arr("get", "minz"))))
            put("layout", obj(
                "symbol-sort-key" to arr("get", "rank"),
                "text-field" to arr("get", "name"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to arr("interpolate", arr("linear"), arr("zoom"), 1, 10, 6, 14),
                "text-max-width" to 7,
                "text-padding" to 4,
            ))
            put("paint", obj(
                "text-color" to "#1B1B1B",
                "text-halo-color" to "rgba(255,255,255,0.9)",
                "text-halo-width" to 1.6,
            ))
        },
        layer("data-zones-label", "symbol") {
            put("source", DATA_ZONES)
            put("maxzoom", 11)
            // Each from the zoom its zone's size allows; the big ones placed first.
            put("filter", arr("all", eq("role", "label"), arr(">=", arr("zoom"), arr("get", "minz"))))
            put("layout", obj(
                "symbol-sort-key" to arr("get", "rank"),
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 12,
                "text-padding" to 6,
            ))
            put("paint", obj(
                "text-color" to "#1B1B1B",
                "text-halo-color" to "rgba(255,255,255,0.9)",
                "text-halo-width" to 1.6,
            ))
        },
        // The coordinate grid, under everything the user put on the map; its values haloed.
        layer("grid-line", "line") {
            put("source", GRID)
            put("filter", eq("role", "line"))
            put("paint", obj("line-color" to "rgba(20,20,20,0.55)", "line-width" to 1.1))
        },
        // A line's value along it, upright, every so often: the map places them as it moves, so
        // they stay in view and don't jump when the grid is made again.
        layer("grid-line-label", "symbol") {
            put("source", GRID)
            put("filter", arr("all", eq("role", "line"), arr("has", "label")))
            put("layout", obj(
                "symbol-placement" to "line",
                "symbol-spacing" to 320,
                "text-rotation-alignment" to "viewport",
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 11,
                "text-padding" to 6,
            ))
            put("paint", obj(
                "text-color" to "#1B1B1B",
                "text-halo-color" to "rgba(255,255,255,0.92)",
                "text-halo-width" to 1.6,
            ))
        },
        layer("grid-label", "symbol") {
            put("source", GRID)
            put("filter", eq("role", "label"))
            put("layout", obj(
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 11,
                "text-padding" to 4,
            ))
            put("paint", obj(
                "text-color" to "#1B1B1B",
                "text-halo-color" to "rgba(255,255,255,0.92)",
                "text-halo-width" to 1.6,
            ))
        },
        line("tracks-casing", TRACKS, "#FFFFFF", 7.0),
        line("tracks-line", TRACKS, "#E65100", 4.0),
        arrows("tracks-arrows", TRACKS, "#E65100"),
        line("recording", RECORDING, "#D50000", 4.0),
        // The parties' marks: areas and circles filled as set, lines (and outlines) solid, dashed or
        // dotted in their colour and width, points as badges, names alone as labels. All tappable.
        layer("${TAP_PREFIX}party-marks-fill", "fill") {
            put("source", PARTY_MARKS)
            put("filter", arr("==", arr("geometry-type"), "Polygon"))
            put("paint", obj("fill-color" to arr("get", "color"), "fill-opacity" to arr("get", "fill")))
        },
        *listOf("solid" to null, "dashed" to arr(2, 2), "dotted" to arr(0, 2)).map { (style, dashes) ->
            layer("${TAP_PREFIX}party-marks-$style", "line") {
                put("source", PARTY_MARKS)
                put("filter", arr("all", arr("==", arr("get", "line"), style), arr("!=", arr("geometry-type"), "Point")))
                put("layout", ROUND)
                put("paint", obj(
                    *listOfNotNull(
                        "line-color" to arr("get", "color"),
                        "line-width" to arr("get", "width"),
                        dashes?.let { "line-dasharray" to it },
                    ).toTypedArray(),
                ))
            }
        }.toTypedArray(),
        layer("${TAP_PREFIX}party-marks-labels", "symbol") {
            put("source", PARTY_MARKS)
            put("filter", eq("role", "label"))
            put("layout", obj(
                "text-field" to arr("get", "label"),
                "text-font" to arr("Noto Sans Bold"),
                "text-size" to 13,
                "text-allow-overlap" to true,
            ))
            put("paint", obj("text-color" to arr("get", "color"), "text-halo-color" to "rgba(255,255,255,0.95)", "text-halo-width" to 1.8))
        },
        symbols("${TAP_PREFIX}party-marks", PARTY_MARKS, role = "point"),
        // The members' recent ways (the party's trails layer), each in its member's colour, thinner than a replay.
        line("party-trails-casing", PARTY_TRAILS, "#FFFFFF", 5.0),
        layer("party-trails", "line") {
            put("source", PARTY_TRAILS)
            put("layout", ROUND)
            put("paint", obj("line-color" to arr("get", "color"), "line-width" to 3.0, "line-opacity" to 0.8))
        },
        // A party member's way replayed: what's ahead faint, what's been gone in their colour.
        line("party-trail-casing", PARTY_TRAIL, "#FFFFFF", 7.0, role = "done"),
        layer("party-trail", "line") {
            put("source", PARTY_TRAIL)
            put("layout", ROUND)
            put("paint", obj(
                "line-color" to arr("get", "color"),
                "line-width" to 4.0,
                "line-opacity" to arr("match", arr("get", "role"), "done", 1.0, 0.45),
            ))
        },
        // The route a little wider closer in, and which way it goes.
        line("route-casing", ROUTE, "#FFFFFF", widerIn(8.0, 11.0), role = "line"),
        line("route-line", ROUTE, "#6A1B9A", widerIn(5.0, 7.0), role = "line"),
        arrows("route-arrows", ROUTE, "#6A1B9A", role = "line"),
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
        // The tool's point a tap moves next: ringed under its letter.
        layer("tools-selected", "circle") {
            put("source", TOOLS)
            put("filter", eq("role", "selected"))
            put("paint", obj(
                "circle-radius" to 20.0,
                "circle-color" to "rgba(255,214,0,0.28)",
                "circle-stroke-color" to "#FFD600",
                "circle-stroke-width" to 3.0,
            ))
        },
        symbols("tools-points", TOOLS, role = "letter"),
        symbols("${TAP_PREFIX}waypoints", ROUTE, role = "wp"),
        symbols("${TAP_PREFIX}me", ME, role = "dot"),
        symbols("pin", PIN, anchor = "bottom"),
    )

    private fun line(id: String, source: String, color: String, width: Any, role: String? = null) = layer(id, "line") {
        put("source", source)
        if (role != null) put("filter", eq("role", role))
        put("layout", ROUND)
        put("paint", obj("line-color" to color, "line-width" to width))
    }

    /** A width growing from [near10] at zoom 10 to [near16] at 16. */
    private fun widerIn(near10: Double, near16: Double) = arr("interpolate", arr("linear"), arr("zoom"), 10, near10, 16, near16)

    /**
     * Which way a line goes: small white arrowheads along it, every so often, edged in its
     * [color] (a glyph of the labels' font, so every platform draws it), from zoom 10.
     */
    private fun arrows(id: String, source: String, color: String, role: String? = null) = layer(id, "symbol") {
        put("source", source)
        if (role != null) put("filter", eq("role", role))
        put("minzoom", ARROWS_MIN_ZOOM)
        put("layout", obj(
            "symbol-placement" to "line",
            "symbol-spacing" to 110,
            "text-field" to "▶",
            "text-font" to arr("Noto Sans Bold"),
            "text-size" to 12,
            "text-keep-upright" to false,
            "text-rotation-alignment" to "map",
            "text-allow-overlap" to true,
            "text-ignore-placement" to true,
            "text-padding" to 0,
        ))
        put("paint", obj("text-color" to "#FFFFFF", "text-halo-color" to color, "text-halo-width" to 1.2))
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

        /** Routes' and tracks' arrows from here (MapLibre zooms). */
        const val ARROWS_MIN_ZOOM = 10

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
        const val PARTY_TRAIL = "mr-party-trail"
        const val PARTY_TRAILS = "mr-party-trails"
        const val PARTY_MARKS = "mr-party-marks"
        const val ROUTE = "mr-route"
        const val TOOLS = "mr-tools"
        const val PLACES = "mr-places"
        const val PARTY = "mr-party"
        const val ME = "mr-me"
        const val PIN = "mr-pin"
        const val GRID = "mr-grid"
        const val DATA_COUNTRIES = "mr-data-countries"
        const val DATA_LOCALITIES = "mr-data-localities"
        const val DATA_ZONES = "mr-data-zones"
        const val DATA_PHOTOS = "mr-data-photos"
        const val DATA_BUSES = "mr-data-buses"
        const val DATA_WEATHER = "mr-data-weather"
        const val DATA_CAMERAS = "mr-data-cameras"

        /** The data view's tapped country, drawn over the others ([app.maparound.stats.StatsLayers.selection]). */
        const val DATA_SELECTED = "mr-data-selected"

        /** A data view raster's source and layer. */
        fun dataRasterId(src: OnlineSource) = "mr-data-raster-${src.id}"
        val OVERLAY_SOURCES = listOf(DATA_ZONES, DATA_COUNTRIES, DATA_SELECTED, DATA_LOCALITIES, DATA_BUSES, DATA_CAMERAS, DATA_WEATHER, GRID, TRACKS, RECORDING, PARTY_MARKS, PARTY_TRAILS, PARTY_TRAIL, ROUTE, TOOLS, PLACES, PARTY, ME, PIN)

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
