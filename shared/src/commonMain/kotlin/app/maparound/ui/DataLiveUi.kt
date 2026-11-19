package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.i18n.Units
import app.maparound.i18n.ltr
import app.maparound.map.MapSurface
import app.maparound.map.maplibre.MapStyle
import app.maparound.shared.util.nowMillis
import app.maparound.stats.Buses
import app.maparound.stats.Cameras
import app.maparound.stats.Forecast
import app.maparound.stats.RainRadar
import app.maparound.stats.StatsData
import app.maparound.stats.StreetPhotos
import app.maparound.stats.Weather
import app.maparound.stats.WeatherNow
import app.maparound.tools.MapGrid
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// The data view's live layers (the web app): the rain radar, the weather at points over the view,
// street photos, Israel's buses where they are now; and the cards a tap on them opens.

/** The live layers' state: what's on the map now, and when it's from. */
class LiveLayers {
    var radar by mutableStateOf<RainRadar.Frame?>(null)
    var radarFailed by mutableStateOf(false)

    /** The weather at the view's points, and when it was got; null: not yet. */
    var weather by mutableStateOf<List<WeatherNow>?>(null)
    var weatherAt by mutableStateOf(0L)
    var weatherFailed by mutableStateOf(false)
    var hiddenWeather by mutableStateOf(emptySet<Int>())

    var buses by mutableStateOf<List<Buses.Bus>>(emptyList())
    var routes by mutableStateOf<Map<Long, Buses.Route>>(emptyMap())
    var busState by mutableStateOf(LoadState.LOADING)

    /** When the buses' snapshot was taken. */
    var busesAt by mutableStateOf(0L)

    /** The cameras in the view, and the kinds hidden. */
    var cameras by mutableStateOf<List<Cameras.Camera>>(emptyList())
    var cameraState by mutableStateOf(LoadState.LOADING)
    var hiddenCameras by mutableStateOf(emptySet<Int>())

    /** The weather maps: the model's run, the hour shown (its index), whether it couldn't be had. */
    var weatherRun by mutableStateOf<app.maparound.stats.WeatherRun?>(null)
    var weatherStep by mutableStateOf(0)
    var weatherRunFailed by mutableStateOf(false)

    enum class LoadState { LOADING, ZOOM_IN, FAILED, SHOWN }
}

/** Buses show from this zoom (the app's): a city or so. */
private const val BUS_ZOOM = 11.0

/** The weather maps' list of hours is checked again this often (a new model run every 6 hours). */
private const val WEATHER_RUN_CHECK_MS = 30 * 60_000L

/** Cameras show from this zoom (the app's): a region or so, asked for from OpenStreetMap. */
private const val CAMERAS_ZOOM = 10.0

/** OpenStreetMap's servers busy: the cameras are asked for again after this, doubling up to the most. */
private const val CAMERAS_RETRY_MS = 5_000L
private const val CAMERAS_RETRY_MAX_MS = 120_000L

/** The single photos show from this zoom (the app's). */
private const val PHOTOS_ZOOM = StreetPhotos.PHOTOS_ZOOM + 1.0

private const val RADAR_REFRESH_MS = 5 * 60_000L
private const val WEATHER_REFRESH_MS = 15 * 60_000L
private const val BUSES_REFRESH_MS = 60_000L

/**
 * Keeps the live layers [layers] has on up to date while the data view is on: the radar's newest
 * picture, the weather at points over the view (asked again when the view moves), the buses in
 * and around the view (every minute). The points go to [map]; the rasters and photo tiles are the
 * caller's to give it ([LiveLayers.radar], [StatsData.photoTiles]).
 */
