package app.maparound.stats

import app.maparound.i18n.T
import app.maparound.tools.MapGrid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

// Plain Kotlin: the data view's weather (Open-Meteo's forecasts, RainViewer's rain radar). Unit-tested.

/** The weather now at a point (Open-Meteo's "current"): °C, km/h, degrees (where it comes from), mm, %. */
data class WeatherNow(
    val lat: Double,
    val lon: Double,
    val tempC: Double? = null,
    val windKmh: Double? = null,
    val windFrom: Double? = null,
    val gustKmh: Double? = null,
    val code: Int? = null,
    val precipMm: Double? = null,
    val cloudPct: Int? = null,
    val feelsC: Double? = null,
    val humidityPct: Int? = null,
)

/** An hour of the forecast ([timeMillis]: its start, UTC). */
data class HourWeather(val timeMillis: Long, val tempC: Double?, val precipMm: Double?, val chancePct: Int?, val code: Int?)

/** A day of the forecast ([dayMillis]: its local midnight as if it were UTC, for its date). */
data class DayWeather(
    val dayMillis: Long,
    val code: Int?,
    val minC: Double?,
    val maxC: Double?,
    val precipMm: Double?,
    val chancePct: Int?,
    val windMaxKmh: Double?,
    val sunriseMillis: Long?,
    val sunsetMillis: Long?,
)

/** A place's forecast: now, the coming hours and days; [utcOffsetSeconds]: its clocks' offset. */
data class Forecast(
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val utcOffsetSeconds: Int,
    val now: WeatherNow?,
    val hours: List<HourWeather>,
    val days: List<DayWeather>,
)

/** What the weather points show. */
enum class WeatherLayer {
    TEMPERATURE, WIND;

    val title: String get() = if (this == TEMPERATURE) T.live.temperatureNow else T.live.windNow
}

object Weather {
    /** A weather point's tap key: "data:w:31.75,35.25". */
    const val TAP = "data:w:"

    /** Open-Meteo's forecast API (the default; Settings → Sources can give another). */
    const val OPEN_METEO = "https://api.open-meteo.com/v1/forecast"

    /** About this many points over the view. */
    private const val POINTS = 40

    /** Never more than this many (a very tall or wide view). */
    const val MAX_POINTS = 120

    /** Degrees between the points: they're no closer than the forecasts' own grid (a few km). */
    private val STEPS = doubleArrayOf(0.05, 0.1, 0.2, 0.25, 0.5, 1.0, 2.0, 2.5, 5.0, 10.0, 15.0, 20.0, 30.0)

    /** Temperatures' classes: their upper bounds in °C. */
    private val TEMP_BREAKS = doubleArrayOf(-10.0, 0.0, 10.0, 20.0, 25.0, 30.0, 35.0)
    private val TEMP_COLORS = listOf("#313695", "#4575b4", "#91bfdb", "#a6d96a", "#fee08b", "#fdae61", "#f46d43", "#a50026")

    /** Wind's classes: their upper bounds in km/h (calm, light, breezy, strong, gale). */
    private val WIND_BREAKS = doubleArrayOf(5.0, 20.0, 40.0, 60.0)
    private val WIND_COLORS = listOf("#edf8fb", "#b3cde3", "#8c96c6", "#8856a7", "#810f7c")

    /**
     * Where to ask for the weather over [view]: a lattice of whole steps of latitude and longitude
     * (so a moved view asks again only for the new points), about [POINTS] of them, up to [MAX_POINTS].
     */
    fun grid(view: MapGrid.View): List<Pair<Double, Double>> {
        val south = view.south.coerceIn(-85.0, 85.0)
        val north = view.north.coerceIn(-85.0, 85.0)
        if (north <= south) return emptyList()
        var west = view.west
        var east = view.east
        if (east < west) east += 360.0
        if (east - west >= 360.0) { west = -180.0; east = 180.0 }
        val mid = (south + north) / 2 * PI / 180
        val shrink = cos(mid).coerceAtLeast(0.2)
        val area = (east - west) * shrink * (north - south)
        val latStep = nice(sqrt(area / POINTS))
        val lonStep = nice(latStep / shrink)
        val points = ArrayList<Pair<Double, Double>>()
        var i = floor(south / latStep).toLong()
        while (i * latStep <= north + 1e-9) {
            val lat = round4(i * latStep)
            if (lat in south..north) {
                var j = ceil(west / lonStep).toLong()
                while (j * lonStep <= east + 1e-9) {
                    var lon = j * lonStep
                    if (lon > 180.0) lon -= 360.0
                    points += lat to round4(lon)
                    if (points.size >= MAX_POINTS) return points
                    j++
                }
            }
            i++
        }
        return points
    }

    /** The step nearest [v] (by ratio). */
    private fun nice(v: Double): Double = STEPS.minBy { kotlin.math.abs(kotlin.math.ln(it / v.coerceAtLeast(1e-9))) }

    private fun round4(v: Double) = (v * 1e4).roundToLong() / 1e4

    private const val NOW_FIELDS = "temperature_2m,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,cloud_cover"

