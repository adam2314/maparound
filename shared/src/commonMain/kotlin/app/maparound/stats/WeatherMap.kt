package app.maparound.stats

import app.maparound.i18n.T
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

// Plain Kotlin: the data view's weather maps, NOAA's GFS forecast as MapAround's pipeline makes
// it (my-pipeline: weather.py, every 6 hours): the next 48 hours, every 3, as pictures of the whole
// world (each colour channel one field), drawn by the web map on the graphics card (weather.js):
// a field in smooth colours, the wind as moving particles. Unit-tested.

/** What colours the weather map: its picture and channel, its range's name, and its colour scale. */
enum class WeatherMapField(
    val source: String,
    val channel: Int,
    val rangeKey: String,
    /** The picture holds the value's square root (rain: so light rain shows). */
    val sqrt: Boolean,
    /** (value in the field's own unit, "#rrggbb", opacity), from low to high. */
    val stops: List<Triple<Double, String, Double>>,
    /** The wind's particles over it ("#rrggbb"): white, or dark where the field is light (clouds). */
    val particles: String = "#ffffff",
) {
    TEMPERATURE(
        "wt", 2, "t", false,
        listOf(
            Triple(-50.0, "#3b0f70", 0.62), Triple(-30.0, "#4b2fbf", 0.62), Triple(-15.0, "#2f6fd8", 0.6),
            Triple(-5.0, "#4fb0e6", 0.58), Triple(0.0, "#8fd6ef", 0.55), Triple(5.0, "#76c9a6", 0.55),
            Triple(12.0, "#6cc46a", 0.55), Triple(18.0, "#d7e34b", 0.57), Triple(24.0, "#f6c344", 0.58),
            Triple(30.0, "#f28b30", 0.6), Triple(36.0, "#d9472b", 0.62), Triple(44.0, "#9e1b2a", 0.64),
            Triple(50.0, "#5f0f2a", 0.66),
        ),
    ),
    CLOUDS(
        "cp", 0, "cloud", false,
        listOf(
            Triple(0.0, "#ffffff", 0.0), Triple(15.0, "#ffffff", 0.0), Triple(40.0, "#f2f4f7", 0.35),
            Triple(70.0, "#e3e7ed", 0.6), Triple(100.0, "#cfd5de", 0.78),
        ),
        particles = "#1f2a44",
    ),
    PRECIP(
        "cp", 1, "precip", true,
        listOf(
            Triple(0.0, "#000000", 0.0), Triple(0.08, "#9fd9ff", 0.0), Triple(0.12, "#9fd9ff", 0.55),
            Triple(1.0, "#4aa3ff", 0.7), Triple(3.0, "#2a5bd7", 0.78), Triple(6.0, "#7b3fd1", 0.82),
            Triple(10.0, "#d13fb5", 0.86), Triple(20.0, "#ff3f3f", 0.9),
        ),
    );

    val title: String get() = T.live.weatherMapTitle(this)
}

/** One run of the model as the pipeline published it: its steps, the grid and the fields' ranges. */
data class WeatherRun(
    val runMillis: Long,
    val steps: List<Step>,
    val gridJson: String,
    val rangesJson: String,
    val ranges: Map<String, Pair<Double, Double>>,
) {
    /** A forecast hour: when, and its two pictures (file names). */
    data class Step(val timeMillis: Long, val wt: String, val cp: String)
}

object WeatherMap {
    /** The pipeline's `weather` branch, which browsers can read (the default; Settings → Sources can give another). */
    const val BASE = "https://raw.githubusercontent.com/adam2314/my-pipeline/weather/"

    const val MANIFEST = "manifest.json"

