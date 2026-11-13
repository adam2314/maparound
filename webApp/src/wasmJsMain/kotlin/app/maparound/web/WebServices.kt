package app.maparound.web

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import app.maparound.data.CatalogEntry
import app.maparound.data.DownloadInfo
import app.maparound.data.InstalledMaps
import app.maparound.data.LocalMap
import app.maparound.data.MapDownloads
import app.maparound.data.RoutingData
import app.maparound.data.RoutingDataState
import app.maparound.data.TerrainData
import app.maparound.data.TerrainState
import app.maparound.data.TrailData
import app.maparound.data.TrailState
import app.maparound.map.MapTheme
import app.maparound.map.MapThemeLibrary
import app.maparound.map.MapThemes
import app.maparound.map.StyleMenu
import app.maparound.party.PartyController
import app.maparound.party.PartyEvent
import app.maparound.party.PartyKey
import app.maparound.party.PartyPlatform
import app.maparound.party.PartyState
import app.maparound.places.Place
import app.maparound.places.PlaceRepository
import app.maparound.places.Places
import app.maparound.raster.RasterLibrary
import app.maparound.raster.RasterMap
import app.maparound.routing.GeoPoint
import app.maparound.routing.Segments
import app.maparound.search.SearchEngine
import app.maparound.search.SearchResult
import app.maparound.sensors.DeviceSensors
import app.maparound.sensors.GeoFix
import app.maparound.sensors.MotionSample
import app.maparound.sensors.MotionSensor
import app.maparound.sensors.Orientation
import app.maparound.sensors.SensorAccuracy
import app.maparound.sensors.SensorInfo
import app.maparound.sensors.Vec3
import app.maparound.share.Incoming
import app.maparound.share.RouteText
import app.maparound.share.SharedLinks
import app.maparound.shared.fileSystem
import app.maparound.shared.net.httpGetText
import app.maparound.terrain.GeoBounds
import app.maparound.tracks.Gpx
import app.maparound.tracks.Track
import app.maparound.ui.PlatformActions
import app.maparound.ui.RecordingControl
import app.maparound.ui.Searcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import okio.Path.Companion.toPath
import kotlin.math.PI
import kotlin.math.floor

internal const val LATER = "Coming to the web app in a later version"

// ---- sharing: the browser's share sheet where there is one, else the clipboard or a download ------

private fun canShare(): Boolean = js("!!navigator.share")
private fun shareTextJs(title: String, text: String): Unit = js("{ navigator.share({ title: title, text: text }).catch(() => {}); }")
private fun copyText(text: String): Unit = js("{ if (navigator.clipboard) navigator.clipboard.writeText(text).catch(() => {}); }")
private fun downloadText(name: String, mime: String, text: String): Unit = js(
    """{
    const url = URL.createObjectURL(new Blob([text], { type: mime }));
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
}""",
)
private fun openWindow(url: String): Unit = js("{ window.open(url, '_blank', 'noopener'); }")

object WebPlatformActions : PlatformActions {
    override fun shareText(text: String, title: String) {
        if (canShare()) {
            shareTextJs(title, text)
        } else {
            copyText(text)
            Incoming.pending.value = Incoming.Item.Message("Copied to the clipboard")
        }
    }

    override fun sharePointText(lat: Double, lon: Double, label: String?) = shareText(SharedLinks.pointText(lat, lon, label), label ?: "Location")

    override fun sharePointGpx(lat: Double, lon: Double, label: String?) =
        saveGpx(label ?: "location") { Gpx.writeWaypoint(label, lat, lon, it) }

    override fun openPointElsewhere(lat: Double, lon: Double, label: String?) =
        openWindow("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon")

    override fun sharePlaces(places: List<Place>, title: String) = saveGpx(title) { Places.writeGpx(places, it, title) }

