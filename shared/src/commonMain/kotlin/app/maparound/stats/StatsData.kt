package app.maparound.stats

import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.shared.fileSystem
import app.maparound.shared.net.httpGetAsync
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath

/**
 * A country's outline (GeoJSON geometry), names, where its name goes on the map, and what its card
 * says (Wikidata's, in tools/stats/make_countries.py): its area, capitals and official languages,
 * each named (English, Hebrew), the languages most spoken first.
 */
class Country(
    val iso: String,
    val nameEn: String,
    val nameHe: String,
    val geometry: String,
    /** Where its name goes (Natural Earth's point), or null for none. */
    val label: Pair<Double, Double>? = null,
    /** The (MapLibre) zoom its name shows from: the big ones first. */
    val labelZoom: Double = 4.0,
    val areaKm2: Double? = null,
    val capitals: List<Pair<String, String>> = emptyList(),
    val languages: List<Pair<String, String>> = emptyList(),
    /** Its Wikipedia articles' titles (English, Hebrew; "" where there's none), or null. */
    val wikipedia: Pair<String, String>? = null,
    /** Its flag's picture size in pixels (the app's flags/<ISO>.png), or null for none. */
    val flag: Pair<Int, Int>? = null,
) {
    /** Its name in Hebrew ([he]) where it has one, else in English. */
    fun name(he: Boolean): String = if (he && nameHe.isNotBlank()) nameHe else nameEn

    /** Its Wikipedia article: the Hebrew one when [he] and there is one, else the English. */
    fun wikipediaUrl(he: Boolean): String? {
        val (en, heTitle) = wikipedia ?: return null
        return when {
            he && heTitle.isNotBlank() -> app.maparound.search.Wikipedia.articleUrl("he", heTitle)
            en.isNotBlank() -> app.maparound.search.Wikipedia.articleUrl("en", en)
            heTitle.isNotBlank() -> app.maparound.search.Wikipedia.articleUrl("he", heTitle)
            else -> null
        }
    }

    companion object {
        /** One country from the file's entry ([o]: "en", "he", "g", "lp", "lz", "km2", "cap", "lang", "wp", "fl"). */
        fun of(iso: String, o: kotlinx.serialization.json.JsonObject): Country {
            fun names(k: String) = (o[k] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                val en = (a.getOrNull(0) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                en to ((a.getOrNull(1) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: en)
            }
            fun num(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            fun nums(k: String) = (o[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() }
            val lp = nums("lp")
            val fl = nums("fl")?.takeIf { it.size == 2 && it.all { v -> v > 0 } }
            val wp = (o["wp"] as? kotlinx.serialization.json.JsonArray)?.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
            return Country(
                iso = iso,
                nameEn = o["en"]?.jsonPrimitive?.content.orEmpty(),
                nameHe = o["he"]?.jsonPrimitive?.content.orEmpty(),
                geometry = o["g"].toString(),
                // Natural Earth's zooms are for 256-pixel tiles, MapLibre's for 512: one less.
                label = lp?.takeIf { it.size == 2 }?.let { it[0] to it[1] },
                labelZoom = ((num("lz") ?: 5.0) - 1).coerceAtLeast(0.0),
                areaKm2 = num("km2"),
                capitals = names("cap"),
                languages = names("lang"),
                wikipedia = wp?.takeIf { it.size == 2 && it.any(String::isNotBlank) }?.let { it[0] to it[1] },
                flag = fl?.let { it[0].toInt() to it[1].toInt() },
            )
        }
    }
}

/**
 * The data view's data: the countries' outlines (the app's own file, from Natural Earth), their
 * figures (Our World in Data) and Israel's localities (data.gov.il). Each is downloaded when first
 * needed and kept in [dir] for a week, and read from there when offline.
 */
class StatsData(
    private val dir: String,
    private val outlines: suspend () -> String,
    private val clock: () -> Long = ::nowMillis,
    /** The time zones' file (timezones.bin, the app's own), or null where there's none. */
    private val zones: (suspend () -> ByteArray?)? = null,
    /**
     * A zone's offset from UTC at a time, in seconds (the zone: an IANA name; null: the device's
     * own); null when it isn't known. The web app's comes from the browser.
     */
    private val zoneOffset: (zone: String?, epochMillis: Long) -> Int? = { _, _ -> null },
    /** Where the countries' flags are (the app's own files, "<ISO>.png"), or null where there are none. */
    private val flags: String? = null,
) {
    /** A country's flag's address, or null when it has none. */
    fun flagUrl(c: Country): String? = if (c.flag == null) null else flags?.let { "$it${c.iso}.png" }

    private val _countries = MutableStateFlow<Map<String, Country>?>(null)
    val countries: StateFlow<Map<String, Country>?> = _countries.asStateFlow()

    private val _figures = MutableStateFlow<Map<CountryIndicator, Map<String, Figure>>>(emptyMap())
    val figures: StateFlow<Map<CountryIndicator, Map<String, Figure>>> = _figures.asStateFlow()

    private val _localities = MutableStateFlow<List<Locality>?>(null)
    val localities: StateFlow<List<Locality>?> = _localities.asStateFlow()

    private val _timeZones = MutableStateFlow<List<ZoneArea>?>(null)
    val timeZones: StateFlow<List<ZoneArea>?> = _timeZones.asStateFlow()

    /** Whether there are time zones to show. */
    val hasTimeZones: Boolean get() = zones != null

    /** [zone]'s offset from UTC at [epochMillis] (seconds; null zone: the device's), or null. */
    fun zoneOffset(zone: String?, epochMillis: Long): Int? = zoneOffset.invoke(zone, epochMillis)

    /** What's being downloaded, or what failed; empty when nothing. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCountries() = lock.withLock {
        if (_countries.value != null) return@withLock
        runCatching {
            val root = json.parseToJsonElement(outlines()).jsonObject
            _countries.value = root.mapValues { (iso, v) -> Country.of(iso, v.jsonObject) }
        }
    }

    suspend fun loadTimeZones() = lock.withLock {
        if (_timeZones.value != null) return@withLock
        val bytes = runCatching { zones?.invoke() }.getOrNull() ?: return@withLock
        _timeZones.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TimeZones.decode(bytes) }.takeIf { it.isNotEmpty() }
    }

    suspend fun loadFigures(indicator: CountryIndicator, year: Int) = lock.withLock {
        if (indicator in _figures.value) return@withLock
        val csv = cached("owid-${indicator.slug}.csv", CountryStats.url(indicator), app.maparound.i18n.T.stats.gettingCountries)
            ?: return@withLock
        _figures.value = _figures.value + (indicator to CountryStats.parse(csv, year))
    }

    suspend fun loadLocalities() = lock.withLock {
        if (_localities.value != null) return@withLock
        val places = rows(IsraelLocalities.LOCALITIES)
        if (places.isEmpty()) return@withLock
        _localities.value = IsraelLocalities.parse(places, rows(IsraelLocalities.AGES), rows(IsraelLocalities.CLUSTERS))
    }

    /** A dataset's newest table's rows. */
    private suspend fun rows(dataset: String): List<kotlinx.serialization.json.JsonObject> {
        val getting = app.maparound.i18n.T.stats.gettingLocalities
        val id = cached("$dataset.package.json", IsraelLocalities.packageUrl(dataset), getting)?.let(IsraelLocalities::newestTable)
            ?: return emptyList()
        return cached("$dataset.json", IsraelLocalities.tableUrl(id), getting)?.let(IsraelLocalities::records).orEmpty()
    }

    /** [url]'s text: from the cache while fresh, else downloaded (and kept), else the cache however old. */
    private suspend fun cached(name: String, url: String, getting: String): String? {
        val file = "$dir/$name".toPath()
        val stamp = "$dir/$name.t".toPath()
        val fs = fileSystem
        val saved = runCatching { if (fs.exists(file)) fs.read(file) { readUtf8() } else null }.getOrNull()
        val savedAt = runCatching { if (fs.exists(stamp)) fs.read(stamp) { readUtf8() }.toLong() else 0L }.getOrDefault(0L)
        if (saved != null && clock() - savedAt < FRESH_MS) return saved
        _status.value = getting
        val fetched = runCatching { httpGetAsync(url, 60_000).decodeToString() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fetched != null) {
            runCatching {
                fs.createDirectories(dir.toPath())
                fs.write(file) { writeUtf8(fetched) }
                fs.write(stamp) { writeUtf8(clock().toString()) }
            }
            _status.value = ""
            return fetched
        }
        _status.value = if (saved == null) app.maparound.i18n.T.stats.couldntGet else ""
        return saved
    }

    // ---- live layers: kept in memory only, they're about now ----

    /** The weather now by place asked, and when it was got. */
    private val weatherNow = HashMap<Pair<Double, Double>, Pair<Long, WeatherNow>>()
    private val weatherLock = Mutex()

    /**
     * The weather now at [points] (Open-Meteo, or the user's server): those got in the last quarter
     * of an hour from memory, the rest asked for. Null when it's off, or none could be had.
     */
    suspend fun weatherNow(points: List<Pair<Double, Double>>): List<WeatherNow>? = weatherLock.withLock {
        val base = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.WEATHER) ?: return@withLock null
        val now = clock()
        val missing = points.filter { p -> weatherNow[p]?.let { now - it.first < WEATHER_FRESH_MS } != true }
        for (chunk in missing.chunked(WEATHER_CHUNK)) {
            val got = fetch(Weather.nowUrl(base, chunk))?.let { runCatching { Weather.parseNow(it, chunk) }.getOrNull() } ?: break
            for (w in got) weatherNow[w.lat to w.lon] = now to w
        }
        if (weatherNow.size > WEATHER_KEPT) weatherNow.entries.removeAll { now - it.value.first >= WEATHER_FRESH_MS }
        points.mapNotNull { weatherNow[it]?.second }.takeIf { it.isNotEmpty() || missing.isEmpty() }
    }

    /** A place's forecast, or null when it's off or couldn't be had. */
    suspend fun forecast(lat: Double, lon: Double): Forecast? {
        val base = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.WEATHER) ?: return null
        return fetch(Weather.forecastUrl(base, lat, lon))?.let { runCatching { Weather.parseForecast(it, lat, lon) }.getOrNull() }
    }

    /** The rain radar's pictures (the past two hours, oldest first), or null when it's off or couldn't be had. */
    suspend fun radarFrames(): List<RainRadar.Frame>? {
        val url = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.RAIN_RADAR) ?: return null
        return fetch(url)?.let { runCatching { RainRadar.parse(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
    }

    /** The street photos' map tiles, or null when they're off. */
    val photoTiles: String?
        get() = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.STREET_PHOTOS)?.let(StreetPhotos::tiles)

    /** A street photo's details, or null. */
    suspend fun photo(id: String): StreetPhotos.Picture? {
        val base = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.STREET_PHOTOS) ?: return null
        return fetch(StreetPhotos.pictureUrl(base, id))?.let { runCatching { StreetPhotos.parse(it) }.getOrNull() }
    }

    /** Whether the buses can be shown (their source isn't off). */
    val hasBuses: Boolean get() = !app.maparound.sources.Sources.isOff(app.maparound.sources.SourceKind.BUSES)

    /**
     * The buses in [area] in the feed's newest snapshot, with it; null when it's off or couldn't be
     * had (an area outside Israel: none).
     */
    suspend fun buses(area: app.maparound.tools.MapGrid.View): Pair<Buses.Snapshot, List<Buses.Bus>>? {
        val base = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.BUSES) ?: return null
        val snapshot = fetch(Buses.snapshotsUrl(base))?.let { runCatching { Buses.latestLoaded(it) }.getOrNull() } ?: return null
        val clipped = Buses.clip(area) ?: return snapshot to emptyList()
        val buses = fetch(Buses.locationsUrl(base, snapshot, clipped), 60_000)?.let { runCatching { Buses.parseLocations(it, snapshot) }.getOrNull() }
            ?: return null
        return snapshot to buses
    }

    /** The lines known by their reference, for the day they were asked on ("2026-11-18": its lines). */
    private val routes = HashMap<Long, Buses.Route?>()
    private var routesDay = ""
    private val routesLock = Mutex()

    /** [lineRefs]' lines on the day in Israel at [atMillis] (those already known from memory). */
    suspend fun routes(lineRefs: Set<Long>, atMillis: Long): Map<Long, Buses.Route> = routesLock.withLock {
        val day = Buses.israelDate(atMillis, zoneOffset("Asia/Jerusalem", atMillis))
        if (day != routesDay) {
            routes.clear()
            routesDay = day
        }
        val base = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.BUSES)
        val missing = lineRefs.filter { it !in routes }
        if (base != null) {
            for (chunk in missing.chunked(ROUTES_CHUNK)) {
                val got = fetch(Buses.routesUrl(base, chunk, day))?.let { runCatching { Buses.parseRoutes(it) }.getOrNull() } ?: break
                val byRef = got.associateBy { it.lineRef }
                // Lines not in the day's timetable aren't asked for again.
                for (ref in chunk) routes[ref] = byRef[ref]
            }
        }
        lineRefs.mapNotNull { ref -> routes[ref]?.let { ref to it } }.toMap()
    }

    /** The areas whose cameras were asked for, and when; the cameras got, by their OpenStreetMap object. */
    private val cameraAreas = ArrayList<Pair<app.maparound.tools.MapGrid.View, Long>>()
    private val camerasById = HashMap<String, Cameras.Camera>()
    private val camerasLock = Mutex()

    /**
     * The cameras in [view] (OpenStreetMap's, through an Overpass server, the user's first): from
     * memory where its area was asked for in the last day, else asked for with a margin around it,
     * trying each server in turn. Null when they're off or none could be had.
     */
    suspend fun cameras(view: app.maparound.tools.MapGrid.View): List<Cameras.Camera>? = camerasLock.withLock {
        val now = clock()
        cameraAreas.removeAll { now - it.second > CAMERAS_FRESH_MS }
        if (cameraAreas.none { Cameras.covers(it.first, view) }) {
            val servers = app.maparound.sources.Sources.addresses(app.maparound.sources.SourceKind.CAMERAS)
            if (servers.isEmpty()) return@withLock null
            val area = Cameras.around(view)
            val got = servers.firstNotNullOfOrNull { base -> fetch(Cameras.url(base, area), 60_000)?.let { runCatching { Cameras.parse(it) }.getOrNull() } }
                ?: return@withLock null
            if (camerasById.size > CAMERAS_KEPT) {
                camerasById.clear()
                cameraAreas.clear()
            }
            cameraAreas += area to now
            for (c in got) camerasById[c.id] = c
        }
        camerasById.values.filter { Cameras.inside(it, view) }
    }

    /** The weather maps' latest run, and when it was asked for. */
    private var weatherRun: Pair<Long, WeatherRun>? = null

    /**
     * The weather maps' latest run (its manifest), asked for again after [WEATHER_RUN_FRESH_MS];
     * null when they're off or it couldn't be had (the last one got, then, if any).
     */
    suspend fun weatherRun(): WeatherRun? {
        val base = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.WEATHER_MAPS) ?: return null
        val now = clock()
        weatherRun?.takeIf { now - it.first < WEATHER_RUN_FRESH_MS }?.let { return it.second }
        val got = fetch(base.trimEnd('/') + "/" + WeatherMap.MANIFEST)?.let(WeatherMap::parse)
        if (got != null) weatherRun = now to got
        return got ?: weatherRun?.second
    }

    /** Where the weather maps' pictures are (the source's address), or null when they're off. */
    val weatherMapsBase: String?
        get() = app.maparound.sources.Sources.address(app.maparound.sources.SourceKind.WEATHER_MAPS)

    /** [url]'s text, or null when it couldn't be had. */
    private suspend fun fetch(url: String, timeoutMs: Int = 30_000): String? = try {
        httpGetAsync(url, timeoutMs).decodeToString().takeIf { it.isNotBlank() }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val FRESH_MS = 7 * 24 * 3_600_000L

        /** Open-Meteo's forecasts now are for each quarter of an hour. */
        const val WEATHER_FRESH_MS = 15 * 60_000L
        const val WEATHER_CHUNK = 100
        const val WEATHER_KEPT = 3000
        const val ROUTES_CHUNK = 100

        /** OpenStreetMap's cameras change slowly: an area is asked for again after a day. */
        const val CAMERAS_FRESH_MS = 24 * 3_600_000L
        const val CAMERAS_KEPT = 20_000

        /** A new model run every 6 hours: the list is asked for again after half an hour. */
        const val WEATHER_RUN_FRESH_MS = 30 * 60_000L
    }
}

/** The data view's data where the app has it (the web app); null elsewhere. */
val LocalStats = staticCompositionLocalOf<StatsData?> { null }
