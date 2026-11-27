package app.maparound.map.maplibre

import app.maparound.data.Prefs
import app.maparound.map.BaseMap
import app.maparound.map.MapThemes
import app.maparound.map.OnlineSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightStyleTest {
    private fun json(s: String) = Json.parseToJsonElement(s)
    private fun light(c: String) = NightStyle.lightness(NightStyle.parse(c)!!)

    @Test
    fun readsTheColourFormsStylesUse() {
        assertEquals(NightStyle.Rgba(1.0, 1.0, 1.0), NightStyle.parse("#fff"))
        assertEquals(NightStyle.Rgba(250 / 255.0, 247 / 255.0, 225 / 255.0), NightStyle.parse("#FAF7E1"))
        assertEquals(0.5, NightStyle.parse("#00000080")!!.a, 0.01)
        assertEquals(NightStyle.Rgba(239 / 255.0, 239 / 255.0, 239 / 255.0), NightStyle.parse("rgb(239,239,239)"))
        assertEquals(0.35, NightStyle.parse("rgba(255, 255, 255, 0.35)")!!.a, 1e-9)
        assertEquals(0.5, NightStyle.parse("rgb(239 239 239 / 50%)")!!.a, 1e-9)
        val blue = NightStyle.parse("hsl(210, 100%, 50%)")!!
        assertEquals(0.0, blue.r, 1e-9)
        assertEquals(0.5, blue.g, 1e-9)
        assertEquals(1.0, blue.b, 1e-9)
        assertEquals(0.5, NightStyle.parse("hsla(0,0%,100%,0.5)")!!.a, 1e-9)
        assertEquals(NightStyle.Rgba(0.0, 0.0, 0.0), NightStyle.parse("black"))
        assertNull(NightStyle.parse("black", names = false))
        assertNull(NightStyle.parse("Noto Sans Bold"))
        assertNull(NightStyle.parse("rgba(1,2,3,x)"))
    }

    @Test
    fun lightGoesDarkAndDarkLightHueAndAlphaKept() {
        val land = NightStyle.night(NightStyle.parse("#FAF7E1")!!)
        assertTrue(NightStyle.lightness(land) < 0.2, "land $land")
        val text = NightStyle.night(NightStyle.parse("#222222")!!)
        assertTrue(NightStyle.lightness(text) > 0.7, "text $text")
        // Light blue water stays blue, only dark.
        val water = NightStyle.night(NightStyle.parse("#8EBDF0")!!)
        assertTrue(water.b > water.r && water.b > water.g && NightStyle.lightness(water) < 0.35, "water $water")
        assertEquals(0.35, NightStyle.night(NightStyle.parse("rgba(255,255,255,0.35)")!!).a, 1e-9)
    }

    @Test
    fun coloursInsideExpressionsButNotMatchValues() {
        val zoomed = NightStyle.colors(json("""["interpolate",["linear"],["zoom"],10,"#ffffff",16,"#000000"]"""), top = true).jsonArray
        assertTrue(light(zoomed[4].jsonPrimitive.content) < 0.2)
        assertTrue(light(zoomed[6].jsonPrimitive.content) > 0.7)
        val stops = NightStyle.colors(json("""{"base":1,"stops":[[5,"#ffffff"]]}"""), top = true).jsonObject
        assertTrue(light(stops["stops"]!!.jsonArray[0].jsonArray[1].jsonPrimitive.content) < 0.2)
        // "white" here is a tag value, not a colour.
        val matched = NightStyle.colors(json("""["match",["get","colour"],"white","#000000","#ffffff"]"""), top = true).jsonArray
        assertEquals("white", matched[2].jsonPrimitive.content)
        assertEquals("get", matched[1].jsonArray[0].jsonPrimitive.content)
        // A name as the whole value is a colour.
        assertTrue(light((NightStyle.colors(JsonPrimitive("white"), top = true) as JsonPrimitive).content) < 0.2)
    }

    @Test
    fun rastersInvertedImageryDimmedShadingDarkOnly() {
        val raster = json("""{"id":"online","type":"raster","source":"online","paint":{"raster-opacity":0.9}}""").jsonObject
        val map = NightStyle.base(raster, imagery = false)["paint"]!!.jsonObject
        assertTrue(map["raster-brightness-min"]!!.jsonPrimitive.double > map["raster-brightness-max"]!!.jsonPrimitive.double)
        assertEquals(180.0, map["raster-hue-rotate"]!!.jsonPrimitive.double)
        assertEquals(0.9, map["raster-opacity"]!!.jsonPrimitive.double)
        val photo = NightStyle.base(raster, imagery = true)["paint"]!!.jsonObject
        assertNull(photo["raster-hue-rotate"])
        assertTrue(photo["raster-brightness-max"]!!.jsonPrimitive.double < 0.7)
        val shade = json("""{"id":"hillshade","type":"hillshade","source":"dem","paint":{"hillshade-shadow-color":"#5B4A33"}}""").jsonObject
        assertEquals("#000000", NightStyle.base(shade, imagery = false)["paint"]!!.jsonObject["hillshade-shadow-color"]!!.jsonPrimitive.content)
    }

    @Test
    fun overlayLabelsTurnLightOnDarkTheRestStays() {
        val label = json("""{"id":"grid-label","type":"symbol","source":"mr-grid","paint":{"text-color":"#1B1B1B","text-halo-color":"rgba(255,255,255,0.9)","text-halo-width":1.5}}""").jsonObject
        val night = NightStyle.overlay(label)["paint"]!!.jsonObject
        assertTrue(light(night["text-color"]!!.jsonPrimitive.content) > 0.7)
        assertTrue(light(night["text-halo-color"]!!.jsonPrimitive.content) < 0.2)
        assertEquals(1.5, night["text-halo-width"]!!.jsonPrimitive.double)
        // White arrows on the route's colour, and the route itself: as by day.
        val arrows = json("""{"id":"route-arrows","type":"symbol","paint":{"text-color":"#FFFFFF","text-halo-color":"#6A1B9A"}}""").jsonObject
        assertEquals(arrows, NightStyle.overlay(arrows))
        val route = json("""{"id":"route-casing","type":"line","paint":{"line-color":"#FFFFFF"}}""").jsonObject
        assertEquals(route, NightStyle.overlay(route))
    }

    // ---- the whole style ------------------------------------------------------------------------

    private val style = MapStyle(BasicLayers.layers, BasicLayers.GLYPHS)

    private fun layers(json: String) = Json.parseToJsonElement(json).jsonObject["layers"]!!.jsonArray.map { it.jsonObject }
    private fun List<JsonObject>.byId(id: String) = first { it["id"]!!.jsonPrimitive.content == id }
    private fun JsonObject.paint(key: String) = this["paint"]!!.jsonObject[key]!!.jsonPrimitive.content

    private fun offline(background: String = Prefs.BACKGROUND_NONE) = BaseMap.Offline(
        maps = emptyList(), theme = MapThemes.TOPO, categories = null, contours = false, hillshade = false, terrainVersion = 0,
        trails = false, background = background, vectorOnTop = true, rasterMaps = emptyList(),
    )

    @Test
    fun theOfflineMapAtNight() {
        val day = layers(style.build(offline(), TileService()))
        val night = layers(style.build(offline(), TileService(), night = true))
        assertEquals(day.map { it["id"] }, night.map { it["id"] })
        assertTrue(light(night.byId("land").paint("background-color")) < 0.2)
        assertTrue(light(night.byId("canvas").paint("background-color")) < 0.2)
        val places = night.byId("places")
        assertTrue(light(places.paint("text-color")) > 0.7)
        assertTrue(light(places.paint("text-halo-color")) < 0.2)
        // The route as by day.
        assertEquals(day.byId("route-line"), night.byId("route-line"))
        assertEquals(day.byId("route-casing"), night.byId("route-casing"))
    }

    @Test
    fun buildingsStandUpUnderTheNamesWhenAsked() {
        val flat = layers(style.build(offline(), TileService()))
        assertTrue(flat.none { it["id"]!!.jsonPrimitive.content == MapStyle.BUILDINGS_3D })
        val tilted = layers(style.build(offline(), TileService(), buildings3d = true))
        val ids = tilted.map { it["id"]!!.jsonPrimitive.content }
        val b = ids.indexOf(MapStyle.BUILDINGS_3D)
        assertTrue(b >= 0)
        // Under the first names and symbols, over the ground.
        assertTrue(b < tilted.indexOfFirst { it["type"]!!.jsonPrimitive.content == "symbol" })
        assertTrue(b > ids.indexOf("land"))
        assertEquals("fill-extrusion", tilted[b]["type"]!!.jsonPrimitive.content)
        // At night dark, as the rest of the map.
        val night = layers(style.build(offline(), TileService(), night = true, buildings3d = true)).byId(MapStyle.BUILDINGS_3D)
        assertTrue(light(night.paint("fill-extrusion-color")) < 0.3)
        // No offline map drawn (an online one): nothing to stand up.
        val online = OnlineSource("osm_std", "OSM", "https://tile.example/{z}/{x}/{y}.png", attribution = "©")
        assertTrue(layers(style.build(BaseMap.Online(online), TileService(), buildings3d = true)).none { it["id"]!!.jsonPrimitive.content == MapStyle.BUILDINGS_3D })
    }

    @Test
    fun satelliteDimmedStreetMapInverted() {
        val hybrid = layers(style.build(offline(Prefs.BACKGROUND_SATELLITE), TileService(), night = true))
        assertNull(hybrid.byId("satellite")["paint"]!!.jsonObject["raster-hue-rotate"])
        assertNotNull(hybrid.byId("satellite")["paint"]!!.jsonObject["raster-brightness-max"])

        val street = OnlineSource("osm_std", "OSM", "https://tile.example/{z}/{x}/{y}.png", attribution = "©")
        val inverted = layers(style.build(BaseMap.Online(street), TileService(), night = true)).byId("online")
        assertNotNull(inverted["paint"]!!.jsonObject["raster-hue-rotate"])
        val imagery = street.copy(id = "esri_imagery", kind = OnlineSource.Kind.IMAGERY)
        val dimmed = layers(style.build(BaseMap.Online(imagery), TileService(), night = true)).byId("online")
        assertNull(dimmed["paint"]!!.jsonObject["raster-hue-rotate"])
    }
}