    override fun shareRoute(track: Track, gpxPath: String?) {
        val saved = gpxPath?.toPath()?.takeIf { fileSystem.exists(it) }?.let { p -> fileSystem.read(p) { readUtf8() } }
        val text = saved ?: StringBuilder().also { Gpx.write(track, it) }.toString()
        downloadText("${slug(track.name)}.gpx", "application/gpx+xml", text)
    }

    override fun shareRouteLink(track: Track) = shareText(RouteText.of(track), track.name)

    override fun shareTextFile(fileName: String, mimeType: String, content: String, title: String) =
        downloadText(fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"), mimeType, content)

    private fun saveGpx(name: String, write: (Appendable) -> Unit) =
        downloadText("${slug(name)}.gpx", "application/gpx+xml", StringBuilder().also(write).toString())

    private fun slug(name: String): String =
        name.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }.trim().replace(' ', '_').take(50).ifEmpty { "shared" }
}

// ---- search ---------------------------------------------------------------------------------

private fun browserLanguage(): String = js("(navigator.language || 'en').split('-')[0]")

/** Search over the saved places and the downloaded maps; online through the browser (Photon, Nominatim). */
class WebSearcher(private val places: PlaceRepository, private val maps: InstalledMaps) : Searcher {
    override fun quick(query: String, near: GeoPoint?): List<SearchResult> = SearchEngine.quick(query, near, places.places.value)

    override fun offline(query: String, center: GeoPoint, isActive: () -> Boolean): List<SearchResult> =
        SearchEngine.offline(query, center, maps.files.value.filterNot { it.isWorld }.map { it.path }, browserLanguage(), 25.0, isActive)

    override fun online(query: String, near: GeoPoint?): List<SearchResult> =
        SearchEngine.online(query, near) { url -> httpGetText(url, mapOf("Accept-Language" to "${browserLanguage()},en;q=0.8"), 12_000) }
}

// ---- sensors: geolocation, device orientation and motion -------------------------------------------

private fun nowMs(): Double = js("Date.now()")
private fun monotonicMs(): Double = js("performance.now()")

/** Positions; NaN for what the browser doesn't know. Returns a handle for [geoClear]. */
private fun geoWatch(onFix: (Double, Double, Double, Double, Double, Double, Double) -> Unit, onError: (Int) -> Unit): Int = js(
    """(navigator.geolocation ? navigator.geolocation.watchPosition(p => {
        const c = p.coords;
        const n = v => (v === null || v === undefined) ? NaN : v;
        onFix(c.latitude, c.longitude, n(c.altitude), n(c.accuracy), n(c.speed), n(c.heading), p.timestamp);
    }, e => onError(e.code), { enableHighAccuracy: true, maximumAge: 0, timeout: 60000 }) : -1)""",
)

private fun geoClear(id: Int): Unit = js("{ if (navigator.geolocation && id >= 0) navigator.geolocation.clearWatch(id); }")

/** Asks for the position once (the browser asks the user); [done] gets whether it's allowed. */
private fun geoAsk(done: (Boolean) -> Unit): Unit = js(
    """{
    if (!navigator.geolocation) { done(false); return; }
    navigator.geolocation.getCurrentPosition(() => done(true), e => done(e.code !== 1), { enableHighAccuracy: true, timeout: 30000 });
}""",
)

/** The Permissions API's state for geolocation: [done] gets "granted", "denied" or "prompt". */
private fun geoPermission(done: (String) -> Unit): Unit = js(
    """{
    if (navigator.permissions && navigator.permissions.query) {
        navigator.permissions.query({ name: 'geolocation' }).then(s => {
            done(s.state);
            s.onchange = () => done(s.state);
        }, () => done('prompt'));
    } else {
        done('prompt');
    }
}""",
)

private fun hasOrientationEvents(): Boolean = js("typeof DeviceOrientationEvent !== 'undefined'")
private fun hasMotionEvents(): Boolean = js("typeof DeviceMotionEvent !== 'undefined'")

/**
 * The compass: Safari's `webkitCompassHeading`, else `deviceorientationabsolute` (Chrome). iPhones
 * ask for permission first. [onReading] gets heading (−1 if unknown), pitch, roll.
 */