    /** The weather now at [points] (one request; Open-Meteo answers a list for several). */
    fun nowUrl(base: String, points: List<Pair<Double, Double>>): String =
        "$base?latitude=${points.joinToString(",") { fmt(it.first) }}&longitude=${points.joinToString(",") { fmt(it.second) }}" +
            "&current=$NOW_FIELDS&wind_speed_unit=kmh&timezone=GMT"

    /** The answer to [nowUrl]: each point's weather, in [points]' order (the asked places, not the model's grid). */
    fun parseNow(json: String, points: List<Pair<Double, Double>>): List<WeatherNow> {
        val root = Json.parseToJsonElement(json)
        val items = when (root) {
            is JsonArray -> root.map { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return items.zip(points).mapNotNull { (o, p) -> o?.let { current(it["current"] as? JsonObject, p.first, p.second) } }
    }

    private fun current(c: JsonObject?, lat: Double, lon: Double): WeatherNow? {
        c ?: return null
        return WeatherNow(
            lat, lon,
            tempC = c.num("temperature_2m"),
            windKmh = c.num("wind_speed_10m"),
            windFrom = c.num("wind_direction_10m"),
            gustKmh = c.num("wind_gusts_10m"),
            code = c.num("weather_code")?.roundToInt(),
            precipMm = c.num("precipitation"),
            cloudPct = c.num("cloud_cover")?.roundToInt(),
            feelsC = c.num("apparent_temperature"),
            humidityPct = c.num("relative_humidity_2m")?.roundToInt(),
        )
    }

    /** A place's forecast: now, 48 hours, 7 days, in its own time zone (times as Unix seconds). */
    fun forecastUrl(base: String, lat: Double, lon: Double): String =
        "$base?latitude=${fmt(lat)}&longitude=${fmt(lon)}" +
            "&current=$NOW_FIELDS,apparent_temperature,relative_humidity_2m" +
            "&hourly=temperature_2m,precipitation,precipitation_probability,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,sunrise,sunset" +
            "&wind_speed_unit=kmh&timezone=auto&forecast_days=7&forecast_hours=48&timeformat=unixtime"

    fun parseForecast(json: String, lat: Double, lon: Double): Forecast {
        val o = Json.parseToJsonElement(json) as JsonObject
        val offset = o.num("utc_offset_seconds")?.roundToInt() ?: 0
        val h = o["hourly"] as? JsonObject
        val hours = h?.let {
            val times = it.longs("time")
            val temp = it.nums("temperature_2m")
            val precip = it.nums("precipitation")
            val chance = it.nums("precipitation_probability")
            val code = it.nums("weather_code")
            times.indices.map { i ->
                HourWeather(times[i] * 1000, temp.getOrNull(i), precip.getOrNull(i), chance.getOrNull(i)?.roundToInt(), code.getOrNull(i)?.roundToInt())
            }
        }.orEmpty()
        val d = o["daily"] as? JsonObject
        val days = d?.let {
            val times = it.longs("time")
            val code = it.nums("weather_code")
            val max = it.nums("temperature_2m_max")
            val min = it.nums("temperature_2m_min")
            val sum = it.nums("precipitation_sum")
            val chance = it.nums("precipitation_probability_max")
            val wind = it.nums("wind_speed_10m_max")
            val rise = it.longs("sunrise")
            val set = it.longs("sunset")
            times.indices.map { i ->
                DayWeather(
                    (times[i] + offset) * 1000, code.getOrNull(i)?.roundToInt(), min.getOrNull(i), max.getOrNull(i),
                    sum.getOrNull(i), chance.getOrNull(i)?.roundToInt(), wind.getOrNull(i),
                    rise.getOrNull(i)?.takeIf { s -> s > 0 }?.times(1000), set.getOrNull(i)?.takeIf { s -> s > 0 }?.times(1000),
                )
            }
        }.orEmpty()
        return Forecast(lat, lon, o.num("elevation"), offset, current(o["current"] as? JsonObject, lat, lon), hours, days)
    }

    // ---- classes and the map ----

    /** °F when heights are in feet (the US's units); °C elsewhere. */
    fun fahrenheit(): Boolean = app.maparound.i18n.Units.current.height == app.maparound.i18n.HeightUnit.FT

    /** A temperature as shown: "12°" (in °F where they're used). */
    fun degrees(c: Double, fahrenheit: Boolean = fahrenheit()): String =
        "${(if (fahrenheit) c * 9 / 5 + 32 else c).roundToInt()}°"

    fun classOf(layer: WeatherLayer, w: WeatherNow): Int? = when (layer) {
        WeatherLayer.TEMPERATURE -> w.tempC?.let { step(it, TEMP_BREAKS) }
        WeatherLayer.WIND -> w.windKmh?.let { step(it, WIND_BREAKS) }
    }

    /** The classes' legend: temperatures in °C or °F, wind in the speed unit chosen. */
    fun classes(layer: WeatherLayer): List<StatClass> {
        val (breaks, colors) = when (layer) {
            WeatherLayer.TEMPERATURE -> TEMP_BREAKS to TEMP_COLORS
            WeatherLayer.WIND -> WIND_BREAKS to WIND_COLORS
        }
        val f = fahrenheit()
        fun v(x: Double) = when (layer) {
            WeatherLayer.TEMPERATURE -> degrees(x, f)
            WeatherLayer.WIND -> windNumber(x).toString()
        }
        val unit = if (layer == WeatherLayer.WIND) " " + app.maparound.i18n.Units.current.speed.label else ""
        return (0..breaks.size).map { i ->
            val label = when (i) {
                0 -> "<${v(breaks[0])}"
                breaks.size -> "${v(breaks.last())}+"
                else -> "${v(breaks[i - 1])}–${v(breaks[i])}"
            } + if (i == breaks.size) unit else ""
            StatClass(app.maparound.i18n.ltr(label), colors[i])
        }
    }

    /** A wind speed in the unit chosen, as a number. */
    fun windNumber(kmh: Double): Int = (kmh / 3.6 * app.maparound.i18n.Units.current.speed.perMps).roundToInt()

    /**
     * The points as GeoJSON: each coloured by its class and labelled ("12°"; wind: its speed, "15",
     * and `rot`, the way it blows for its arrow, degrees from north), without the [hidden] classes.
     */
    fun geoJson(points: List<WeatherNow>, layer: WeatherLayer, hidden: Set<Int>): String {
        val classes = classes(layer)
        val f = fahrenheit()
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (w in points) {
            val c = classOf(layer, w) ?: continue
            if (c in hidden) continue
            val label = when (layer) {
                WeatherLayer.TEMPERATURE -> degrees(w.tempC ?: continue, f)
                WeatherLayer.WIND -> windNumber(w.windKmh ?: continue).toString()
            }
            // The wind's arrow points where it blows to: away from where it comes from.
            val rot = w.windFrom?.takeIf { layer == WeatherLayer.WIND && (w.windKmh ?: 0.0) >= 1 }?.let { ((it + 180) % 360).roundToInt() }
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":{"type":"Point","coordinates":[""")
                .append(w.lon).append(',').append(w.lat)
                .append("""]},"properties":{"tap":"$TAP${w.lat},${w.lon}","color":"${classes[c].color}","label":""")
                .append(JsonPrimitive(label).toString())
            if (rot != null) sb.append(""","rot":""").append(rot)
            sb.append("}}")
        }
        return sb.append("]}").toString()
    }

    /** A tapped point's place, from its tap key. */
    fun placeOf(key: String): Pair<Double, Double>? {
        val parts = key.removePrefix(TAP).split(',')
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /**
     * What a WMO weather code says, as one of [app.maparound.i18n.LiveTexts.weatherKinds]: clear,
     * partly cloudy, cloudy, fog, drizzle, rain, freezing rain, snow, rain showers, snow showers,
     * thunderstorm.
     */
    fun kindOf(code: Int): Int = when (code) {
        0 -> 0
        1, 2 -> 1
        3 -> 2
        45, 48 -> 3
        51, 53, 55 -> 4
        61, 63, 65 -> 5
        56, 57, 66, 67 -> 6
        71, 73, 75, 77 -> 7
        80, 81, 82 -> 8
        85, 86 -> 9
        95, 96, 99 -> 10
        else -> 2
    }

    private fun step(v: Double, breaks: DoubleArray): Int = breaks.indexOfFirst { v < it }.let { if (it < 0) breaks.size else it }

    private fun fmt(v: Double): String = ((v * 1e4).roundToLong() / 1e4).toString()

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.nums(key: String): List<Double?> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()

    private fun JsonObject.longs(key: String): List<Long> =
        (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull ?: 0L }.orEmpty()
}

/** RainViewer's rain radar: the past two hours, a picture every ten minutes, as map tiles to zoom 7. */
object RainRadar {
    /** The list of the radar's pictures. */
    const val FRAMES = "https://api.rainviewer.com/public/weather-maps.json"

    /** Its tiles go to here (deeper ones say "Zoom Level Not Supported"); the map enlarges them. */
    const val MAX_ZOOM = 7

    /** One picture: when, and its tiles' address ({z}/{x}/{y}). */
    data class Frame(val timeMillis: Long, val tiles: String)

    /** The pictures in [json], oldest first. */
    fun parse(json: String): List<Frame> {
        val o = Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val host = (o["host"] as? JsonPrimitive)?.content ?: return emptyList()
        val past: List<JsonElement> = ((o["radar"] as? JsonObject)?.get("past") as? JsonArray) ?: emptyList()
        return past.mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val time = (f["time"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val path = (f["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            // 256 px tiles, colour scheme 2 (its "Universal Blue", the one it keeps), smoothed, with snow.
            Frame(time * 1000, "$host$path/256/{z}/{x}/{y}/2/1_1.png")
        }.sortedBy { it.timeMillis }
    }

    /** [frame] as a map layer. */
    fun source(frame: Frame) = app.maparound.map.OnlineSource(
        id = "rain_radar_${frame.timeMillis / 1000}",
        name = "Rain radar",
        urlTemplate = frame.tiles,
        maxZoom = MAX_ZOOM,
        attribution = "Radar © RainViewer",
        subdomains = emptyList(),
        transparent = true,
    )
}
