package app.maparound.sources

import app.maparound.data.MemoryStore
import app.maparound.data.Prefs
import app.maparound.map.TileSources
import app.maparound.map.satelliteSource
import app.maparound.routing.GeoPoint
import app.maparound.search.SearchEngine
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.AfterTest
import kotlin.test.Test

class SourcesTest {
    @AfterTest
    fun backToDefaults() {
        Sources.set(emptyMap())
        Sources.regionPaths = emptyList()
    }

    @Test
    fun yoursThenTheAppsOrInstead() {
        assertEquals(SourceKind.SEARCH.builtIn, Sources.addresses(SourceKind.SEARCH))
        Sources.set(
            mapOf(
                SourceKind.SEARCH to SourceSetting.Custom(listOf("https://nominatim.example.org")),
                SourceKind.ROUTING to SourceSetting.Custom(listOf("https://brouter.example.org/brouter")),
                SourceKind.WIKIPEDIA to SourceSetting.Off,
            ),
        )
        // Search tries yours first, then the app's; routing goes to yours only.
        assertEquals(listOf("https://nominatim.example.org") + SourceKind.SEARCH.builtIn, Sources.addresses(SourceKind.SEARCH))
        assertEquals(listOf("https://brouter.example.org/brouter"), Sources.addresses(SourceKind.ROUTING))
        assertTrue(Sources.isOff(SourceKind.WIKIPEDIA))
        assertEquals(emptyList<String>(), Sources.addresses(SourceKind.WIKIPEDIA))
        // The search goes there, as Nominatim.
        val asked = ArrayList<String>()
        SearchEngine.online("Ein Gedi", GeoPoint(31.46, 35.39)) { url -> asked += url; """[{"display_name":"Ein Gedi","lat":"31.46","lon":"35.39"}]""" }
        assertTrue(asked.single(), asked.single().startsWith("https://nominatim.example.org/search?format=jsonv2"))
    }

    @Test
    fun regionsHaveTheirOwn() {
        assertEquals("usgs_topo", RegionalDefaults.of(SourceKind.MAP_LAYER, listOf("north-america/us/california", "north-america/us"))?.id)
        assertNull(RegionalDefaults.of(SourceKind.MAP_LAYER, listOf("europe/germany/bayern", "europe/germany")))
        assertNull(RegionalDefaults.of(SourceKind.MAP_LAYER, listOf("europe/france-ish")))
        // The automatic map layer follows where the map is.
        assertEquals(TileSources.DEFAULT_ID, TileSources.find(TileSources.AUTO_ID, emptyList())?.id)
        Sources.regionPaths = listOf("europe/switzerland")
        assertEquals("swisstopo", TileSources.find(TileSources.AUTO_ID, emptyList())?.id)
        // The satellite too, unless the user has their own.
        Sources.regionPaths = listOf("europe/france/corse", "europe/france")
        assertEquals("ign_ortho", satelliteSource.id)
        Sources.set(mapOf(SourceKind.SATELLITE to SourceSetting.Custom(listOf("https://sat.example.org/{z}/{x}/{y}.jpg"))))
        assertEquals("https://sat.example.org/{z}/{x}/{y}.jpg", satelliteSource.urlTemplate)
        Sources.set(emptyMap())
        Sources.regionPaths = emptyList()
        assertEquals("esri_imagery", satelliteSource.id)
    }

    @Test
    fun addressesAreChecked() {
        assertEquals("https://x.org/{z}/{x}/{y}.png", Sources.validate(SourceKind.TRAILS_OVERLAY, " https://x.org/{z}/{x}/{y}.png "))
        assertNull(Sources.validate(SourceKind.TRAILS_OVERLAY, "https://x.org/tiles.png"))
        assertNull(Sources.validate(SourceKind.ROUTING, "brouter.de"))
        assertEquals("https://brouter.de/brouter", Sources.validate(SourceKind.ROUTING, "https://brouter.de/brouter"))
        assertNull(Sources.validate(SourceKind.ELEVATION, "https://x.org/{z}/{x}/{y}.png"))
        assertNull(Sources.validate(SourceKind.PARTY, "https://mqtt.example.org"))
        assertNull(Sources.validate(SourceKind.WIKIPEDIA, "https://wikipedia.org"))
        assertEquals("https://t.org/12/2048/2047.png", Sources.tileAt("https://t.org/{z}/{x}/{y}.png", 0.01, 0.01, 12))
    }

    @Test
    fun keptAndGivenToTheApp() {
        val choices = mapOf(
            SourceKind.MAP_LIST to SourceSetting.Custom(listOf("https://maps.example.org/catalog.json")),
            SourceKind.SEARCH to SourceSetting.Off,
        )
        assertEquals(choices, Sources.decode(Sources.encode(choices)))
        // What isn't an address for it is dropped.
        assertEquals(emptyMap<SourceKind, SourceSetting>(), Sources.decode("""{"SATELLITE":{"custom":["not a template"]},"NOPE":{"off":true}}"""))
        val store = MemoryStore()
        Prefs(store).apply {
            setSource(SourceKind.SEARCH, SourceSetting.Off)
            setSource(SourceKind.ROUTING, SourceSetting.Custom(listOf("https://brouter.example.org/brouter")))
            setSource(SourceKind.ROUTING, SourceSetting.Default)
        }
        var given: Map<SourceKind, SourceSetting>? = null
        val again = Prefs(store, onSources = { given = it })
        assertEquals(mapOf<SourceKind, SourceSetting>(SourceKind.SEARCH to SourceSetting.Off), again.sources.value)
        assertEquals(again.sources.value, given)
        // Never set: the automatic map layer.
        assertEquals(TileSources.AUTO_ID, again.baseLayer.value)
    }
}