private fun orientationListen(onReading: (Double, Double, Double) -> Unit): JsAny = js(
    """{
    const handler = e => {
        let heading = -1;
        if (typeof e.webkitCompassHeading === 'number') heading = e.webkitCompassHeading;
        else if (e.absolute && typeof e.alpha === 'number') heading = (360 - e.alpha) % 360;
        onReading(heading, e.beta || 0, e.gamma || 0);
    };
    const start = () => {
        if ('ondeviceorientationabsolute' in window) window.addEventListener('deviceorientationabsolute', handler);
        else window.addEventListener('deviceorientation', handler);
    };
    if (typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function') {
        DeviceOrientationEvent.requestPermission().then(r => { if (r === 'granted') start(); }).catch(() => {});
    } else {
        start();
    }
    return handler;
}""",
)

private fun orientationStop(handler: JsAny): Unit = js(
    "{ window.removeEventListener('deviceorientationabsolute', handler); window.removeEventListener('deviceorientation', handler); }",
)

/** Motion in m/s² and deg/s: accel with gravity (x, y, z), without (x, y, z), rotation (alpha, beta, gamma); NaN if missing. */
private fun motionListen(onSample: (Double, Double, Double, Double, Double, Double, Double, Double, Double) -> Unit): JsAny = js(
    """{
    const n = v => (v === null || v === undefined) ? NaN : v;
    const handler = e => {
        const g = e.accelerationIncludingGravity || {}, a = e.acceleration || {}, r = e.rotationRate || {};
        onSample(n(g.x), n(g.y), n(g.z), n(a.x), n(a.y), n(a.z), n(r.alpha), n(r.beta), n(r.gamma));
    };
    const start = () => window.addEventListener('devicemotion', handler);
    if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
        DeviceMotionEvent.requestPermission().then(r => { if (r === 'granted') start(); }).catch(() => {});
    } else {
        start();
    }
    return handler;
}""",
)

private fun motionStop(handler: JsAny): Unit = js("{ window.removeEventListener('devicemotion', handler); }")

/** The phone's (or computer's) sensors as far as the browser has them: no barometer, no satellites. */
class WebSensors : DeviceSensors {
    private val _permission = MutableStateFlow(false)

    /** Whether the page may use the position. */
    val permission: StateFlow<Boolean> = _permission.asStateFlow()

    /** The browser hasn't asked yet. */
    var canAsk = true
        private set

    init {
        geoPermission { state ->
            _permission.value = state == "granted"
            canAsk = state == "prompt"
        }
    }

    /** Asks for the position (the browser shows its question). */
    fun requestPermission() = geoAsk { ok -> if (ok) _permission.value = true }

    override fun hasLocationPermission(): Boolean = _permission.value

    override fun locations(intervalMs: Long): Flow<GeoFix> = callbackFlow {
        val id = geoWatch(
            onFix = { lat, lon, alt, acc, speed, heading, time ->
                _permission.value = true
                trySend(
                    GeoFix(
                        lat = lat, lon = lon,
                        altitudeM = alt.takeUnless { it.isNaN() },
                        accuracyM = acc.takeUnless { it.isNaN() }?.toFloat(),
                        speedMps = speed.takeUnless { it.isNaN() }?.toFloat(),
                        speedAccuracyMps = null,
                        bearingDeg = heading.takeUnless { it.isNaN() }?.toFloat(),
                        timeMs = time.toLong(),
                        elapsedMs = monotonicMs().toLong(),
                        // Browsers don't say where a fix comes from: a precise one is taken as satellite.
                        satellite = !acc.isNaN() && acc <= 30.0,
                    ),
                )
            },
            onError = { code -> if (code == 1) _permission.value = false },
        )
        awaitClose { geoClear(id) }
    }

    override val hasCompass: Boolean get() = hasOrientationEvents()

