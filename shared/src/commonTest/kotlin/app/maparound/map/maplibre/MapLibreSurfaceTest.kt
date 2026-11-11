package app.maparound.map.maplibre

import app.maparound.data.MapPos
import app.maparound.data.Prefs
import app.maparound.map.BaseMap
import app.maparound.map.MapListener
import app.maparound.map.OnlineSource
import app.maparound.map.PlaceMarker
import app.maparound.routing.GeoPoint
import app.maparound.sensors.GeoFix
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLibreSurfaceTest {
    private class FakeMap : NativeMap {
        val styles = ArrayList<String>()
        val data = HashMap<String, String>()
        var cam = NativeCamera(0.0, 0.0, 2.0, 0.0)
        var cbs: NativeMapCallbacks? = null
        var fitted: List<Double>? = null

        override fun setStyle(json: String) {
            styles += json
        }

        override fun setGeoJson(sourceId: String, json: String) {
            data[sourceId] = json
        }

        override fun camera() = cam
        override fun viewHeight() = 800.0

        override fun moveCamera(lat: Double, lon: Double, zoom: Double, animated: Boolean) {
            cam = NativeCamera(lat, lon, zoom, cam.bearing)
        }

        override fun fitBounds(south: Double, west: Double, north: Double, east: Double, animated: Boolean) {
            fitted = listOf(south, west, north, east)
        }

        override fun setCallbacks(callbacks: NativeMapCallbacks?) {
            cbs = callbacks
        }
    }

    private val style = MapStyle(BasicLayers.layers, BasicLayers.GLYPHS)

    private fun layers(json: String) = Json.parseToJsonElement(json).jsonObject["layers"]!!.jsonArray.map { it.jsonObject }
    private fun sources(json: String) = Json.parseToJsonElement(json).jsonObject["sources"]!!.jsonObject

    private fun offline(background: String = Prefs.BACKGROUND_NONE, vectorOnTop: Boolean = true) = BaseMap.Offline(
        maps = emptyList(), topo = true, contours = false, hillshade = false, terrainVersion = 0,
        trails = false, trailsVersion = 0, background = background, vectorOnTop = vectorOnTop, rasterMaps = emptyList(),
    )

    @Test
    fun offlineStyleHasTheVectorTilesAndEveryOverlay() {
        val json = style.build(offline(), TileService().apply { baseUrl = "http://127.0.0.1:5000" })
        val src = sources(json)
        assertEquals("http://127.0.0.1:5000/osm/0/{z}/{x}/{y}.pbf", src["osm"]!!.jsonObject["tiles"]!!.jsonArray[0].jsonPrimitive.content)
        for (id in MapStyle.OVERLAY_SOURCES) assertNotNull(src[id], id)
        val ids = layers(json).map { it["id"]!!.jsonPrimitive.content }
        assertTrue("forest" in ids && "roads" in ids)
        // Overlays above the map, the pin on top.
        assertTrue(ids.indexOf("roads") < ids.indexOf("route-line"))
        assertEquals("pin", ids.last())
        assertTrue(ids.count { it.startsWith(MapStyle.TAP_PREFIX) } >= 4)
    }

    @Test
    fun satelliteHybridKeepsLinesAndLabelsOnly() {
        val hybrid = layers(style.build(offline(Prefs.BACKGROUND_SATELLITE), TileService()))
        val ids = hybrid.map { it["id"]!!.jsonPrimitive.content }
        assertTrue("satellite" in ids && "roads" in ids && "places" in ids)
        assertTrue("forest" !in ids && "land" !in ids)
        val imageryOnly = layers(style.build(offline(Prefs.BACKGROUND_SATELLITE, vectorOnTop = false), TileService()))
        assertTrue(imageryOnly.none { it["source"]?.jsonPrimitive?.content == "osm" })
    }

    @Test
    fun onlineSubdomainsBecomeSeparateUrls() {
        val src = OnlineSource("t", "Test", "https://{s}.tiles.example/{z}/{x}/{y}.png", attribution = "©")
        val json = style.build(BaseMap.Online(src), TileService())
        val tiles = sources(json)["online"]!!.jsonObject["tiles"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("https://a.tiles.example/{z}/{x}/{y}.png", "https://b.tiles.example/{z}/{x}/{y}.png", "https://c.tiles.example/{z}/{x}/{y}.png"), tiles)
    }

    @Test
    fun zoomLevelsAreMapsforgesOnTheOutside() {
        val native = FakeMap()
        val map = MapLibreSurface(native, TileService(), style)
        map.moveTo(MapPos(47.1, 9.5, 14))
        assertEquals(13.0, native.cam.zoom)
        assertEquals(MapPos(47.1, 9.5, 14), map.position())
        // Zooms in to at least 15 when further out, otherwise keeps the zoom.
        map.animateTo(47.2, 9.6, minZoom = 15)
        assertEquals(14.0, native.cam.zoom)
        map.animateTo(47.3, 9.7, minZoom = 12)
        assertEquals(14.0, native.cam.zoom)
        assertEquals(47.3, native.cam.lat)
    }

    @Test
    fun tapsReachTheRightListenerCall() {
        val native = FakeMap()
        val map = MapLibreSurface(native, TileService(), style)
        val calls = ArrayList<String>()
        map.listener = object : MapListener {
            override fun onUserGesture() { calls += "gesture" }
            override fun onLongPress(point: GeoPoint) { calls += "long ${point.lat}" }
            override fun onTap(point: GeoPoint): Boolean { calls += "tap ${point.lat}"; return false }
            override fun onWaypointTap(index: Int) { calls += "wp $index" }
            override fun onPartyMarkerTap(key: String) { calls += "party $key" }
            override fun onPlaceTap(id: String) { calls += "place $id" }
            override fun onMyLocationTap() { calls += "me" }
        }
        val cb = native.cbs!!
        cb.onTap(1.0, 2.0, null)
        cb.onTap(1.0, 2.0, "wp:2")
        cb.onTap(1.0, 2.0, "party:abc")
        cb.onTap(1.0, 2.0, "place:p1")
        cb.onTap(1.0, 2.0, "me")
        cb.onLongPress(3.0, 4.0)
        cb.onUserGesture()
        assertEquals(listOf("tap 1.0", "wp 2", "party abc", "place p1", "me", "long 3.0", "gesture"), calls)
        map.destroy()
        assertNull(native.cbs)
    }

    @Test
    fun overlaysBecomeGeoJson() {
        val native = FakeMap()
        val map = MapLibreSurface(native, TileService(), style)
        map.setPlaces(listOf(PlaceMarker("p1", 47.0, 9.0, "Hut", "⛺", 0xFF2E7D32.toInt())))
        val place = Json.parseToJsonElement(native.data[MapStyle.PLACES]!!).jsonObject["features"]!!.jsonArray[0].jsonObject
        val props = place["properties"]!!.jsonObject
        assertEquals("place:p1", props["tap"]!!.jsonPrimitive.content)
        assertEquals(listOf("place", "ff2e7d32", "⛺", "Hut"), props["icon"]!!.jsonPrimitive.content.split(MarkerIcons.SEP))
        assertEquals(listOf(9.0, 47.0), place["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() })

        map.setPlannedRoute(listOf(GeoPoint(47.0, 9.0), GeoPoint(47.1, 9.1)), listOf(GeoPoint(47.0, 9.0), GeoPoint(47.1, 9.1)))
        val route = Json.parseToJsonElement(native.data[MapStyle.ROUTE]!!).jsonObject["features"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, route.size)
        assertEquals("wp:1", route[2]["properties"]!!.jsonObject["tap"]!!.jsonPrimitive.content)

        map.updateLocation(GeoFix(47.0, 9.0, null, 25f, null, null, null, 0, 0, true))
        val me = Json.parseToJsonElement(native.data[MapStyle.ME]!!).jsonObject["features"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("accuracy", "dot"), me.map { it["properties"]!!.jsonObject["role"]!!.jsonPrimitive.content })
        map.updateLocation(null)
        assertEquals(GeoJson.empty, native.data[MapStyle.ME])

        // A one-point recording draws nothing yet.
        map.setRecording(listOf(GeoPoint(47.0, 9.0)))
        assertEquals(GeoJson.empty, native.data[MapStyle.RECORDING])
    }

    @Test
    fun theSameStyleIsNotSentTwice() = runTest {
        val native = FakeMap()
        val map = MapLibreSurface(native, TileService(), style)
        map.setBaseMap(offline())
        map.setBaseMap(offline())
        assertEquals(2, native.styles.size) // the empty start style, then the map
    }

    @Test
    fun accuracyCircleHasTheRightRadius() {
        val ring = GeoJson.circle(47.0, 9.0, 1000.0)
        for (p in ring) {
            val d = app.maparound.routing.Geo.distance(47.0, 9.0, p.lat, p.lon)
            assertTrue(kotlin.math.abs(d - 1000.0) < 5.0, "$d")
        }
    }

    @Test
    fun fontsComeFromTheAppWhenItHasThem() {
        val dir = "${app.maparound.shared.tempDir.trimEnd('/', '\\')}/maparound-fonts-test-${kotlin.random.Random.nextInt(1_000_000)}"
        val fs = app.maparound.shared.fileSystem
        try {
            fs.createDirectories(okio.Path.Companion.run { "$dir/Noto Sans Regular".toPath() })
            fs.write(okio.Path.Companion.run { "$dir/Noto Sans Regular/0-255.pbf".toPath() }) { writeUtf8("glyphs") }
            val t = TileService(fontsDir = dir).apply { baseUrl = "http://127.0.0.1:7" }
            assertEquals("http://127.0.0.1:7/fonts/{fontstack}/{range}.pbf", t.glyphsUrl)
            assertEquals("glyphs", t.respond("/fonts/Noto%20Sans%20Regular/0-255.pbf")!!.bytes.decodeToString())
            // A stack: the first font; a range the app leaves out: empty, not an error.
            assertEquals(6, t.respond("/fonts/Noto%20Sans%20Regular,Other/0-255.pbf")!!.bytes.size)
            assertEquals(0, t.respond("/fonts/Noto%20Sans%20Regular/19968-20223.pbf")!!.bytes.size)
            assertNull(t.respond("/fonts/..%2F..%2Fsecret/0-255.pbf"))
            assertNull(t.respond("/fonts/Noto%20Sans%20Regular/passwd"))
            // Without fonts in the app the style keeps its online fonts.
            assertNull(TileService().glyphsUrl)
            val json = style.build(null, t)
            assertTrue("\"http://127.0.0.1:7/fonts/{fontstack}/{range}.pbf\"" in json)
        } finally {
            runCatching { fs.deleteRecursively(okio.Path.Companion.run { dir.toPath() }) }
        }
    }

    @Test
    fun tileServiceAnswersOnlyTilePaths() {
        val t = TileService()
        assertNotNull(t.respond("/osm/0/3/4/2.pbf"))
        assertNull(t.respond("/osm/0/3/9/2.pbf")) // x outside zoom 3
        assertNull(t.respond("/osm/3/4/2.pbf"))
        assertNull(t.respond("/other/0/3/4/2.pbf"))
        assertEquals("application/x-protobuf", t.respond("/osm/0/3/4/2.pbf?x=1")!!.contentType)
    }
}