@Composable
fun rememberLiveLayers(map: MapSurface, stats: StatsData?, dataView: Boolean, layers: DataLayers): LiveLayers {
    val live = remember { LiveLayers() }
    val radarOn = stats != null && dataView && layers.rainRadar
    LaunchedEffect(stats, radarOn) {
        if (stats == null || !radarOn) {
            live.radar = null
            live.radarFailed = false
            return@LaunchedEffect
        }
        while (true) {
            val frames = stats.radarFrames()
            if (frames != null) live.radar = frames.last()
            live.radarFailed = frames == null && live.radar == null
            delay(RADAR_REFRESH_MS)
        }
    }

    val weatherOn = stats != null && dataView && layers.weather != null
    LaunchedEffect(stats, weatherOn) {
        if (stats == null || !weatherOn) {
            live.weather = null
            live.weatherFailed = false
            map.setDataPoints(MapStyle.DATA_WEATHER, null)
            return@LaunchedEffect
        }
        var asked: List<Pair<Double, Double>>? = null
        var askedAt = 0L
        while (true) {
            val grid = map.visibleBounds()?.let(Weather::grid).orEmpty()
            if (grid.isNotEmpty() && (grid != asked || nowMillis() - askedAt > WEATHER_REFRESH_MS)) {
                // Once the view has stopped moving.
                delay(400)
                if (map.visibleBounds()?.let(Weather::grid).orEmpty() == grid) {
                    val got = stats.weatherNow(grid)
                    if (got != null) {
                        live.weather = got
                        live.weatherAt = nowMillis()
                    }
                    live.weatherFailed = got == null && live.weather == null
                    asked = grid
                    askedAt = nowMillis()
                }
            }
            delay(600)
        }
    }
    val weatherLayer = layers.weather
    LaunchedEffect(live.weather, weatherLayer, live.hiddenWeather, Units.current, I18n.language) {
        val points = live.weather
        map.setDataPoints(MapStyle.DATA_WEATHER, if (points == null || weatherLayer == null) null else Weather.geoJson(points, weatherLayer, live.hiddenWeather))
    }

    val busesOn = stats != null && dataView && layers.buses
    LaunchedEffect(stats, busesOn) {
        if (stats == null || !busesOn) {
            live.buses = emptyList()
            live.busState = LiveLayers.LoadState.LOADING
            map.setDataPoints(MapStyle.DATA_BUSES, null)
            return@LaunchedEffect
        }
        var asked: MapGrid.View? = null
        var askedAt = 0L
        while (true) {
            val view = map.visibleBounds()
            if (map.zoomLevel() < BUS_ZOOM || view == null || Buses.clip(view) == null) {
                live.busState = LiveLayers.LoadState.ZOOM_IN
                if (live.buses.isNotEmpty()) {
                    live.buses = emptyList()
                    map.setDataPoints(MapStyle.DATA_BUSES, null)
                }
                asked = null
            } else if (asked?.let { covers(it, view) } != true || nowMillis() - askedAt > BUSES_REFRESH_MS) {
                if (live.buses.isEmpty()) live.busState = LiveLayers.LoadState.LOADING
                // A bit around the view, so a small move doesn't ask again.
                val area = padded(view, 0.3)
                val got = stats.buses(area)
                asked = area
                askedAt = nowMillis()
                if (got == null) {
                    if (live.buses.isEmpty()) live.busState = LiveLayers.LoadState.FAILED
                } else {
                    val (snapshot, buses) = got
                    live.buses = buses
                    live.busesAt = snapshot.timeMillis
                    live.busState = LiveLayers.LoadState.SHOWN
                    map.setDataPoints(MapStyle.DATA_BUSES, Buses.geoJson(buses, live.routes))
                    val routes = stats.routes(buses.mapTo(HashSet()) { it.lineRef }, snapshot.timeMillis)
                    if (routes != live.routes) {
                        live.routes = routes
                        map.setDataPoints(MapStyle.DATA_BUSES, Buses.geoJson(buses, routes))
                    }
                }
            }
            delay(1_000)
        }
    }
    // The weather maps: the latest run (checked again every half hour), the hour nearest now at
    // first; what to draw goes to the map as one spec.
    val weatherMapsOn = stats != null && dataView && layers.weatherMaps
    LaunchedEffect(stats, weatherMapsOn) {
        if (stats == null || !weatherMapsOn) {
            live.weatherRunFailed = false
            return@LaunchedEffect
        }
        while (true) {
            val run = stats.weatherRun()
            if (run != null && run.runMillis != live.weatherRun?.runMillis) {
                live.weatherRun = run
                live.weatherStep = app.maparound.stats.WeatherMap.nearest(run, nowMillis())
            }
            live.weatherRunFailed = run == null && live.weatherRun == null
            delay(WEATHER_RUN_CHECK_MS)
        }
    }
    val weatherSpec = live.weatherRun?.takeIf { weatherMapsOn }?.let { run ->
        stats?.weatherMapsBase?.let { base -> app.maparound.stats.WeatherMap.spec(base, run, live.weatherStep, layers.weatherMap, layers.windAnimation) }
    }
    LaunchedEffect(weatherSpec) { map.setWeatherMap(weatherSpec) }

    val camerasOn = stats != null && dataView && layers.cameras
    LaunchedEffect(stats, camerasOn) {
        if (stats == null || !camerasOn) {
            live.cameras = emptyList()
            live.cameraState = LiveLayers.LoadState.LOADING
            return@LaunchedEffect
        }
        var shown: MapGrid.View? = null
        // The servers busy: asked again after a while, longer each time (as Overpass asks).
        var retryAt: Long? = null
        var backoff = CAMERAS_RETRY_MS
        while (true) {
            val view = map.visibleBounds()
            if (map.zoomLevel() < CAMERAS_ZOOM || view == null) {
                live.cameraState = LiveLayers.LoadState.ZOOM_IN
                if (live.cameras.isNotEmpty()) live.cameras = emptyList()
                shown = null
            } else if (view != shown || retryAt?.let { nowMillis() >= it } == true) {
                // Once the view has stopped moving.
                delay(400)
                if (map.visibleBounds() == view) {
                    if (live.cameras.isEmpty() && live.cameraState != LiveLayers.LoadState.FAILED) live.cameraState = LiveLayers.LoadState.LOADING
                    val got = stats.cameras(view)
                    if (got == null) {
                        if (live.cameras.isEmpty()) live.cameraState = LiveLayers.LoadState.FAILED
                        retryAt = nowMillis() + backoff
                        backoff = (backoff * 2).coerceAtMost(CAMERAS_RETRY_MAX_MS)
                    } else {
                        live.cameras = got
                        live.cameraState = LiveLayers.LoadState.SHOWN
                        retryAt = null
                        backoff = CAMERAS_RETRY_MS
                    }
                    shown = view
                }
            }
            delay(700)
        }
    }
    LaunchedEffect(camerasOn, live.cameras, live.hiddenCameras) {
        map.setDataPoints(MapStyle.DATA_CAMERAS, if (camerasOn) Cameras.geoJson(live.cameras, live.hiddenCameras) else null)
    }
    return live
}

