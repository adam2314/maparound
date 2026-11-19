package app.maparound.stats

import app.maparound.i18n.HeightUnit
import app.maparound.i18n.UnitSystem
import app.maparound.i18n.Units
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weather maps: the pipeline's manifest read, what the web map is told to draw, the legends. */
class WeatherMapTest {
    @AfterTest fun metric() = Units.set(UnitSystem())

    // As weather.py writes it (two steps, listed out of order here).
    private val manifest = """
        {"v": 1, "run": "2026-11-18T06:00Z", "made": "2026-11-18T14:23Z",
         "grid": {"width": 720, "height": 361, "west": -180, "north": 90, "step": 0.5},
         "channels": {"wt": ["u", "v", "t"], "cp": ["cloud", "precip", "pressure"]},
         "ranges": {"u": [-40.0, 40.0], "v": [-40.0, 40.0], "t": [-50.0, 50.0],
                    "cloud": [0.0, 100.0], "precip": [0.0, 20.0], "pressure": [950.0, 1050.0]},
         "precipScale": "sqrt",
         "steps": [
           {"hour": 6, "time": "2026-11-18T12:00Z", "wt": "wt_f006.png", "cp": "cp_f006.png"},
           {"hour": 3, "time": "2026-11-18T09:00Z", "wt": "wt_f003.png", "cp": "cp_f003.png"}
         ]}
    """.trimIndent()

    private val run6 = isoMillis("2026-11-18T06:00:00Z")!!
    private val hour = 3_600_000L

    @Test fun readsTheManifest() {
        val run = assertNotNull(WeatherMap.parse(manifest))
        assertEquals(run6, run.runMillis)
        assertEquals(listOf(run6 + 3 * hour, run6 + 6 * hour), run.steps.map { it.timeMillis })
        assertEquals(listOf("wt_f003.png", "wt_f006.png"), run.steps.map { it.wt })
        assertEquals("cp_f003.png", run.steps[0].cp)
        assertEquals(-50.0 to 50.0, run.ranges["t"])
        assertEquals(0.0 to 20.0, run.ranges["precip"])
    }

    @Test fun refusesWhatIsntAManifest() {
        assertNull(WeatherMap.parse("not json"))
        assertNull(WeatherMap.parse("[]"))
        assertNull(WeatherMap.parse(manifest.replace(Regex(""""steps": \[[^]]*]"""), """"steps": []""")))
        assertNull(WeatherMap.parse(manifest.replace(""""width": 720, """, "")))
        // A step without its pictures is left out, the rest kept.
        val one = WeatherMap.parse(manifest.replace(""", "wt": "wt_f006.png"""", ""))
        assertEquals(listOf("wt_f003.png"), one?.steps?.map { it.wt })
    }

    @Test fun nearestHourToNow() {
        val run = WeatherMap.parse(manifest)!!
        assertEquals(0, WeatherMap.nearest(run, run6))
        assertEquals(0, WeatherMap.nearest(run, run6 + 4 * hour))
        assertEquals(1, WeatherMap.nearest(run, run6 + 5 * hour))
        assertEquals(1, WeatherMap.nearest(run, run6 + 30 * hour))
    }

    @Test fun specForTheWebMap() {
        val run = WeatherMap.parse(manifest)!!
        val spec = Json.parseToJsonElement(WeatherMap.spec("https://example.org/wx", run, 1, WeatherMapField.PRECIP, wind = true)).jsonObject
        assertEquals("https://example.org/wx/wt_f006.png", spec["wt"]!!.jsonPrimitive.content)
        assertEquals("https://example.org/wx/cp_f006.png", spec["cp"]!!.jsonPrimitive.content)
        assertEquals(720, spec["grid"]!!.jsonObject["width"]!!.jsonPrimitive.int)
        assertEquals(-40.0, spec["ranges"]!!.jsonObject["u"]!!.jsonArray[0].jsonPrimitive.double)
        assertTrue(spec["wind"]!!.jsonPrimitive.boolean)
        val field = spec["field"]!!.jsonObject
        assertEquals("cp", field["source"]!!.jsonPrimitive.content)
        assertEquals(1, field["channel"]!!.jsonPrimitive.int)
        assertEquals(listOf(0.0, 20.0), field["range"]!!.jsonArray.map { it.jsonPrimitive.double })
        assertTrue(field["sqrt"]!!.jsonPrimitive.boolean)
        val stops = field["stops"]!!.jsonArray.map { it.jsonArray }
        assertEquals(WeatherMapField.PRECIP.stops.size, stops.size)
        assertTrue(stops.all { s -> Regex("#[0-9a-f]{6}").matches(s[1].jsonPrimitive.content) })
        assertEquals(stops.map { it[0].jsonPrimitive.double }, stops.map { it[0].jsonPrimitive.double }.sorted())

        // A base ending in "/", the step held in range, no field: only the wind.
        val wind = Json.parseToJsonElement(WeatherMap.spec("https://example.org/wx/", run, 9, null, wind = true)).jsonObject
        assertEquals("https://example.org/wx/wt_f006.png", wind["wt"]!!.jsonPrimitive.content)
        assertNull(wind["field"])
        // Clouds: the temperature's channel isn't theirs, and the particles are dark over them.
        val clouds = Json.parseToJsonElement(WeatherMap.spec("b/", run, 0, WeatherMapField.CLOUDS, wind = false)).jsonObject
        assertFalse(clouds["wind"]!!.jsonPrimitive.boolean)
        assertEquals(0, clouds["field"]!!.jsonObject["channel"]!!.jsonPrimitive.int)
        assertEquals("#1f2a44", clouds["field"]!!.jsonObject["particles"]!!.jsonPrimitive.content)
        val t = Json.parseToJsonElement(WeatherMap.spec("b/", run, 0, WeatherMapField.TEMPERATURE, wind = true)).jsonObject["field"]!!.jsonObject
        assertEquals("wt", t["source"]!!.jsonPrimitive.content)
        assertEquals(2, t["channel"]!!.jsonPrimitive.int)
        assertEquals(listOf(-50.0, 50.0), t["range"]!!.jsonArray.map { it.jsonPrimitive.double })
    }

    @Test fun legends() {
        val t = WeatherMap.legend(WeatherMapField.TEMPERATURE)
        assertEquals(listOf("-30°", "-15°", "0°", "12°", "24°", "36°", "44°"), t.map { it.label })
        assertTrue(t.all { c -> WeatherMapField.TEMPERATURE.stops.any { it.second == c.color } })
        assertEquals(listOf("40%", "70%", "100%"), WeatherMap.legend(WeatherMapField.CLOUDS).map { it.label })
        val rain = WeatherMap.legend(WeatherMapField.PRECIP).map { it.label }
        assertEquals("0.1", rain.first())
        assertEquals("20 mm/h", rain.last())
        // Where heights are in feet, temperatures are in °F.
        Units.set(UnitSystem(height = HeightUnit.FT))
        assertEquals("32°", WeatherMap.legend(WeatherMapField.TEMPERATURE)[2].label)
    }
}
