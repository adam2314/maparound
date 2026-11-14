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

/** A country's outline (GeoJSON geometry) and names. */
class Country(val iso: String, val nameEn: String, val nameHe: String, val geometry: String)

/**
 * The data view's data: the countries' outlines (the app's own file, from Natural Earth), their
 * figures (Our World in Data) and Israel's localities (data.gov.il). Each is downloaded when first
 * needed and kept in [dir] for a week, and read from there when offline.
 */
class StatsData(private val dir: String, private val outlines: suspend () -> String, private val clock: () -> Long = ::nowMillis) {
    private val _countries = MutableStateFlow<Map<String, Country>?>(null)
    val countries: StateFlow<Map<String, Country>?> = _countries.asStateFlow()

    private val _figures = MutableStateFlow<Map<CountryIndicator, Map<String, Figure>>>(emptyMap())
    val figures: StateFlow<Map<CountryIndicator, Map<String, Figure>>> = _figures.asStateFlow()

    private val _localities = MutableStateFlow<List<Locality>?>(null)
    val localities: StateFlow<List<Locality>?> = _localities.asStateFlow()

    /** What's being downloaded, or what failed; empty when nothing. */
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadCountries() = lock.withLock {
        if (_countries.value != null) return@withLock
        runCatching {
            val root = json.parseToJsonElement(outlines()).jsonObject
            _countries.value = root.mapValues { (iso, v) ->
                val o = v.jsonObject
                Country(iso, o["en"]?.jsonPrimitive?.content.orEmpty(), o["he"]?.jsonPrimitive?.content.orEmpty(), o["g"].toString())
            }
        }
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

    private companion object {
        const val FRESH_MS = 7 * 24 * 3_600_000L
    }
}

/** The data view's data where the app has it (the web app); null elsewhere. */
val LocalStats = staticCompositionLocalOf<StatsData?> { null }