/** Whether [outer] has all of [inner]. */
private fun covers(outer: MapGrid.View, inner: MapGrid.View): Boolean =
    inner.south >= outer.south && inner.north <= outer.north && inner.west >= outer.west && inner.east <= outer.east

/** [v] grown by [share] of its size each way. */
private fun padded(v: MapGrid.View, share: Double): MapGrid.View {
    val dLat = (v.north - v.south) * share
    val dLon = (v.east - v.west) * share
    return MapGrid.View(v.south - dLat, v.west - dLon, v.north + dLat, v.east + dLon)
}

/** What the live layers that are on say: when their data is from, what to do. */
fun liveNotes(live: LiveLayers, layers: DataLayers, zoom: Double, stats: StatsData): List<String> {
    val l = T.live
    return buildList {
        if (layers.rainRadar) add(
            live.radar?.let { l.radarAt(deviceClock(stats, it.timeMillis)) + " " + l.radarScale }
                ?: if (live.radarFailed) l.couldntGet else l.gettingRadar,
        )
        if (layers.weather != null) add(
            when {
                live.weather != null -> l.weatherAt(deviceClock(stats, live.weatherAt))
                live.weatherFailed -> l.couldntGet
                else -> l.gettingWeather
            },
        )
        if (layers.photos) add(if (zoom < PHOTOS_ZOOM) "${l.photosNote} ${l.photosZoomIn}" else l.photosNote)
        if (layers.buses) add(
            when (live.busState) {
                LiveLayers.LoadState.LOADING -> l.gettingBuses
                LiveLayers.LoadState.ZOOM_IN -> l.busesZoomIn
                LiveLayers.LoadState.FAILED -> l.couldntGet
                LiveLayers.LoadState.SHOWN ->
                    if (live.buses.isEmpty()) l.noBuses else l.busesAt(live.buses.size, deviceClock(stats, live.busesAt))
            },
        )
        if (layers.weatherMaps) add(
            live.weatherRun?.let { r ->
                val t = Instant.fromEpochMilliseconds(r.runMillis).toLocalDateTime(TimeZone.UTC)
                l.weatherMapsNote(Fmt.clock(t.hour, t.minute, null))
            } ?: if (live.weatherRunFailed) l.couldntGet else l.gettingWeatherMaps,
        )
        if (layers.cameras) add(
            when (live.cameraState) {
                LiveLayers.LoadState.LOADING -> l.gettingCameras
                LiveLayers.LoadState.ZOOM_IN -> l.camerasZoomIn
                LiveLayers.LoadState.FAILED -> l.camerasBusy
                LiveLayers.LoadState.SHOWN -> if (live.cameras.isEmpty()) l.noCameras else l.camerasHere(live.cameras.size)
            },
        )
    }
}