    override fun orientation(): Flow<Orientation> = callbackFlow {
        val handler = orientationListen { heading, pitch, roll ->
            if (heading >= 0) trySend(Orientation(heading.toFloat(), pitch.toFloat(), roll.toFloat(), SensorAccuracy.MEDIUM))
        }
        awaitClose { orientationStop(handler) }
    }

    override fun motionInfo(sensor: MotionSensor): SensorInfo? = if (!hasMotionEvents()) null else when (sensor) {
        MotionSensor.ACCELEROMETER -> SensorInfo("Accelerometer", "Browser", 0f, 0f, 60)
        MotionSensor.GYROSCOPE -> SensorInfo("Gyroscope", "Browser", 0f, 0f, 60)
    }

    override fun motion(periodMs: Long): Flow<MotionSample> = callbackFlow {
        var last = 0.0
        val handler = motionListen { gx, gy, gz, ax, ay, az, ra, rb, rg ->
            val now = monotonicMs()
            if (now - last < periodMs) return@motionListen
            last = now
            val accel = if (gx.isNaN()) null else Vec3(gx.toFloat(), gy.toFloat(), gz.toFloat())
            val linear = if (ax.isNaN()) null else Vec3(ax.toFloat(), ay.toFloat(), az.toFloat())
            val gravity = if (accel != null && linear != null) Vec3(accel.x - linear.x, accel.y - linear.y, accel.z - linear.z) else null
            val toRad = PI.toFloat() / 180f
            // rotationRate: alpha about z, beta about x, gamma about y (deg/s).
            val gyro = if (rb.isNaN()) null else Vec3(rb.toFloat() * toRad, rg.toFloat() * toRad, ra.toFloat() * toRad)
            trySend(MotionSample((now * 1e6).toLong(), accel, linear, gravity, gyro))
        }
        awaitClose { motionStop(handler) }
    }

    override val hasBarometer: Boolean get() = false
    override fun pressure(): Flow<Float> = emptyFlow()

    /** The browser has no magnetic model: headings are magnetic. */
    override fun declination(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Float = 0f

    override fun elapsedMs(): Long = monotonicMs().toLong()
}

// ---- what comes in later steps: answers that say so --------------------------------------------

/** Offline maps in the browser come in the next step (they need the browser's file storage). */
class WebInstalledMaps : InstalledMaps {
    override val files: StateFlow<List<LocalMap>> = MutableStateFlow(emptyList())
    override fun delete(map: LocalMap) = Unit
    override fun freeBytes(): Long = 0

    @Composable
    override fun rememberImporter(onStart: () -> Unit, onResult: (Result<LocalMap>) -> Unit): () -> Unit {
        val done = rememberUpdatedState(onResult)
        return remember { { done.value(Result.failure(Exception("Offline maps: $LATER"))) } }
    }
}

object WebDownloads : MapDownloads {
    override fun enqueue(entry: CatalogEntry, url: String, wifiOnly: Boolean): String = "Offline maps: $LATER"
    override fun cancel(id: Long) = Unit
    override fun poll(): List<DownloadInfo> = emptyList()
}

private fun cells(bounds: GeoBounds): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (lat in floor(bounds.minLat).toInt()..floor(bounds.maxLat - 1e-9).toInt()) {
        for (lon in floor(bounds.minLon).toInt()..floor(bounds.maxLon - 1e-9).toInt()) out += lat to lon
    }
    return out
}

object PendingTerrain : TerrainData {
    override val state: StateFlow<TerrainState> = MutableStateFlow(TerrainState())
    override fun cellsFor(bounds: GeoBounds) = cells(bounds)
    override fun missingCells(bounds: GeoBounds) = cells(bounds)
    override fun estimateBytes(cells: Int, highDetail: Boolean): Long = 0
    override fun download(label: String, bounds: GeoBounds, highDetail: Boolean): String = "Terrain: $LATER"
    override fun cancel() = Unit
    override fun deleteAll() = Unit
}

