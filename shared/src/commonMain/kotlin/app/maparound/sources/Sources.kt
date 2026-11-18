package app.maparound.sources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.maparound.data.ElevationSources
import app.maparound.data.MapAroundCatalog
import app.maparound.map.OnlineSource
import app.maparound.map.TileSources
import app.maparound.party.PartyRelays
import app.maparound.shared.net.HttpException
import app.maparound.shared.net.httpGetAsync
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Plain Kotlin (with snapshot state): the online services the app reads from, and the user's
// choices for them (Settings → Sources). Unit-tested.

/** How the Sources page groups them. */
enum class SourceGroup { MAPS, TERRAIN, ROUTING, PLACES, DATA, PARTY }

/** What the address of a service is like, for checking one the user types. */
enum class AddressKind {
    /** A web address (https://…). */
    URL,

    /** A tile address with {z}, {x} and {y} ([TileSources.validateTemplate]). */
    TILES,

    /** Elevation tiles with {name} ([ElevationSources.tilesTemplate]). */
    ELEVATION,

    /** The web 3D view's terrain tiles ({z}/{x}/{y}). */
    RELIEF,

    /** A party's MQTT server, wss:// ([PartyRelays.server]). */
    MQTT,

    /** Only on or off. */
    NONE,
}

/**
 * The online services, each a line on the Sources page. [builtIn]: the addresses used by default,
 * tried in turn; [customFirst]: the user's address is tried before them (else it replaces them);
 * [canBeOff]: the user can turn it off (the app goes without). Some are kept elsewhere already:
 * the map layer ([app.maparound.data.Prefs.baseLayer]), streamed maps, elevation and 3D relief
 * (their own settings), the party's server (the party's); the page shows those too.
 */
