package app.maparound.map.maplibre

import app.maparound.map.BaseMap
import app.maparound.map.OnlineSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineVectorStyleTest {
    /** A cut of OpenFreeMap's Liberty style (2026-11): its sources, a fill, a road number and a place name. */
    private val liberty = Json.parseToJsonElement(
        """
        {"version":8,"sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm",
         "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
         "sources":{"ne2_shaded":{"type":"raster","tiles":["https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"],"maxzoom":6},
                    "openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},
         "layers":[
          {"id":"background","type":"background","paint":{"background-color":"rgb(239,239,239)"}},
          {"id":"water","type":"fill","source":"openmaptiles","source-layer":"water","paint":{"fill-color":"rgb(158,189,255)"}},
          {"id":"road_shield","type":"symbol","source":"openmaptiles","source-layer":"transportation_name",
           "layout":{"text-field":["to-string",["get","ref"]],"text-font":["Noto Sans Regular"]}},
          {"id":"label_city","type":"symbol","source":"openmaptiles","source-layer":"place",
           "layout":{"text-field":["case",["has","name:nonlatin"],["concat",["get","name:latin"],"\n",["get","name:nonlatin"]],["coalesce",["get","name_en"],["get","name"]]],
                     "text-font":["Noto Sans Bold"]}}
         ]}
        """.trimIndent(),
    ).jsonObject

    private fun layer(parts: OnlineVectorStyle.Parts, id: String) = parts.layers.first { it["id"]!!.jsonPrimitive.content == id }

    @Test
    fun itsSourcesAndLayersApartFromOurs() {
        val p = OnlineVectorStyle.parts(liberty, "en")
        assertEquals(setOf("v-ne2_shaded", "v-openmaptiles"), p.sources.keys)
        assertEquals(listOf("v-background", "v-water", "v-road_shield", "v-label_city"), p.layers.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("v-openmaptiles", layer(p, "v-water")["source"]!!.jsonPrimitive.content)
        assertEquals("https://tiles.openfreemap.org/sprites/ofm_f384/ofm", p.sprite!!.jsonPrimitive.content)
    }

    @Test
    fun namesInTheAppsLanguage() {
        // English: the style's own (Latin letters, and the name in its script beside them).
        val en = OnlineVectorStyle.parts(liberty, "en")
        assertTrue("name:nonlatin" in layer(en, "v-label_city")["layout"].toString())
        // Hebrew: the Hebrew name, else the English one, else the place's own; road numbers stay.
        for (code in listOf("he", "he-IL", "iw")) {
            val he = OnlineVectorStyle.parts(liberty, code)
            val field = layer(he, "v-label_city")["layout"]!!.jsonObject["text-field"].toString()
            assertEquals("""["coalesce",["get","name:he"],["get","name:en"],["get","name_en"],["get","name"]]""", field, code)
            assertEquals("""["to-string",["get","ref"]]""", layer(he, "v-road_shield")["layout"]!!.jsonObject["text-field"].toString())
            // The rest of the layout kept.
            assertTrue("Noto Sans Bold" in layer(he, "v-label_city")["layout"].toString())
        }
    }

    @Test
    fun drawnUnderOursWithItsSprite() {
        val style = MapStyle(BasicLayers.layers, "https://example.org/fonts/{fontstack}/{range}.pbf")
        val src = OnlineSource("ofm_liberty", "Liberty", "", attribution = "©", style = "https://tiles.openfreemap.org/styles/liberty")
        val json = Json.parseToJsonElement(style.build(BaseMap.Online(src, liberty.toString(), "he"), TileService())).jsonObject
        val sources = json["sources"]!!.jsonObject
        assertTrue("v-openmaptiles" in sources)
        assertFalse("online" in sources)
        assertEquals("https://tiles.openfreemap.org/sprites/ofm_f384/ofm", json["sprite"]!!.jsonPrimitive.content)
        // Our fonts (they have the same faces, with Hebrew).
        assertEquals("https://example.org/fonts/{fontstack}/{range}.pbf", json["glyphs"]!!.jsonPrimitive.content)
        val ids = json["layers"]!!.jsonArray.map { (it as JsonObject)["id"]!!.jsonPrimitive.content }
        // Its layers under ours (the route, the pin).
        assertTrue(ids.indexOf("v-label_city") < ids.indexOf(ids.first { it.startsWith("route") || it.startsWith("pin") }))
        // Until its style is read: nothing but the overlays (no raster tiles from an empty address).
        val waiting = Json.parseToJsonElement(style.build(BaseMap.Online(src), TileService())).jsonObject
        assertFalse(waiting["sources"]!!.jsonObject.keys.any { it == "online" || it.startsWith("v-") })
        assertFalse("sprite" in waiting)
    }
}