/** The credits of the live layers that are on, for the map's credits line. */
fun liveCredits(layers: DataLayers): List<String> {
    val l = T.live
    return listOfNotNull(
        l.radarCredits.takeIf { layers.rainRadar },
        l.weatherCredits.takeIf { layers.weather != null },
        l.photosCredits.takeIf { layers.photos },
        l.busesCredits.takeIf { layers.buses },
        l.camerasCredits.takeIf { layers.cameras },
        l.weatherMapsCredits.takeIf { layers.weatherMaps },
    )
}

/** [millis] by the device's clock ("14:05"): the browser knows its offset; Kotlin's zones don't on the web. */
private fun deviceClock(stats: StatsData, millis: Long): String {
    val t = Instant.fromEpochMilliseconds(millis + (stats.zoneOffset(null, millis) ?: 0) * 1000L).toLocalDateTime(TimeZone.UTC)
    return Fmt.clock(t.hour, t.minute, null)
}

/** [millis] by clocks [offsetSeconds] ahead of UTC, as a date and time there. */
private fun at(millis: Long, offsetSeconds: Int) =
    Instant.fromEpochMilliseconds(millis + offsetSeconds * 1000L).toLocalDateTime(TimeZone.UTC)

/** "5 min ago" */
private fun ago(millis: Long): String {
    val min = (nowMillis() - millis) / 60_000
    return if (min < 1) T.common.justNow.lowercase() else T.common.minutesAgo(min)
}

/** A card's top line: its title (and a line under it), and the close button. */
@Composable
private fun CardTitle(title: String, subtitle: String?, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
    }
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/**
 * A tapped weather point: the weather there now (what it's like, feels like, wind, humidity,
 * clouds), the coming hours every three, the next seven days, and today's sunrise and sunset;
 * times by the clocks there.
 */
