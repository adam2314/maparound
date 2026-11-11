package app.maparound.map.maplibre

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A simple first style for the vector tiles, until the Topo style (from topo.xml) replaces it. */
object BasicLayers {
    /** Online fonts for now; bundled with the Topo style. */
    const val GLYPHS = "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf"

    val layers: List<JsonObject> by lazy { Json.parseToJsonElement(JSON).jsonArray.map { it.jsonObject } }

    private const val JSON = """[
  { "id": "land", "type": "background", "paint": { "background-color": "#FAF7E1" } },
  { "id": "sea", "type": "fill", "source": "osm", "source-layer": "sea", "paint": { "fill-color": "#8EBDF0" } },
  { "id": "forest", "type": "fill", "source": "osm", "source-layer": "area",
    "filter": ["any", ["==", ["get", "landuse"], "forest"], ["==", ["get", "natural"], "wood"]],
    "paint": { "fill-color": "#CFE3B8" } },
  { "id": "grass", "type": "fill", "source": "osm", "source-layer": "area",
    "filter": ["any", ["match", ["get", "landuse"], ["meadow", "grass", "farmland", "orchard", "vineyard"], true, false],
                      ["match", ["get", "natural"], ["grassland", "heath", "scrub"], true, false]],
    "paint": { "fill-color": "#E6EFC8" } },
  { "id": "residential", "type": "fill", "source": "osm", "source-layer": "area",
    "filter": ["match", ["get", "landuse"], ["residential", "commercial", "industrial", "retail"], true, false],
    "paint": { "fill-color": "#EDE6DA" } },
  { "id": "water", "type": "fill", "source": "osm", "source-layer": "area",
    "filter": ["any", ["==", ["get", "natural"], "water"], ["has", "water"], ["==", ["get", "waterway"], "riverbank"]],
    "paint": { "fill-color": "#8EBDF0" } },
  { "id": "buildings", "type": "fill", "source": "osm", "source-layer": "area", "minzoom": 14,
    "filter": ["has", "building"],
    "paint": { "fill-color": "#D9D0C9", "fill-outline-color": "#B9AEA4" } },
  { "id": "waterways", "type": "line", "source": "osm", "source-layer": "line",
    "filter": ["has", "waterway"],
    "paint": { "line-color": "#6FA8DC", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 16, 2.5] } },
  { "id": "paths", "type": "line", "source": "osm", "source-layer": "line",
    "filter": ["match", ["get", "highway"], ["path", "footway", "track", "bridleway", "steps", "cycleway"], true, false],
    "paint": { "line-color": "#9C6B3F", "line-width": 1.2, "line-dasharray": [3, 2] } },
  { "id": "road-casing", "type": "line", "source": "osm", "source-layer": "line",
    "filter": ["match", ["get", "highway"], ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service", "living_street"], true, false],
    "layout": { "line-cap": "round", "line-join": "round" },
    "paint": { "line-color": "#A69B8C", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.2, 16, 9] } },
  { "id": "roads", "type": "line", "source": "osm", "source-layer": "line",
    "filter": ["match", ["get", "highway"], ["motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service", "living_street"], true, false],
    "layout": { "line-cap": "round", "line-join": "round" },
    "paint": {
      "line-color": ["match", ["get", "highway"], ["motorway", "trunk"], "#F2A65A", ["primary", "secondary"], "#F7D88C", "#FFFFFF"],
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 16, 7] } },
  { "id": "road-names", "type": "symbol", "source": "osm", "source-layer": "line", "minzoom": 14,
    "filter": ["all", ["has", "highway"], ["has", "name"]],
    "layout": { "symbol-placement": "line", "text-field": ["get", "name"], "text-font": ["Noto Sans Regular"], "text-size": 11 },
    "paint": { "text-color": "#333333", "text-halo-color": "#FFFFFF", "text-halo-width": 1.2 } },
  { "id": "places", "type": "symbol", "source": "osm", "source-layer": "poi",
    "filter": ["all", ["has", "place"], ["has", "name"]],
    "layout": { "text-field": ["get", "name"], "text-font": ["Noto Sans Bold"],
                "text-size": ["match", ["get", "place"], ["city", "town"], 16, 13] },
    "paint": { "text-color": "#222222", "text-halo-color": "#FFFFFF", "text-halo-width": 1.5 } },
  { "id": "peaks", "type": "symbol", "source": "osm", "source-layer": "poi", "minzoom": 12,
    "filter": ["==", ["get", "natural"], "peak"],
    "layout": { "text-field": ["concat", "▲ ", ["coalesce", ["get", "name"], ""]], "text-font": ["Noto Sans Regular"], "text-size": 11 },
    "paint": { "text-color": "#6B4A2B", "text-halo-color": "#FFFFFF", "text-halo-width": 1.2 } }
]"""
}