enum class SourceKind(
    val group: SourceGroup,
    val builtIn: List<String>,
    val address: AddressKind = AddressKind.URL,
    val canBeOff: Boolean = false,
    val customFirst: Boolean = false,
) {
    /** The list of maps to download (catalog.json). */
    MAP_LIST(SourceGroup.MAPS, MapAroundCatalog.CATALOG_URLS, customFirst = true),

    /** The online map layer (Layers): kept as the base layer. */
    MAP_LAYER(SourceGroup.MAPS, emptyList(), AddressKind.TILES),

    /** Maps read over the internet where none is downloaded (Android): on or off. */
    STREAMED_MAPS(SourceGroup.MAPS, emptyList(), AddressKind.NONE, canBeOff = true),

    /** Satellite imagery under the vector map, and in the 3D view. */
    SATELLITE(SourceGroup.MAPS, listOf(TileSources.ESRI_IMAGERY), AddressKind.TILES),

    /** Marked trails over online maps. */
    TRAILS_OVERLAY(SourceGroup.MAPS, listOf(TileSources.WAYMARKED_HIKING), AddressKind.TILES),

    /** Marked trails downloaded for the Topo style (Android): Overpass servers. */
    TRAILS_DATA(SourceGroup.MAPS, listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"), customFirst = true),

    /** Elevation tiles (contours, hill shading, line of sight, 3D): kept as their own setting. */
    ELEVATION(SourceGroup.TERRAIN, listOf(ElevationSources.DEFAULT_TILES), AddressKind.ELEVATION),

    /** The web app's 3D relief: kept as its own setting. */
    RELIEF_3D(SourceGroup.TERRAIN, listOf(ElevationSources.DEFAULT_3D), AddressKind.RELIEF),

    /** Routes calculated online (where routing data isn't downloaded; the web app always). */
    ROUTING(SourceGroup.ROUTING, listOf("https://brouter.de/brouter"), canBeOff = true),

    /** Routing data downloads (Android). */
    ROUTING_DATA(SourceGroup.ROUTING, listOf("https://brouter.de/brouter/segments4/")),

    /** Searching for places online: Photon, Nominatim if it fails. */
    SEARCH(SourceGroup.PLACES, listOf("https://photon.komoot.io/api/", "https://nominatim.openstreetmap.org/search"), canBeOff = true, customFirst = true),

    /** Wikipedia articles and Wikimedia Commons photos around a place ("What's here?"). */
    WIKIPEDIA(SourceGroup.PLACES, listOf("https://wikipedia.org", "https://commons.wikimedia.org"), AddressKind.NONE, canBeOff = true),

    /** The data view's GPS traces (the web app): OpenStreetMap's public ones. */
    GPS_TRACES(SourceGroup.DATA, listOf(TileSources.GPS_TRACES), AddressKind.TILES, canBeOff = true),

    /** The data view's rain radar: RainViewer's pictures. */
    RAIN_RADAR(SourceGroup.DATA, listOf(app.maparound.stats.RainRadar.FRAMES), AddressKind.NONE, canBeOff = true),

    /** The data view's weather: Open-Meteo, or the user's own Open-Meteo server. */
    WEATHER(SourceGroup.DATA, listOf(app.maparound.stats.Weather.OPEN_METEO), canBeOff = true),

    /** The data view's street photos: Panoramax's catalogue, or one Panoramax server. */
    STREET_PHOTOS(SourceGroup.DATA, listOf(app.maparound.stats.StreetPhotos.PANORAMAX), canBeOff = true),

    /** The data view's buses in Israel: the Open Bus project's API. */
    BUSES(SourceGroup.DATA, listOf(app.maparound.stats.Buses.OPEN_BUS), canBeOff = true),

    /** The data view's cameras: OpenStreetMap's, from Overpass servers (the user's first). */
    CAMERAS(SourceGroup.DATA, listOf("https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"), canBeOff = true, customFirst = true),

    /** Where parties meet online: kept by the party. */
    PARTY(SourceGroup.PARTY, PartyRelays.WEBSOCKET, AddressKind.MQTT),
}

/** The user's choice for a service. */
sealed interface SourceSetting {
    /** The app's own (for where the map is, when there's a regional one). */
    data object Default : SourceSetting

    /** The user's own address(es). */
    data class Custom(val addresses: List<String>) : SourceSetting

    /** Not used at all. */
    data object Off : SourceSetting
}

/**
 * Sources that suit one region better than the app's own: (region path prefix, kind, one of
 * [TileSources.builtIn]'s ids). A region matches its prefix and everything under it
 * (`north-america/us/california` is in `north-america/us`). National mapping agencies' maps, each
 * for its own country.
 */
object RegionalDefaults {
    val table: List<Triple<String, SourceKind, String>> = listOf(
        Triple("europe/switzerland", SourceKind.MAP_LAYER, "swisstopo"),
        Triple("europe/austria", SourceKind.MAP_LAYER, "basemap_at"),
        Triple("europe/norway", SourceKind.MAP_LAYER, "kartverket"),
        Triple("europe/france", SourceKind.MAP_LAYER, "ign_plan"),
        Triple("europe/france", SourceKind.SATELLITE, "ign_ortho"),
        Triple("north-america/us", SourceKind.MAP_LAYER, "usgs_topo"),
    )

    /** The source for [kind] where the map is ([regions]: their paths, the most specific first), or null. */
    fun of(kind: SourceKind, regions: List<String>): OnlineSource? {
        for (r in regions) {
            val id = table.firstOrNull { (prefix, k, _) -> k == kind && (r == prefix || r.startsWith("$prefix/")) }?.third
            if (id != null) return TileSources.builtIn.firstOrNull { it.id == id }
        }
        return null
    }
}

/**
 * The user's choices for the online services, and where the map is (for regional defaults). Both
 * are snapshot state: screens and maps using [addresses] follow changes. The apps' settings set
 * [settings] ([app.maparound.data.Prefs]); the map sets [regionPaths].
 */
object Sources {
    var settings: Map<SourceKind, SourceSetting> by mutableStateOf(emptyMap())
        private set

    /** The regions the map shows closely (paths, the most specific first); empty when zoomed out. */
    var regionPaths: List<String> by mutableStateOf(emptyList())

    /** The map is shown closely enough for a region's own sources from this zoom on. */
    const val REGIONAL_ZOOM = 8

    fun set(settings: Map<SourceKind, SourceSetting>) {
        this.settings = settings
    }

    fun setting(kind: SourceKind): SourceSetting = settings[kind] ?: SourceSetting.Default

    fun isOff(kind: SourceKind): Boolean = setting(kind) == SourceSetting.Off

    /** [kind]'s source for where the map is, when a region has its own (and the user hasn't chosen). */
    fun regional(kind: SourceKind, regions: List<String> = regionPaths): OnlineSource? =
        if (setting(kind) == SourceSetting.Default) RegionalDefaults.of(kind, regions) else null

    /**
     * The addresses to use for [kind], in turn: the user's (first, or instead), the region's, the
     * app's own; none when it's off.
     */
    fun addresses(kind: SourceKind, regions: List<String> = regionPaths): List<String> = when (val s = setting(kind)) {
        SourceSetting.Off -> emptyList()
        is SourceSetting.Custom -> if (kind.customFirst) (s.addresses + kind.builtIn).distinct() else s.addresses
        SourceSetting.Default -> RegionalDefaults.of(kind, regions)?.let { listOf(it.urlTemplate) } ?: kind.builtIn
    }

    /** [kind]'s first address, or null when it's off. */
    fun address(kind: SourceKind, regions: List<String> = regionPaths): String? = addresses(kind, regions).firstOrNull()

    /** [text] as an address for [kind], or null if it can't be one. */
    fun validate(kind: SourceKind, text: String): String? {
        val t = text.trim()
        return when (kind.address) {
            AddressKind.URL -> t.takeIf { isWebAddress(it) }
            AddressKind.TILES -> t.takeIf { isWebAddress(it) && TileSources.validateTemplate(it) == null }
            AddressKind.ELEVATION -> ElevationSources.tilesTemplate(t)
            AddressKind.RELIEF -> ElevationSources.tiles3dTemplate(t)
            AddressKind.MQTT -> PartyRelays.server(t)
            AddressKind.NONE -> null
        }
    }

    private fun isWebAddress(t: String) =
        (t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)) &&
            t.substringAfter("://").substringBefore('/').isNotEmpty() && t.none { it.isWhitespace() }

    // ---- kept ----

    /** As kept in the settings: `{"SEARCH": {"custom": ["https://…"]}, "WIKIPEDIA": {"off": true}}`. */
    fun encode(settings: Map<SourceKind, SourceSetting>): String = buildJsonObject {
        for ((kind, s) in settings) {
            when (s) {
                SourceSetting.Default -> Unit
                SourceSetting.Off -> put(kind.name, buildJsonObject { put("off", true) })
                is SourceSetting.Custom -> put(kind.name, buildJsonObject { put("custom", buildJsonArray { s.addresses.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) })
            }
        }
    }.toString()

    fun decode(text: String?): Map<SourceKind, SourceSetting> {
        val obj = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return emptyMap()
        return buildMap {
            for ((name, v) in obj) {
                val kind = SourceKind.entries.firstOrNull { it.name == name } ?: continue
                val o = v as? JsonObject ?: continue
                when {
                    runCatching { o["off"]?.jsonPrimitive?.boolean }.getOrNull() == true -> put(kind, SourceSetting.Off)
                    o["custom"] != null -> runCatching { o.getValue("custom").jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
                        ?.mapNotNull { validate(kind, it) }?.takeIf { it.isNotEmpty() }?.let { put(kind, SourceSetting.Custom(it)) }
                }
            }
        }
    }

    // ---- checking an address ----

    /** How a check went: answered (in [ms]), or not ([problem]). */
    data class Check(val ok: Boolean, val ms: Long, val problem: String? = null)

    /**
     * One request to [address] as [kind]'s, to see that it answers: a tile, elevation tile or
     * search near [lat]/[lon], the list itself, a tiny Overpass query, the server's page.
     */
    suspend fun check(kind: SourceKind, address: String, lat: Double, lon: Double): Check {
        val url = when (kind.address) {
            AddressKind.TILES, AddressKind.RELIEF -> tileAt(address, lat, lon, 12)
            AddressKind.ELEVATION -> ElevationSources.tileUrl(address, app.maparound.terrain.DemIndex.nameFor(kotlin.math.floor(lat).toInt(), kotlin.math.floor(lon).toInt()))
            AddressKind.URL -> when (kind) {
                SourceKind.TRAILS_DATA, SourceKind.CAMERAS -> "$address?data=" + "[out:json];out;".encodeUrl()
                SourceKind.SEARCH -> if (address.contains("nominatim", ignoreCase = true) || address.trimEnd('/').endsWith("/search")) {
                    "${address.trimEnd('/').removeSuffix("/search")}/search?format=jsonv2&limit=1&q=a"
                } else {
                    "${address.trimEnd('/')}/?limit=1&q=a"
                }
                SourceKind.ROUTING -> "${address.trimEnd('/')}?lonlats=${fmt(lon)},${fmt(lat)}|${fmt(lon + 0.001)},${fmt(lat + 0.001)}&profile=trekking&alternativeidx=0&format=geojson"
                SourceKind.WEATHER -> app.maparound.stats.Weather.nowUrl(address, listOf(lat to lon))
                SourceKind.STREET_PHOTOS -> "${address.trimEnd('/')}/search?limit=1&bbox=${fmt(lon - 0.05)},${fmt(lat - 0.05)},${fmt(lon + 0.05)},${fmt(lat + 0.05)}"
                SourceKind.BUSES -> app.maparound.stats.Buses.snapshotsUrl(address)
                else -> address
            }
            AddressKind.MQTT, AddressKind.NONE -> return Check(true, 0)
        }
        val start = nowMillis()
        return try {
            httpGetAsync(url, timeoutMs = 15_000)
            Check(true, nowMillis() - start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Check(false, nowMillis() - start, "HTTP ${e.status}")
        } catch (e: Exception) {
            Check(false, nowMillis() - start, e.message ?: e::class.simpleName)
        }
    }

    private fun fmt(v: Double) = ((v * 1e5).toLong() / 1e5).toString()

    private fun String.encodeUrl() = app.maparound.shared.util.urlEncode(this)

    /** The address of the tile at [zoom] around [lat]/[lon] from a {z}/{x}/{y} template. */
    internal fun tileAt(template: String, lat: Double, lon: Double, zoom: Int): String {
        val n = 1 shl zoom
        val x = ((lon + 180) / 360 * n).toInt().coerceIn(0, n - 1)
        val r = lat.coerceIn(-85.0, 85.0) * kotlin.math.PI / 180
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / kotlin.math.PI) / 2 * n).toInt().coerceIn(0, n - 1)
        return template.replace("{s}", "a").replace("{z}", "$zoom").replace("{x}", "$x").replace("{y}", "$y").replace("{-y}", "${n - 1 - y}")
    }
}