@Composable
fun WeatherCard(lat: Double, lon: Double, stats: StatsData, onClose: () -> Unit) {
    val l = T.live
    var forecast by remember(lat, lon) { mutableStateOf<Forecast?>(null) }
    var failed by remember(lat, lon) { mutableStateOf(false) }
    LaunchedEffect(lat, lon) {
        forecast = stats.forecast(lat, lon)
        failed = forecast == null
    }
    val f = forecast
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            CardTitle(
                l.weatherHere,
                listOfNotNull(ltr(Fmt.latLon(lat, lon)), f?.elevationM?.let { l.atHeight(Fmt.height(it)) }).joinToString(" · "),
                onClose,
            )
            if (f == null) {
                Text(if (failed) l.couldntGet else l.gettingWeather, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(end = 12.dp)) {
                f.now?.let { n -> WeatherNowLines(n) }
                val now = nowMillis()
                val hours = f.hours.filter { it.timeMillis >= now - 3_600_000L }.filterIndexed { i, _ -> i % 3 == 0 }.take(8)
                if (hours.isNotEmpty()) {
                    Text(l.nextHours, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (h in hours) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val t = at(h.timeMillis, f.utcOffsetSeconds)
                                Small(Fmt.clock(t.hour, t.minute, null))
                                Text(h.tempC?.let { Weather.degrees(it) } ?: "–", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Small(h.chancePct?.let { ltr("$it%") } ?: "")
                            }
                        }
                    }
                }
                if (f.days.isNotEmpty()) {
                    Text(l.nextDays, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    f.days.forEachIndexed { i, d ->
                        val date = at(d.dayMillis, 0).date
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (i == 0) l.today else "${l.weekdays[date.dayOfWeek.ordinal]} ${Fmt.day(date, withYear = false)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(0.3f),
                            )
                            Text(d.code?.let { l.weatherKinds[Weather.kindOf(it)] }.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.32f))
                            Text(
                                ltr("${d.minC?.let { Weather.degrees(it) } ?: "–"} / ${d.maxC?.let { Weather.degrees(it) } ?: "–"}"),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(0.2f),
                            )
                            Text(
                                d.precipMm?.takeIf { it >= 0.1 }?.let { l.mm(Fmt.oneDecimal(it)) } ?: d.chancePct?.takeIf { it > 0 }?.let { ltr("$it%") } ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(0.18f),
                            )
                        }
                    }
                    val today = f.days.first()
                    val rise = today.sunriseMillis
                    val set = today.sunsetMillis
                    if (rise != null && set != null) {
                        val r = at(rise, f.utcOffsetSeconds)
                        val s = at(set, f.utcOffsetSeconds)
                        Text(
                            l.sun(Fmt.clock(r.hour, r.minute, null), Fmt.clock(s.hour, s.minute, null)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                Small(l.weatherCredit, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The weather now: the temperature and what it's like, then what it feels like, wind, humidity, clouds. */
@Composable
private fun WeatherNowLines(n: WeatherNow) {
    val l = T.live
    Row(verticalAlignment = Alignment.Bottom) {
        n.tempC?.let { Text(ltr(Weather.degrees(it)), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium) }
        n.code?.let {
            Text(l.weatherKinds[Weather.kindOf(it)], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 10.dp, bottom = 3.dp))
        }
    }
    val wind = n.windKmh?.let { kmh ->
        val dir = n.windFrom?.takeIf { kmh >= 1 }?.let { Fmt.heading(it.toFloat()) }.orEmpty()
        listOfNotNull(
            l.wind(dir, Fmt.speed(kmh / 3.6)).replace("  ", " "),
            n.gustKmh?.takeIf { it > kmh + 5 }?.let { l.gusts(Fmt.speed(it / 3.6)) },
        ).joinToString(", ")
    }
    Text(
        listOfNotNull(n.feelsC?.let { l.feelsLike(Weather.degrees(it)) }, wind).joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
    )
    val more = listOfNotNull(n.humidityPct?.let(l::humidity), n.cloudPct?.let(l::clouds), n.precipMm?.takeIf { it > 0 }?.let { l.mm(Fmt.oneDecimal(it)) })
    if (more.isNotEmpty()) Text(more.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
}

/**
 * A tapped street photo: the picture (tapped: bigger; an all-round one can be looked around by
 * dragging), when and by whom it was taken, its licence, and the ways to Panoramax's own viewer
 * and to Google Street View there.
 */
@Composable
fun PhotoCard(id: String, stats: StatsData, onClose: () -> Unit) {
    val l = T.live
    val platform = LocalPlatform.current
    var pic by remember(id) { mutableStateOf<StreetPhotos.Picture?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var big by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        pic = stats.photo(id)
        failed = pic == null
    }
    val p = pic
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
            CardTitle(if (p?.panorama == true) l.panorama else l.streetPhotos, p?.instance, onClose)
            if (p == null) {
                Text(if (failed) l.couldntGet else l.loading, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                return@Column
            }
            p.picture?.let { url ->
                RemoteImage(
                    url,
                    l.streetPhotos,
                    Modifier.padding(end = 12.dp, top = 4.dp).fillMaxWidth().height(if (p.panorama) 150.dp else 190.dp).clickable { big = true },
                    ContentScale.Crop,
                )
            }
            val date = p.timeMillis?.let { at(it, stats.zoneOffset(null, it) ?: 0) }
            Text(
                listOfNotNull(
                    date?.let { l.takenOn(Fmt.day(it.date, withYear = true) + " " + Fmt.clock(it.hour, it.minute, null)) },
                    p.author?.let { T.map.photoBy(it) },
                    p.license,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp, end = 12.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                TextButton(onClick = { platform.openUrl(StreetPhotos.viewerUrl(p)) }) { Text(l.openInPanoramax) }
                TextButton(onClick = { platform.openUrl(StreetPhotos.streetViewUrl(p.lat, p.lon, p.heading)) }) { Text(l.streetView) }
            }
        }
    }
    if (big && p?.picture != null) PhotoBig(p, onDismiss = { big = false })
}

/** A street photo over the whole screen; an all-round one at the screen's height, dragged sideways to look around. */
@Composable
private fun PhotoBig(p: StreetPhotos.Picture, onDismiss: () -> Unit) {
    val url = p.picture ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(if (p.panorama) T.live.panorama else T.live.streetPhotos, p.author).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                    Tip(T.common.close) { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (p.panorama) {
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            RemoteImage(url, null, Modifier.fillMaxHeight().aspectRatio(2f), ContentScale.Fit, placeholder = Color.Black)
                        }
                    } else {
                        p.thumb?.let { RemoteImage(it, null, Modifier.fillMaxSize(), ContentScale.Fit, placeholder = Color.Black) }
                        RemoteImage(url, null, Modifier.fillMaxSize(), ContentScale.Fit, placeholder = Color.Transparent)
                    }
                }
                Text(
                    listOfNotNull(T.live.photoCredit, p.license).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** A tapped bus: its line, operator and where it goes, how fast, when it was there, its number. */
@Composable
fun BusCard(bus: Buses.Bus, route: Buses.Route?, stats: StatsData, onClose: () -> Unit) {
    val l = T.live
    fun place(p: Buses.Place) = if (p.stop.isBlank()) p.city else "${p.city} (${p.stop})"
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
            CardTitle(
                if (route != null && route.number.isNotBlank()) l.line(route.number) else l.buses,
                route?.operator,
                onClose,
            )
            if (route == null) Text(l.unknownLine, style = MaterialTheme.typography.bodyMedium)
            route?.to?.let { Text(l.to(place(it)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
            route?.from?.let { Text(l.from(place(it)), style = MaterialTheme.typography.bodySmall) }
            Text(
                listOfNotNull(
                    bus.speedKmh?.let { if (it == 0) l.stopped else Fmt.speed(it / 3.6) },
                    bus.bearing?.takeIf { (bus.speedKmh ?: 1) > 0 }?.let { Fmt.heading(it.toFloat()) },
                    l.positionAt(deviceClock(stats, bus.timeMillis), ago(bus.timeMillis)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            bus.vehicle?.let { Text(l.vehicle(it), style = MaterialTheme.typography.bodySmall) }
            Small(l.busesCredit, Modifier.padding(top = 6.dp, end = 12.dp))
        }
    }
}

/**
 * A tapped camera: what it is and its name, the speed limit it enforces, which way it looks, what
 * it watches, who runs it; its webcam's page, and its page on OpenStreetMap.
 */
@Composable
fun CameraCard(c: Cameras.Camera, onClose: () -> Unit) {
    val l = T.live
    val platform = LocalPlatform.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
            CardTitle(l.cameraKinds[c.kind.ordinal], c.name, onClose)
            val facts = listOfNotNull(
                // OpenStreetMap's limits are in km/h unless they say ("50 mph"): shown in the speed unit chosen.
                c.maxspeed?.takeIf { c.kind == Cameras.Kind.SPEED }?.let { v -> v.toDoubleOrNull()?.let { Fmt.speed(it / 3.6) } ?: v }?.let(l::speedLimit),
                c.direction?.let { l.facing(Fmt.heading(it.toFloat())) },
                c.watches?.let(l::watches),
                l.readsPlates.takeIf { c.type.equals("ALPR", ignoreCase = true) },
                c.operator?.let(l::operatedBy),
            )
            for (f in facts) Text(f, style = MaterialTheme.typography.bodyMedium)
            Small(l.camerasCredit, Modifier.padding(top = 6.dp, end = 12.dp))
            Row {
                c.webcam?.let { url -> TextButton(onClick = { platform.openUrl(url) }) { Text(l.openWebcam) } }
                TextButton(onClick = { platform.openUrl(Cameras.osmUrl(c)) }) { Text(l.openInOsm) }
            }
        }
    }
}

/**
 * The weather map's hour: a slider over the forecast's hours (every 3, the next 48), with the day
 * and time shown (by the device's clock) and how far from now.
 */
@Composable
fun WeatherTimeRow(live: LiveLayers, stats: StatsData) {
    val run = live.weatherRun ?: return
    val steps = run.steps
    if (steps.isEmpty()) return
    val i = live.weatherStep.coerceIn(steps.indices)
    val at = steps[i].timeMillis
    val offset = stats.zoneOffset(null, at) ?: 0
    val t = Instant.fromEpochMilliseconds(at + offset * 1000L).toLocalDateTime(TimeZone.UTC)
    val hours = ((at - nowMillis()) / 3_600_000.0).let { kotlin.math.round(it).toInt() }
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOf(
                "${T.live.weekdays[t.date.dayOfWeek.ordinal]} ${Fmt.clock(t.hour, t.minute, null)}",
                if (kotlin.math.abs(hours) <= 1) T.live.now else T.live.hoursFromNow(hours),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(150.dp),
        )
        androidx.compose.material3.Slider(
            value = i.toFloat(),
            onValueChange = { live.weatherStep = it.roundToInt().coerceIn(steps.indices) },
            valueRange = 0f..(steps.size - 1).toFloat(),
            steps = (steps.size - 2).coerceAtLeast(0),
            modifier = Modifier.weight(1f),
        )
    }
}