    /** The run in a manifest, or null when it isn't one. */
    fun parse(json: String): WeatherRun? = runCatching {
        val o = Json.parseToJsonElement(json) as JsonObject
        val steps = (o["steps"] as JsonArray).mapNotNull { e ->
            val s = e as? JsonObject ?: return@mapNotNull null
            WeatherRun.Step(
                timeMillis = isoMillis(str(s, "time")?.let(::fixTime) ?: return@mapNotNull null) ?: return@mapNotNull null,
                wt = str(s, "wt") ?: return@mapNotNull null,
                cp = str(s, "cp") ?: return@mapNotNull null,
            )
        }
        val ranges = (o["ranges"] as JsonObject).mapValues { (_, v) ->
            val a = v as JsonArray
            ((a[0] as JsonPrimitive).doubleOrNull ?: 0.0) to ((a[1] as JsonPrimitive).doubleOrNull ?: 1.0)
        }
        WeatherRun(
            runMillis = str(o, "run")?.let(::fixTime)?.let(::isoMillis) ?: 0L,
            steps = steps.sortedBy { it.timeMillis },
            gridJson = o["grid"].toString(),
            rangesJson = o["ranges"].toString(),
            ranges = ranges,
        ).takeIf { it.steps.isNotEmpty() && (o["grid"] as? JsonObject)?.get("width")?.let { (it as JsonPrimitive).intOrNull } != null }
    }.getOrNull()

    /** "2026-11-18T09:00Z" (minutes only): with seconds, as ISO 8601 wants. */
    private fun fixTime(t: String): String = if (Regex("""T\d{2}:\d{2}Z$""").containsMatchIn(t)) t.removeSuffix("Z") + ":00Z" else t

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The step nearest [now] (the forecast for now). */
    fun nearest(run: WeatherRun, now: Long): Int = run.steps.indices.minByOrNull { abs(run.steps[it].timeMillis - now) } ?: 0

    /**
     * What the web map draws (weather.js): the step's pictures from [base], the grid, the ranges,
     * the field (its picture, channel and colour scale) or none, and the wind's particles or not.
     */
    fun spec(base: String, run: WeatherRun, step: Int, field: WeatherMapField?, wind: Boolean): String {
        val s = run.steps[step.coerceIn(run.steps.indices)]
        val dir = if (base.endsWith("/")) base else "$base/"
        return buildJsonObject {
            put("wt", dir + s.wt)
            put("cp", dir + s.cp)
            put("grid", Json.parseToJsonElement(run.gridJson))
            put("ranges", Json.parseToJsonElement(run.rangesJson))
            put("wind", wind)
            if (field != null) {
                put("field", buildJsonObject {
                    put("source", field.source)
                    put("channel", field.channel)
                    put("range", buildJsonArray {
                        val r = run.ranges[field.rangeKey] ?: (0.0 to 1.0)
                        add(JsonPrimitive(r.first))
                        add(JsonPrimitive(r.second))
                    })
                    put("sqrt", field.sqrt)
                    put("particles", field.particles)
                    put("stops", buildJsonArray {
                        for ((v, c, a) in field.stops) add(buildJsonArray {
                            add(JsonPrimitive(v))
                            add(JsonPrimitive(c))
                            add(JsonPrimitive(a))
                        })
                    })
                })
            }
        }.toString()
    }

    /** The legend: the scale's colours at round values, in °C or °F (temperature), %, mm/h. */
    fun legend(field: WeatherMapField): List<StatClass> {
        val f = Weather.fahrenheit()
        val shown: List<Double> = when (field) {
            WeatherMapField.TEMPERATURE -> listOf(-30.0, -15.0, 0.0, 12.0, 24.0, 36.0, 44.0)
            WeatherMapField.CLOUDS -> listOf(40.0, 70.0, 100.0)
            WeatherMapField.PRECIP -> listOf(0.12, 1.0, 3.0, 6.0, 10.0, 20.0)
        }
        return shown.map { v ->
            val stop = field.stops.minBy { abs(it.first - v) }
            val label = when (field) {
                WeatherMapField.TEMPERATURE -> Weather.degrees(v, f)
                WeatherMapField.CLOUDS -> "${v.roundToInt()}%"
                WeatherMapField.PRECIP -> if (v < 1) "0.1" else "${v.roundToInt()}" + if (v == 20.0) " mm/h" else ""
            }
            StatClass(app.maparound.i18n.ltr(label), stop.second)
        }
    }
}
