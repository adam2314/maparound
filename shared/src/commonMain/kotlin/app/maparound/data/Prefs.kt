package app.maparound.data

import app.maparound.ui.ScreenOn
import app.maparound.i18n.DateOrder
import app.maparound.i18n.DirectionFormat
import app.maparound.i18n.DistanceUnit
import app.maparound.i18n.HeightUnit
import app.maparound.i18n.I18n
import app.maparound.i18n.TimeFormat
import app.maparound.i18n.UnitChoice
import app.maparound.i18n.UnitSystem
import app.maparound.i18n.Units
import app.maparound.map.MapThemes
import app.maparound.map.OnlineSource
import app.maparound.map.StyleChoice
import app.maparound.map.TileSources
import app.maparound.sensors.SpeedUnit
import app.maparound.sources.SourceKind
import app.maparound.sources.SourceSetting
import app.maparound.sources.Sources
import app.maparound.sensors.Vec3
import app.maparound.tools.CoordFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class MapPos(val lat: Double, val lon: Double, val zoom: Int)

/**
 * The app's settings, saved in [sp] (the same keys as before on Android). [onUnits] gets the units
 * and formats in use, now and on each change: the apps pass [Units.set], so values everywhere are
 * shown in them.
 */
class Prefs(
    private val sp: KeyValueStore,
    private val onUnits: (UnitSystem) -> Unit = {},
    /** Gets the user's choices for the online services, now and on each change: the apps pass [Sources.set]. */
    private val onSources: (Map<SourceKind, SourceSetting>) -> Unit = {},
) {

    // ---- the app's language: a code ("he"), or "" for the device's ----
    private val _language = MutableStateFlow(sp.getString(K_LANGUAGE) ?: "")
    val language: StateFlow<String> = _language.asStateFlow()
    fun setLanguage(code: String) {
        _language.value = code
        sp.putString(K_LANGUAGE, code)
        I18n.apply(code)
    }

    init {
        I18n.apply(_language.value)
    }

    private val _baseLayer = MutableStateFlow(sp.getString(K_BASE)?.let(TileSources::current) ?: TileSources.AUTO_ID)
    val baseLayer: StateFlow<String> = _baseLayer.asStateFlow()
    fun setBaseLayer(id: String) {
        _baseLayer.value = id
        sp.putString(K_BASE, id)
    }

    private val _customSources = MutableStateFlow(decodeSources(sp.getString(K_CUSTOM)))
    val customSources: StateFlow<List<OnlineSource>> = _customSources.asStateFlow()
    fun addCustomSource(source: OnlineSource) =
        saveSources(_customSources.value.filterNot { it.id == source.id } + source)

    /**
     * Adds imported sources, skipping URLs the app already has. Returns the ones added (with
     * new ids), in order.
     */
    fun addCustomSources(sources: List<OnlineSource>, idPrefix: String): List<OnlineSource> {
        val known = (TileSources.builtIn + _customSources.value).map { it.urlTemplate }.toMutableSet()
        val added = sources.filter { known.add(it.urlTemplate) }
            .mapIndexed { i, s -> s.copy(id = "${idPrefix}_$i", custom = true) }
        if (added.isNotEmpty()) saveSources(_customSources.value + added)
        return added
    }

    fun removeCustomSource(id: String) {
        saveSources(_customSources.value.filterNot { it.id == id })
        if (_baseLayer.value == id) setBaseLayer(TileSources.AUTO_ID)
    }

    private val _seaLevelPressure = MutableStateFlow(sp.getFloat(K_QNH, STANDARD_PRESSURE))
    val seaLevelPressure: StateFlow<Float> = _seaLevelPressure.asStateFlow()
    fun setSeaLevelPressure(hPa: Float) {
        _seaLevelPressure.value = hPa
        sp.putFloat(K_QNH, hPa)
    }

    private val _trueNorth = MutableStateFlow(sp.getBoolean(K_TRUE_NORTH, true))
    val trueNorth: StateFlow<Boolean> = _trueNorth.asStateFlow()
    fun setTrueNorth(value: Boolean) {
        _trueNorth.value = value
        sp.putBoolean(K_TRUE_NORTH, value)
    }

    private val _wifiOnly = MutableStateFlow(sp.getBoolean(K_WIFI_ONLY, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        sp.putBoolean(K_WIFI_ONLY, value)
    }

    // ---- offline map style & terrain ----
    /** The offline maps' style ([MapThemes] id); before styles could be imported it was Topo or Classic. */
    private val _mapTheme = MutableStateFlow(
        sp.getString(K_THEME) ?: if (sp.getBoolean(K_TOPO, true)) MapThemes.TOPO else MapThemes.CLASSIC,
    )
    val mapTheme: StateFlow<String> = _mapTheme.asStateFlow()
    fun setMapTheme(id: String) {
        _mapTheme.value = id
        sp.putString(K_THEME, id)
    }

    /** What the user chose in each style's options (its style menu), by style id. */
    private val _styleChoices = MutableStateFlow(decodeChoices(sp.getString(K_STYLE_CHOICES)))
    val styleChoices: StateFlow<Map<String, StyleChoice>> = _styleChoices.asStateFlow()
    fun setStyleChoice(themeId: String, choice: StyleChoice) {
        val all = _styleChoices.value + (themeId to choice)
        _styleChoices.value = all
        sp.putString(
            K_STYLE_CHOICES,
            buildJsonObject {
                for ((id, c) in all) {
                    put(
                        id,
                        buildJsonObject {
                            c.style?.let { put("style", it) }
                            put("overlays", buildJsonObject { for ((o, on) in c.overlays) put(o, on) })
                        },
                    )
                }
            }.toString(),
        )
    }

    private fun decodeChoices(raw: String?): Map<String, StyleChoice> = runCatching {
        Json.parseToJsonElement(raw ?: return emptyMap()).jsonObject.mapValues { (_, v) ->
            val o = v.jsonObject
            StyleChoice(
                style = o["style"]?.jsonPrimitive?.content,
                overlays = o["overlays"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean }.orEmpty(),
            )
        }
    }.getOrDefault(emptyMap())

    private val _contours = MutableStateFlow(sp.getBoolean(K_CONTOURS, true))
    val contours: StateFlow<Boolean> = _contours.asStateFlow()
    fun setContours(value: Boolean) {
        _contours.value = value
        sp.putBoolean(K_CONTOURS, value)
    }

    private val _hillshade = MutableStateFlow(sp.getBoolean(K_HILLSHADE, true))
    val hillshade: StateFlow<Boolean> = _hillshade.asStateFlow()
    fun setHillshade(value: Boolean) {
        _hillshade.value = value
        sp.putBoolean(K_HILLSHADE, value)
    }

    private val _trails = MutableStateFlow(sp.getBoolean(K_TRAILS, true))
    val trails: StateFlow<Boolean> = _trails.asStateFlow()
    fun setTrails(value: Boolean) {
        _trails.value = value
        sp.putBoolean(K_TRAILS, value)
    }

    /** Where no installed map reaches, the vector map reads the region's map over the internet. */
    private val _streamMaps = MutableStateFlow(sp.getBoolean(K_STREAM_MAPS, true))
    val streamMaps: StateFlow<Boolean> = _streamMaps.asStateFlow()
    fun setStreamMaps(value: Boolean) {
        _streamMaps.value = value
        sp.putBoolean(K_STREAM_MAPS, value)
    }

    var terrainHighDetail: Boolean
        get() = sp.getBoolean(K_TERRAIN_HIGH, true)
        set(value) = sp.putBoolean(K_TERRAIN_HIGH, value)

    fun lastPosition(): MapPos? {
        if (!sp.contains(K_LAT)) return null
        return MapPos(
            sp.getFloat(K_LAT, 0f).toDouble(),
            sp.getFloat(K_LON, 0f).toDouble(),
            sp.getInt(K_ZOOM, 3),
        )
    }

    fun saveLastPosition(pos: MapPos) {
        sp.putFloat(K_LAT, pos.lat.toFloat())
        sp.putFloat(K_LON, pos.lon.toFloat())
        sp.putInt(K_ZOOM, pos.zoom)
    }

    private fun saveSources(list: List<OnlineSource>) {
        _customSources.value = list
        val arr = buildJsonArray {
            for (s in list) {
                add(
                    buildJsonObject {
                        put("id", s.id)
                        put("name", s.name)
                        put("url", s.urlTemplate)
                        put("maxZoom", s.maxZoom)
                        put("attribution", s.attribution)
                        put("subdomains", buildJsonArray { s.subdomains.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }
        }
        sp.putString(K_CUSTOM, arr.toString())
    }

    private fun decodeSources(raw: String?): List<OnlineSource> {
        if (raw == null) return emptyList()
        return runCatching {
            Json.parseToJsonElement(raw).jsonArray.map { el ->
                val o: JsonObject = el.jsonObject
                OnlineSource(
                    id = o.getValue("id").jsonPrimitive.content,
                    name = o.getValue("name").jsonPrimitive.content,
                    urlTemplate = o.getValue("url").jsonPrimitive.content,
                    maxZoom = runCatching { o.getValue("maxZoom").jsonPrimitive.int }.getOrDefault(19),
                    attribution = (o["attribution"] ?: o.getValue("name")).jsonPrimitive.content,
                    subdomains = o["subdomains"]?.jsonArray?.map { it.jsonPrimitive.content }?.takeIf { it.isNotEmpty() } ?: listOf("a", "b", "c"),
                    custom = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val STANDARD_PRESSURE = 1013.25f
        private const val K_BASE = "base_layer"
        private const val K_LANGUAGE = "language"
        private const val K_CUSTOM = "custom_sources"
        private const val K_QNH = "sea_level_pressure"
        private const val K_TRUE_NORTH = "true_north"
        private const val K_WIFI_ONLY = "wifi_only"
        private const val K_LAT = "last_lat"
        private const val K_LON = "last_lon"
        private const val K_ZOOM = "last_zoom"
        private const val K_TOPO = "topo_style"
        private const val K_THEME = "map_theme"
        private const val K_STYLE_CHOICES = "style_choices"
        private const val K_CONTOURS = "contours"
        private const val K_HILLSHADE = "hillshade"
        private const val K_TERRAIN_HIGH = "terrain_high_detail"
        private const val K_TRAILS = "trails"
        private const val K_STREAM_MAPS = "stream_maps"
        private const val K_COORD_FORMAT = "coord_format"
        private const val K_SHARE_LINK = "share_link"
        private const val K_ELEVATION_SOURCE = "elevation_source"
        private const val K_TERRAIN_3D = "terrain_3d_source"
        private const val K_LEVEL_UP = "level_up"
        private const val K_SPEED_UNIT = "speed_unit"
        private const val K_DISTANCE_UNIT = "distance_unit"
        private const val K_HEIGHT_UNIT = "height_unit"
        private const val K_DIRECTION_FORMAT = "direction_format"
        private const val K_TIME_FORMAT = "time_format"
        private const val K_DATE_ORDER = "date_order"
        private const val K_SOURCES = "sources"
        private const val K_NAV_3D = "navigation_3d"
        private const val K_GRID = "show_grid"
        private const val K_SCREEN_OPEN = "screen_on_open"
        private const val K_SCREEN_NAV = "screen_on_nav"
        private const val K_PLACES_ON_MAP = "places_on_map"
        private const val K_NAV_VOICE = "nav_voice"
        private const val K_BACKGROUND = "map_background"
        private const val K_VECTOR_ON_BG = "vector_on_background"
        const val BACKGROUND_NONE = "none"
        const val BACKGROUND_SATELLITE = "satellite"
        const val BACKGROUND_RASTER = "raster:"
    }

    /** Under the offline vector map: "none", "satellite" or "raster:<id>". */
    private val _mapBackground = MutableStateFlow(sp.getString(K_BACKGROUND) ?: BACKGROUND_NONE)
    val mapBackground: StateFlow<String> = _mapBackground.asStateFlow()
    fun setMapBackground(value: String) {
        _mapBackground.value = value
        sp.putString(K_BACKGROUND, value)
    }

    /** Draw roads, trails, contours and labels over the background. */
    private val _vectorOnBackground = MutableStateFlow(sp.getBoolean(K_VECTOR_ON_BG, true))
    val vectorOnBackground: StateFlow<Boolean> = _vectorOnBackground.asStateFlow()
    fun setVectorOnBackground(value: Boolean) {
        _vectorOnBackground.value = value
        sp.putBoolean(K_VECTOR_ON_BG, value)
    }

    private val _placesOnMap = MutableStateFlow(sp.getBoolean(K_PLACES_ON_MAP, true))
    val placesOnMap: StateFlow<Boolean> = _placesOnMap.asStateFlow()
    fun setPlacesOnMap(value: Boolean) {
        _placesOnMap.value = value
        sp.putBoolean(K_PLACES_ON_MAP, value)
    }

    /** Spoken turn-by-turn instructions while navigating. */
    private val _navVoice = MutableStateFlow(sp.getBoolean(K_NAV_VOICE, true))
    val navVoice: StateFlow<Boolean> = _navVoice.asStateFlow()
    fun setNavVoice(value: Boolean) {
        _navVoice.value = value
        sp.putBoolean(K_NAV_VOICE, value)
    }

    // ---- motion & speed ----
    /** "Up" as the phone read it when the user tapped "set as level" (for a phone in a holder), or null. */
    private val _levelUp = MutableStateFlow(Vec3.parse(sp.getString(K_LEVEL_UP)))
    val levelUp: StateFlow<Vec3?> = _levelUp.asStateFlow()
    fun setLevelUp(value: Vec3?) {
        _levelUp.value = value
        if (value == null) sp.remove(K_LEVEL_UP) else sp.putString(K_LEVEL_UP, value.toString())
    }

    // ---- units and formats: the user's choices, the device's for the rest ([Units]) ----
    private val _unitChoice = MutableStateFlow(
        UnitChoice(
            distance = enumOrNull<DistanceUnit>(sp.getString(K_DISTANCE_UNIT)),
            height = enumOrNull<HeightUnit>(sp.getString(K_HEIGHT_UNIT)),
            speed = enumOrNull<SpeedUnit>(sp.getString(K_SPEED_UNIT)),
            direction = enumOrNull<DirectionFormat>(sp.getString(K_DIRECTION_FORMAT)),
            time = enumOrNull<TimeFormat>(sp.getString(K_TIME_FORMAT)),
            date = enumOrNull<DateOrder>(sp.getString(K_DATE_ORDER)),
        ),
    )

    /** What the user chose (null: the device's). */
    val unitChoice: StateFlow<UnitChoice> = _unitChoice.asStateFlow()

    /** The units and formats in use: the ones chosen, the device's for the rest, and the coordinates' format. */
    private val _units = MutableStateFlow(
        _unitChoice.value.resolve(Units.device).copy(coords = CoordFormat.entries.firstOrNull { it.name == sp.getString(K_COORD_FORMAT) } ?: CoordFormat.DD),
    )
    val units: StateFlow<UnitSystem> = _units.asStateFlow()

    init {
        onUnits(_units.value)
    }

    fun setUnitChoice(c: UnitChoice) {
        _unitChoice.value = c
        putOrRemove(K_DISTANCE_UNIT, c.distance?.name)
        putOrRemove(K_HEIGHT_UNIT, c.height?.name)
        putOrRemove(K_SPEED_UNIT, c.speed?.name)
        putOrRemove(K_DIRECTION_FORMAT, c.direction?.name)
        putOrRemove(K_TIME_FORMAT, c.time?.name)
        putOrRemove(K_DATE_ORDER, c.date?.name)
        _units.value = c.resolve(Units.device).copy(coords = _units.value.coords)
        onUnits(_units.value)
        _speedUnit.value = _units.value.speed
    }

    /** The speed unit in use: the one chosen, else the device's region's. */
    private val _speedUnit = MutableStateFlow(_units.value.speed)
    val speedUnit: StateFlow<SpeedUnit> = _speedUnit.asStateFlow()
    fun setSpeedUnit(value: SpeedUnit) = setUnitChoice(_unitChoice.value.copy(speed = value))

    // ---- the coordinate grid over the map (in the coordinate format chosen) ----
    private val _showGrid = MutableStateFlow(sp.getBoolean(K_GRID, false))
    val showGrid: StateFlow<Boolean> = _showGrid.asStateFlow()
    fun setShowGrid(value: Boolean) {
        _showGrid.value = value
        sp.putBoolean(K_GRID, value)
    }

    // ---- navigation's view, and the car's map: tilted, the way ahead up (3D), or flat, north up (2D) ----
    private val _navigation3d = MutableStateFlow(sp.getBoolean(K_NAV_3D, true))
    val navigation3d: StateFlow<Boolean> = _navigation3d.asStateFlow()
    fun setNavigation3d(value: Boolean) {
        _navigation3d.value = value
        sp.putBoolean(K_NAV_3D, value)
    }

    // ---- how long the screen stays on ([app.maparound.ui.ScreenOn]): with the app open, while navigating ----
    private val _screenOnOpen = MutableStateFlow(enumOrNull<ScreenOn>(sp.getString(K_SCREEN_OPEN)) ?: ScreenOn.SYSTEM)
    val screenOnOpen: StateFlow<ScreenOn> = _screenOnOpen.asStateFlow()
    fun setScreenOnOpen(value: ScreenOn) {
        _screenOnOpen.value = value
        sp.putString(K_SCREEN_OPEN, value.name)
    }

    private val _screenOnNav = MutableStateFlow(enumOrNull<ScreenOn>(sp.getString(K_SCREEN_NAV)) ?: ScreenOn.ALWAYS)
    val screenOnNav: StateFlow<ScreenOn> = _screenOnNav.asStateFlow()
    fun setScreenOnNav(value: ScreenOn) {
        _screenOnNav.value = value
        sp.putString(K_SCREEN_NAV, value.name)
    }

    // ---- the online services: the user's choices ([Sources]; some are kept as their own settings) ----
    private val _sources = MutableStateFlow(Sources.decode(sp.getString(K_SOURCES)))
    val sources: StateFlow<Map<SourceKind, SourceSetting>> = _sources.asStateFlow()

    init {
        onSources(_sources.value)
    }

    fun setSource(kind: SourceKind, setting: SourceSetting) {
        val all = if (setting == SourceSetting.Default) _sources.value - kind else _sources.value + (kind to setting)
        _sources.value = all
        putOrRemove(K_SOURCES, Sources.encode(all).takeIf { all.isNotEmpty() })
        onSources(all)
    }

    private fun putOrRemove(key: String, value: String?) {
        if (value == null) sp.remove(key) else sp.putString(key, value)
    }

    // ---- where elevation data comes from ([ElevationSources]) ----
    /** The address template for the 1° elevation tiles, or null for the default (Terrain Tiles on AWS). */
    private val _elevationSource = MutableStateFlow(sp.getString(K_ELEVATION_SOURCE)?.let(ElevationSources::tilesTemplate))
    val elevationSource: StateFlow<String?> = _elevationSource.asStateFlow()
    fun setElevationSource(value: String?) {
        _elevationSource.value = value
        if (value == null) sp.remove(K_ELEVATION_SOURCE) else sp.putString(K_ELEVATION_SOURCE, value)
    }

    /** The web app's 3D terrain tiles, or null for the default (Terrarium tiles on AWS). */
    private val _terrain3dSource = MutableStateFlow(Terrain3dSource.parse(sp.getString(K_TERRAIN_3D)))
    val terrain3dSource: StateFlow<Terrain3dSource?> = _terrain3dSource.asStateFlow()
    fun setTerrain3dSource(value: Terrain3dSource?) {
        _terrain3dSource.value = value
        if (value == null) sp.remove(K_TERRAIN_3D) else sp.putString(K_TERRAIN_3D, value.encode())
    }

    // ---- how coordinates are shown on pins and cards ----
    private val _coordFormat = MutableStateFlow(
        CoordFormat.entries.firstOrNull { it.name == sp.getString(K_COORD_FORMAT) } ?: CoordFormat.DD,
    )
    val coordFormat: StateFlow<CoordFormat> = _coordFormat.asStateFlow()
    fun setCoordFormat(value: CoordFormat) {
        _coordFormat.value = value
        sp.putString(K_COORD_FORMAT, value.name)
        // Coordinates everywhere ([app.maparound.ui.Fmt.point]) follow.
        _units.value = _units.value.copy(coords = value)
        onUnits(_units.value)
    }

    // ---- sharing: the map link that goes with what's shared, only it (MapAround's unless another is chosen) ----
    private val _shareLink = MutableStateFlow(enumOrNull<app.maparound.share.ShareLink>(sp.getString(K_SHARE_LINK)) ?: app.maparound.share.ShareLink.MAPAROUND)
    val shareLink: StateFlow<app.maparound.share.ShareLink> = _shareLink.asStateFlow()

    init {
        app.maparound.share.SharedLinks.shareLink = _shareLink.value
    }

    fun setShareLink(value: app.maparound.share.ShareLink) {
        _shareLink.value = value
        sp.putString(K_SHARE_LINK, value.name)
        app.maparound.share.SharedLinks.shareLink = value
    }
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

/** The app's settings, provided at the root of the shared UI. */
val LocalPrefs = androidx.compose.runtime.staticCompositionLocalOf<Prefs> { error("No Prefs provided") }
