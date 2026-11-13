package app.maparound.map.maplibre

import app.maparound.shared.fileSystem
import app.maparound.shared.mvt.MapsforgeToMvt
import app.maparound.shared.testEnv
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemeStyleTest {
    /** The Android app's theme (THEME_FILE on the iOS simulator). */
    private fun topo(): String? {
        val path = (testEnv("THEME_FILE") ?: "../app/src/main/assets/themes/topo.xml").toPath()
        return if (fileSystem.exists(path)) fileSystem.read(path) { readUtf8() } else null
    }

    private val JsonObject.id get() = this["id"]!!.jsonPrimitive.content
    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** Evaluates the filter expressions the converter writes, like MapLibre would. */
    private fun eval(e: JsonElement?, props: Map<String, String>): Any? {
        if (e == null) return true
        if (e is JsonPrimitive) return e.booleanOrNull ?: e.doubleOrNull ?: e.content
        val a = e.jsonArray
        val op = a[0].jsonPrimitive.content
        return when (op) {
            "all" -> a.drop(1).all { eval(it, props) == true }
            "any" -> a.drop(1).any { eval(it, props) == true }
            "!" -> eval(a[1], props) != true
            "has" -> a[1].jsonPrimitive.content in props
            "get" -> props[a[1].jsonPrimitive.content]
            "==" -> eval(a[1], props) == eval(a[2], props)
            "match" -> {
                val v = eval(a[1], props)
                val labels = a[2].jsonArray.map { it.jsonPrimitive.content }
                if (v in labels) eval(a[3], props) else eval(a[4], props)
            }
            else -> error("Unexpected operator $op")
        }
    }

    private fun matches(layer: JsonObject, props: Map<String, String>) = eval(layer["filter"], props) == true

    @Test
    fun colours() {
        assertEquals("rgb(250,247,225)", ThemeStyle.color("#FAF7E1"))
        assertEquals("rgba(145,230,145,0.502)", ThemeStyle.color("#8091E691"))
    }

    @Test
    fun rulesBecomeFiltersAndZoomRanges() {
        val xml = """
            <rendertheme xmlns="http://mapsforge.org/renderTheme" map-background="#FFFFFF" version="5">
              <rule e="way" k="tunnel" v="~|no">
                <rule e="way" k="highway" v="primary|secondary" zoom-min="12" zoom-max="15">
                  <line stroke="#FF0000" stroke-width="2" />
                  <line stroke="#00FF00" stroke-width="1" scale="none" stroke-dasharray="4,2" dy="1.5" />
                </rule>
              </rule>
              <rule e="node" k="place" v="town" zoom-min="9">
                <caption k="name" fill="#404040" font-size="15" font-style="bold" stroke="#FFFFFF" stroke-width="2.5" priority="30" />
              </rule>
              <rule e="way" k="natural" v="sea|water">
                <area fill="#8EBDF0" stroke="#000000" stroke-width="0.5" />
              </rule>
              <rule e="node" k="highway" v="*">
                <line stroke="#000000" stroke-width="1" />
              </rule>
            </rendertheme>
        """.trimIndent()
        val layers = ThemeStyle.convert(xml).layers
        // Labels above everything else, like mapsforge draws them.
        assertEquals(listOf("background", "line", "line", "fill", "line", "fill", "line", "symbol"), layers.map { it.type })

        val road = layers[1]
        assertEquals("line", road.str("source-layer"))
        assertEquals(11, road["minzoom"]!!.jsonPrimitive.content.toInt())
        assertEquals(15, road["maxzoom"]!!.jsonPrimitive.content.toInt())
        assertTrue(matches(road, mapOf("highway" to "primary")))
        assertTrue(matches(road, mapOf("highway" to "secondary", "tunnel" to "no")))
        assertFalse(matches(road, mapOf("highway" to "secondary", "tunnel" to "yes")))
        assertFalse(matches(road, mapOf("highway" to "track")))
        // Widths grow with zoom unless scale="none"; dashes are in line widths.
        assertTrue(road["paint"]!!.jsonObject["line-width"] is JsonArray)
        val thin = layers[2]["paint"]!!.jsonObject
        assertEquals(1.0, thin["line-width"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(listOf(4.0, 2.0), thin["line-dasharray"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull })
        assertEquals(1.5, thin["line-offset"]!!.jsonPrimitive.doubleOrNull)

        val town = layers[7]
        assertEquals("poi", town.str("source-layer"))
        assertEquals(8, town["minzoom"]!!.jsonPrimitive.content.toInt())
        assertTrue(matches(town, mapOf("place" to "town", "name" to "Vaduz")))
        assertFalse(matches(town, mapOf("place" to "town", "label_of" to "area")))
        assertEquals(-30.0, town["layout"]!!.jsonObject["symbol-sort-key"]!!.jsonPrimitive.doubleOrNull)
        assertEquals(1.25, town["paint"]!!.jsonObject["text-halo-width"]!!.jsonPrimitive.doubleOrNull)

        // Water: on areas, and on whole-sea tiles; marked as land cover for hybrid maps.
        val water = layers.subList(3, 7)
        assertEquals(listOf("area", "area", "sea", "sea"), water.map { it.str("source-layer") })
        assertTrue(water.all { it["metadata"]?.jsonObject?.get(ThemeStyle.AREA_FLAG) != null })
    }

    @Test
    fun topoThemeConverts() {
        val xml = topo() ?: return
        val result = ThemeStyle.convert(xml)
        val layers = result.layers
        assertTrue(layers.size > 300, "${layers.size} layers")
        assertEquals(layers.size, layers.map { it.id }.toSet().size, "unique ids")
        // All labels and symbols come after the last area or line.
        assertTrue(layers.indexOfLast { it.type != "symbol" } < layers.indexOfFirst { it.type == "symbol" })
        assertEquals("background", layers[0].type)
        assertNotNull(result.hillshade)
        assertEquals(8, result.hillshade!!.minZoom)

        // Every layer is on a known source and its filter only uses what the evaluator knows.
        val sources = setOf(MapStyle.OSM_SOURCE, ThemeStyle.CONTOURS, ThemeStyle.TRAILS)
        for (l in layers.drop(1)) {
            assertTrue(l.str("source") in sources, l.id)
            eval(l["filter"], emptyMap())
        }

        fun drawn(props: Map<String, String>, type: String, sourceLayer: String) =
            layers.filter { it.type == type && it.str("source-layer") == sourceLayer && matches(it, props) }

        // A secondary road: grey casing under a coloured core, both growing with zoom.
        val secondary = drawn(mapOf("highway" to "secondary"), "line", "line")
        assertTrue(secondary.size >= 2, secondary.map { it.id }.toString())
        assertTrue(secondary.none { it.id.contains("tunnel") })
        // In a tunnel it's drawn dashed instead.
        val tunnel = drawn(mapOf("highway" to "secondary", "tunnel" to "yes"), "line", "line")
        assertTrue(tunnel.any { it["paint"]!!.jsonObject.containsKey("line-dasharray") })
        assertTrue(tunnel.size < secondary.size)

        // Forest, sea, contours and trails.
        assertTrue(drawn(mapOf("landuse" to "forest"), "fill", "area").isNotEmpty())
        assertTrue(drawn(mapOf("natural" to "sea"), "fill", "sea").isNotEmpty())
        assertTrue(layers.any { it.str("source") == ThemeStyle.CONTOURS && matches(it, mapOf("contour" to "major", "ele" to "1500")) })
        val trail = mapOf("trail" to "hiking", "trail_n" to "1", "trail_colour" to "red", "trail_offset" to "0")
        assertTrue(layers.any { it.str("source") == ThemeStyle.TRAILS && matches(it, trail) })
        // ...and the same trail lines when they're built into the map (MapAround's maps).
        assertTrue(drawn(trail, "line", MapsforgeToMvt.TRAILS).any { it.str("source") == MapStyle.OSM_SOURCE })

        // Town names on points; peaks' names above the (later) symbol.
        assertTrue(drawn(mapOf("place" to "town", "name" to "Vaduz"), "symbol", "poi").isNotEmpty())
        val peak = drawn(mapOf("natural" to "peak", "name" to "Naafkopf"), "symbol", "poi")
        assertTrue(peak.any { it["layout"]!!.jsonObject.str("text-anchor") == "bottom" })
        // The peak's triangle, smaller than the default symbol size.
        val icon = peak.first { it["layout"]!!.jsonObject.containsKey("icon-image") }["layout"]!!.jsonObject
        assertEquals("sym:symbols/peak.svg", icon.str("icon-image"))
        assertEquals(0.55, icon["icon-size"]!!.jsonPrimitive.doubleOrNull)
        // Every symbol image the theme uses has an SVG path.
        val images = layers.mapNotNull { it["layout"]?.jsonObject?.str("icon-image") }.toSet()
        assertTrue(images.size > 50 && images.all { it.startsWith(ThemeStyle.SYMBOL_PREFIX) && it.endsWith(".svg") }, images.toString())
    }

    @Test
    fun builtInTrailsFollowTheTrailSwitch() {
        val xml = topo() ?: return
        val style = MapStyle(ThemeStyle.convert(xml).layers, BasicLayers.GLYPHS)
        fun layers(trails: Boolean): List<JsonObject> {
            val base = app.maparound.map.BaseMap.Offline(
                maps = emptyList(), theme = app.maparound.map.MapThemes.TOPO, categories = null, contours = false, hillshade = false, terrainVersion = 0,
                trails = trails, trailsVersion = 0, background = app.maparound.data.Prefs.BACKGROUND_NONE, vectorOnTop = true, rasterMaps = emptyList(),
            )
            val json = kotlinx.serialization.json.Json.parseToJsonElement(style.build(base, TileService())).jsonObject
            return json["layers"]!!.jsonArray.map { it.jsonObject }
        }
        fun builtIn(l: List<JsonObject>) = l.filter { it.str("source") == MapStyle.OSM_SOURCE && it.str("source-layer") == MapsforgeToMvt.TRAILS }
        assertTrue(builtIn(layers(trails = true)).isNotEmpty())
        assertTrue(builtIn(layers(trails = false)).isEmpty())
    }

    @Test
    fun onlineTrailOverlayOnlyZoomedIn() {
        val style = MapStyle(BasicLayers.layers, BasicLayers.GLYPHS)
        val base = app.maparound.map.BaseMap.Online(app.maparound.map.TileSources.builtIn.first())
        val json = kotlinx.serialization.json.Json.parseToJsonElement(style.build(base, TileService(), trailOverlay = true)).jsonObject
        val overlay = json["layers"]!!.jsonArray.map { it.jsonObject }.first { it.id == "trails-online" }
        assertEquals(MapStyle.TRAILS_MIN_ZOOM.toDouble(), overlay["minzoom"]!!.jsonPrimitive.doubleOrNull)
    }

    @Test
    fun hybridDropsLandCover() {
        val xml = topo() ?: return
        val style = MapStyle(ThemeStyle.convert(xml).layers, BasicLayers.GLYPHS)
        val base = app.maparound.map.BaseMap.Offline(
            maps = emptyList(), theme = app.maparound.map.MapThemes.TOPO, categories = null, contours = false, hillshade = false, terrainVersion = 0, trails = false,
            trailsVersion = 0, background = app.maparound.data.Prefs.BACKGROUND_SATELLITE, vectorOnTop = true, rasterMaps = emptyList(),
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(style.build(base, TileService())).jsonObject
        val layers = json["layers"]!!.jsonArray.map { it.jsonObject }
        assertTrue(layers.none { it["metadata"]?.jsonObject?.get(ThemeStyle.AREA_FLAG) != null })
        assertTrue(layers.any { it.type == "line" && it.str("source") == MapStyle.OSM_SOURCE })
        // Sources not in the style (contours, trails) have no layers.
        val sources = json["sources"]!!.jsonObject.keys
        assertTrue(layers.all { it.str("source") == null || it.str("source") in sources })
    }
}
