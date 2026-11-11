package app.maparound.data

import app.maparound.map.OnlineSource
import app.maparound.map.TileSources
import app.maparound.sensors.SpeedUnit
import app.maparound.sensors.Vec3
import app.maparound.tools.CoordFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class MapPos(val lat: Double, val lon: Double, val zoom: Int)

/** The app's settings, saved in [sp] (the same keys as before on Android). */
class Prefs(private val sp: KeyValueStore) {

    private val _baseLayer = MutableStateFlow(sp.getString(K_BASE) ?: TileSources.DEFAULT_ID)
    val baseLayer: StateFlow<String> = _baseLayer.asStateFlow()
    fun setBaseLayer(id: String) {
        _baseLayer.value = id
        sp.putString(K_BASE, id)
    }

    private val _customSources = MutableStateFlow(decodeSources(sp.getString(K_CUSTOM)))
    val customSources: StateFlow<List<OnlineSource>> = _customSources.asStateFlow()
    fun addCustomSource(source: OnlineSource) =
        saveSources(_customSources.value.filterNot { it.id == source.id } + source)

    fun removeCustomSource(id: String) {
        saveSources(_customSources.value.filterNot { it.id == id })
        if (_baseLayer.value == id) setBaseLayer(TileSources.DEFAULT_ID)
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
    private val _topoStyle = MutableStateFlow(sp.getBoolean(K_TOPO, true))
    val topoStyle: StateFlow<Boolean> = _topoStyle.asStateFlow()
    fun setTopoStyle(value: Boolean) {
        _topoStyle.value = value
        sp.putBoolean(K_TOPO, value)
    }

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
                    attribution = o.getValue("name").jsonPrimitive.content,
                    custom = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val STANDARD_PRESSURE = 1013.25f
        private const val K_BASE = "base_layer"
        private const val K_CUSTOM = "custom_sources"
        private const val K_QNH = "sea_level_pressure"
        private const val K_TRUE_NORTH = "true_north"
        private const val K_WIFI_ONLY = "wifi_only"
        private const val K_LAT = "last_lat"
        private const val K_LON = "last_lon"
        private const val K_ZOOM = "last_zoom"
        private const val K_TOPO = "topo_style"
        private const val K_CONTOURS = "contours"
        private const val K_HILLSHADE = "hillshade"
        private const val K_TERRAIN_HIGH = "terrain_high_detail"
        private const val K_TRAILS = "trails"
        private const val K_COORD_FORMAT = "coord_format"
        private const val K_LEVEL_UP = "level_up"
        private const val K_SPEED_UNIT = "speed_unit"
        private const val K_PLACES_ON_MAP = "places_on_map"
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

    // ---- motion & speed ----
    /** "Up" as the phone read it when the user tapped "set as level" (for a phone in a holder), or null. */
    private val _levelUp = MutableStateFlow(Vec3.parse(sp.getString(K_LEVEL_UP)))
    val levelUp: StateFlow<Vec3?> = _levelUp.asStateFlow()
    fun setLevelUp(value: Vec3?) {
        _levelUp.value = value
        if (value == null) sp.remove(K_LEVEL_UP) else sp.putString(K_LEVEL_UP, value.toString())
    }

    private val _speedUnit = MutableStateFlow(
        SpeedUnit.entries.firstOrNull { it.name == sp.getString(K_SPEED_UNIT) } ?: SpeedUnit.KMH,
    )
    val speedUnit: StateFlow<SpeedUnit> = _speedUnit.asStateFlow()
    fun setSpeedUnit(value: SpeedUnit) {
        _speedUnit.value = value
        sp.putString(K_SPEED_UNIT, value.name)
    }

    // ---- how coordinates are shown on pins and cards ----
    private val _coordFormat = MutableStateFlow(
        CoordFormat.entries.firstOrNull { it.name == sp.getString(K_COORD_FORMAT) } ?: CoordFormat.DD,
    )
    val coordFormat: StateFlow<CoordFormat> = _coordFormat.asStateFlow()
    fun setCoordFormat(value: CoordFormat) {
        _coordFormat.value = value
        sp.putString(K_COORD_FORMAT, value.name)
    }
}

/** The app's settings, provided at the root of the shared UI. */
val LocalPrefs = androidx.compose.runtime.staticCompositionLocalOf<Prefs> { error("No Prefs provided") }