object PendingTrails : TrailData {
    override val state: StateFlow<TrailState> = MutableStateFlow(TrailState())
    override fun cellsFor(bounds: GeoBounds) = cells(bounds)
    override fun missingCells(bounds: GeoBounds) = cells(bounds)
    override fun download(label: String, bounds: GeoBounds): String = "Marked trails: $LATER"
    override fun cancel() = Unit
    override fun deleteAll() = Unit
}

object PendingRouting : RoutingData {
    override val state: StateFlow<RoutingDataState> = MutableStateFlow(RoutingDataState())
    override fun isInstalled(name: String) = false
    override fun segmentsFor(bounds: GeoBounds): List<String> = Segments.namesFor(bounds.minLat, bounds.minLon, bounds.maxLat, bounds.maxLon)
    override suspend fun remoteSizes(): Map<String, Long> = emptyMap()
    override fun download(names: List<String>, wifiOnly: Boolean): String = "Offline routing: $LATER"
    override fun cancel(id: Long) = Unit
    override fun delete(name: String) = Unit
    override fun poll() = Unit
}

object PendingRecording : RecordingControl {
    @Composable
    override fun rememberStarter(hasLocation: Boolean, requestLocation: () -> Unit): () -> Unit =
        remember { { Incoming.pending.value = Incoming.Item.Message("Recording tracks: $LATER") } }

    override fun stop() = Unit
}

object PendingRasters : RasterLibrary {
    override val maps: StateFlow<List<RasterMap>> = MutableStateFlow(emptyList())
    override fun get(id: String): RasterMap? = null
    override fun remove(id: String) = Unit

    @Composable
    override fun rememberPicker(onResult: (Result<RasterMap>) -> Unit): () -> Unit {
        val done = rememberUpdatedState(onResult)
        return remember { { done.value(Result.failure(Exception("Raster maps: $LATER"))) } }
    }
}

/** The party (hidden on the web for now); the map reads an empty one. */
object PendingParty : PartyController {
    override val state: StateFlow<PartyState> = MutableStateFlow(PartyState())
    override val events: SharedFlow<PartyEvent> = MutableSharedFlow()
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)
    override fun create(partyName: String, myName: String) = Unit
    override fun join(key: PartyKey, myName: String) = Unit
    override fun leave() = Unit
    override fun setActive(on: Boolean) = Unit
    override fun setSharing(on: Boolean) = Unit
    override fun setProfile(name: String, color: Int, icon: Int) = Unit
    override fun setOnlineEnabled(on: Boolean) = Unit
    override fun setGateway(on: Boolean) = Unit
    override fun setRadio(address: String, name: String) = Unit
    override fun forgetRadio() = Unit
    override fun sos(active: Boolean, text: String) = Unit
    override fun setMeeting(lat: Double, lon: Double, label: String) = Unit
    override fun clearMeeting() = Unit
    override fun sendText(text: String) = Unit
}

object PendingPartyPlatform : PartyPlatform {
    @Composable
    override fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit = remember { {} }

    @Composable
    override fun QrImage(text: String, modifier: Modifier) {
        Box(modifier) { Text(text) }
    }

    @Composable
    override fun RadioPicker(onPick: (address: String, name: String) -> Unit, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun rememberNotificationPermission(): () -> Unit = remember { {} }
}

/** The styles on the web: Topo with its options (as on iPhone). */
class WebMapThemes(topoXml: String?) : MapThemeLibrary {
    private val topoMenu = topoXml?.let { runCatching { StyleMenu.parse(it) }.getOrNull() }
    override val themes: StateFlow<List<MapTheme>> =
        MutableStateFlow(listOf(MapTheme(MapThemes.TOPO, "Topo", "MapAround's hiking style: contours, trails, peaks")))
    override fun menu(themeId: String): StyleMenu? = if (themeId == MapThemes.TOPO) topoMenu else null
    override val canImport = false
    override fun import(name: String?, bytes: ByteArray): Result<List<MapTheme>> =
        Result.failure(UnsupportedOperationException("Importing map styles: $LATER"))
    override fun delete(themeId: String) = Unit
}
