package app.maparound.data

import android.content.Context
import androidx.core.content.edit
import app.maparound.map.OnlineSource
import app.maparound.map.TileSources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class MapPos(val lat: Double, val lon: Double, val zoom: Int)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("maparound", Context.MODE_PRIVATE)

    private val _baseLayer = MutableStateFlow(sp.getString(K_BASE, TileSources.DEFAULT_ID)!!)
    val baseLayer: StateFlow<String> = _baseLayer.asStateFlow()
    fun setBaseLayer(id: String) {
        _baseLayer.value = id
        sp.edit { putString(K_BASE, id) }
    }

    private val _customSources = MutableStateFlow(decodeSources(sp.getString(K_CUSTOM, null)))
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
        sp.edit { putFloat(K_QNH, hPa) }
    }

    private val _trueNorth = MutableStateFlow(sp.getBoolean(K_TRUE_NORTH, true))
    val trueNorth: StateFlow<Boolean> = _trueNorth.asStateFlow()
    fun setTrueNorth(value: Boolean) {
        _trueNorth.value = value
        sp.edit { putBoolean(K_TRUE_NORTH, value) }
    }

    private val _wifiOnly = MutableStateFlow(sp.getBoolean(K_WIFI_ONLY, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()
    fun setWifiOnly(value: Boolean) {
        _wifiOnly.value = value
        sp.edit { putBoolean(K_WIFI_ONLY, value) }
    }

    // ---- offline map style & terrain ----
    private val _topoStyle = MutableStateFlow(sp.getBoolean(K_TOPO, true))
    val topoStyle: StateFlow<Boolean> = _topoStyle.asStateFlow()
    fun setTopoStyle(value: Boolean) {
        _topoStyle.value = value
        sp.edit { putBoolean(K_TOPO, value) }
    }

    private val _contours = MutableStateFlow(sp.getBoolean(K_CONTOURS, true))
    val contours: StateFlow<Boolean> = _contours.asStateFlow()
    fun setContours(value: Boolean) {
        _contours.value = value
        sp.edit { putBoolean(K_CONTOURS, value) }
    }

    private val _hillshade = MutableStateFlow(sp.getBoolean(K_HILLSHADE, true))
    val hillshade: StateFlow<Boolean> = _hillshade.asStateFlow()
    fun setHillshade(value: Boolean) {
        _hillshade.value = value
        sp.edit { putBoolean(K_HILLSHADE, value) }
    }

    private val _trails = MutableStateFlow(sp.getBoolean(K_TRAILS, true))
    val trails: StateFlow<Boolean> = _trails.asStateFlow()
    fun setTrails(value: Boolean) {
        _trails.value = value
        sp.edit { putBoolean(K_TRAILS, value) }
    }

    var terrainHighDetail: Boolean
        get() = sp.getBoolean(K_TERRAIN_HIGH, true)
        set(value) = sp.edit { putBoolean(K_TERRAIN_HIGH, value) }

    fun lastPosition(): MapPos? {
        if (!sp.contains(K_LAT)) return null
        return MapPos(
            sp.getFloat(K_LAT, 0f).toDouble(),
            sp.getFloat(K_LON, 0f).toDouble(),
            sp.getInt(K_ZOOM, 3),
        )
    }

    fun saveLastPosition(pos: MapPos) = sp.edit {
        putFloat(K_LAT, pos.lat.toFloat())
        putFloat(K_LON, pos.lon.toFloat())
        putInt(K_ZOOM, pos.zoom)
    }

    /** DownloadManager id -> target local file name, for downloads still in flight. */
    @Synchronized
    fun downloads(): Map<Long, String> {
        val json = JSONObject(sp.getString(K_DOWNLOADS, "{}")!!)
        return json.keys().asSequence().associate { it.toLong() to json.getString(it) }
    }

    @Synchronized
    fun putDownload(id: Long, fileName: String) = writeDownloads(downloads() + (id to fileName))

    @Synchronized
    fun removeDownload(id: Long) = writeDownloads(downloads() - id)

    private fun writeDownloads(map: Map<Long, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k.toString(), v) }
        sp.edit { putString(K_DOWNLOADS, json.toString()) }
    }

    private fun saveSources(list: List<OnlineSource>) {
        _customSources.value = list
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("url", it.urlTemplate)
                    .put("maxZoom", it.maxZoom),
            )
        }
        sp.edit { putString(K_CUSTOM, arr.toString()) }
    }

    private fun decodeSources(raw: String?): List<OnlineSource> {
        if (raw == null) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OnlineSource(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    urlTemplate = o.getString("url"),
                    maxZoom = o.optInt("maxZoom", 19),
                    attribution = o.getString("name"),
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
        private const val K_DOWNLOADS = "downloads"
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
    private val _mapBackground = MutableStateFlow(sp.getString(K_BACKGROUND, BACKGROUND_NONE)!!)
    val mapBackground: StateFlow<String> = _mapBackground.asStateFlow()
    fun setMapBackground(value: String) {
        _mapBackground.value = value
        sp.edit { putString(K_BACKGROUND, value) }
    }

    /** Draw roads, trails, contours and labels over the background. */
    private val _vectorOnBackground = MutableStateFlow(sp.getBoolean(K_VECTOR_ON_BG, true))
    val vectorOnBackground: StateFlow<Boolean> = _vectorOnBackground.asStateFlow()
    fun setVectorOnBackground(value: Boolean) {
        _vectorOnBackground.value = value
        sp.edit { putBoolean(K_VECTOR_ON_BG, value) }
    }

    private val _placesOnMap = MutableStateFlow(sp.getBoolean(K_PLACES_ON_MAP, true))
    val placesOnMap: StateFlow<Boolean> = _placesOnMap.asStateFlow()
    fun setPlacesOnMap(value: Boolean) {
        _placesOnMap.value = value
        sp.edit { putBoolean(K_PLACES_ON_MAP, value) }
    }

    // ---- motion & speed ----
    /** "Up" as the phone read it when the user tapped "set as level" (for a phone in a holder), or null. */
    private val _levelUp = MutableStateFlow(app.maparound.sensors.Vec3.parse(sp.getString(K_LEVEL_UP, null)))
    val levelUp: StateFlow<app.maparound.sensors.Vec3?> = _levelUp.asStateFlow()
    fun setLevelUp(value: app.maparound.sensors.Vec3?) {
        _levelUp.value = value
        sp.edit { if (value == null) remove(K_LEVEL_UP) else putString(K_LEVEL_UP, value.toString()) }
    }

    private val _speedUnit = MutableStateFlow(
        app.maparound.sensors.SpeedUnit.entries.firstOrNull { it.name == sp.getString(K_SPEED_UNIT, null) }
            ?: app.maparound.sensors.SpeedUnit.KMH,
    )
    val speedUnit: StateFlow<app.maparound.sensors.SpeedUnit> = _speedUnit.asStateFlow()
    fun setSpeedUnit(value: app.maparound.sensors.SpeedUnit) {
        _speedUnit.value = value
        sp.edit { putString(K_SPEED_UNIT, value.name) }
    }

    // ---- how coordinates are shown on pins and cards ----
    private val _coordFormat = MutableStateFlow(
        app.maparound.tools.CoordFormat.entries.firstOrNull { it.name == sp.getString(K_COORD_FORMAT, null) }
            ?: app.maparound.tools.CoordFormat.DD,
    )
    val coordFormat: StateFlow<app.maparound.tools.CoordFormat> = _coordFormat.asStateFlow()
    fun setCoordFormat(value: app.maparound.tools.CoordFormat) {
        _coordFormat.value = value
        sp.edit { putString(K_COORD_FORMAT, value.name) }
    }
}
