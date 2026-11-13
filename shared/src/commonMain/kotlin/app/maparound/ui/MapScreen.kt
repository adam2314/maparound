package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import app.maparound.data.mapBounds
import app.maparound.terrain.GeoBounds
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.data.MapAroundMaps
import app.maparound.data.MapPos
import app.maparound.map.BaseMap
import app.maparound.map.LocalMapThemes
import app.maparound.map.MapTheme
import app.maparound.map.MapThemeLibrary
import app.maparound.map.MapThemes
import app.maparound.map.SourceList
import app.maparound.map.StyleChoice
import app.maparound.map.categories
import app.maparound.map.MapListener
import app.maparound.map.MapSurface
import app.maparound.map.OnlineSource
import app.maparound.map.PartyMarker
import app.maparound.map.PlaceMarker
import app.maparound.map.TileSources
import app.maparound.map.ToolLine
import app.maparound.map.backgroundAttribution
import app.maparound.party.LocalParty
import app.maparound.party.PartyState
import app.maparound.places.LocalPlaces
import app.maparound.raster.LocalRasters
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.MapFocus
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.routing.RouteResult
import app.maparound.sensors.GeoFix
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.share.Incoming
import app.maparound.shared.ioDispatcher
import app.maparound.shared.util.Xml
import app.maparound.shared.util.nowMillis
import app.maparound.tools.Geodesy
import app.maparound.tools.ToolsSession
import app.maparound.tracks.LocalTracks
import app.maparound.tracks.TrackRecorder
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MapScreen(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    /** Goes to the Offline tab; null where there's none. */
    onOpenOffline: (() -> Unit)?,
    snackbar: SnackbarHostState,
    /** Makes the platform's map (Android: mapsforge; iOS: MapLibre). */
    createMap: () -> MapSurface,
    /** Shows the map made by [createMap]. */
    mapView: @Composable (MapSurface, Modifier) -> Unit,
    /** The platform's 3D view, for the 3D button. */
    view3d: @Composable (Scene3DViewArgs) -> Unit,
) {
    val prefs = LocalPrefs.current
    val offline = LocalOfflineData.current
    val platform = LocalPlatform.current
    val recording = LocalRecordingControl.current
    val partyControl = LocalParty.current
    val placesRepo = LocalPlaces.current
    val tracksRepo = LocalTracks.current
    val rastersLib = LocalRasters.current
    val sensors = LocalDeviceSensors.current
    val scope = rememberCoroutineScope()

    val controller = remember { createMap().apply { moveTo(prefs.lastPosition() ?: MapPos(30.0, 10.0, 3)) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    controller.onPause()
                    prefs.saveLastPosition(controller.position())
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            prefs.saveLastPosition(controller.position())
            controller.destroy()
        }
    }

    // ---- base layer --------------------------------------------------------------------------
    val baseLayerId by prefs.baseLayer.collectAsState()
    val customSources by prefs.customSources.collectAsState()
    val localMaps by offline.maps.files.collectAsState()
    val onlineSource = TileSources.find(baseLayerId, customSources)
    // "offline" (or an id that no longer exists) resolves to no online source -> render offline maps.
    val showingOffline = onlineSource == null
    // The offline maps' style and its options; a style that's gone (deleted, or not on this
    // platform) falls back to the first.
    val themeLib = LocalMapThemes.current
    val themes by themeLib.themes.collectAsState()
    val chosenTheme by prefs.mapTheme.collectAsState()
    val styleChoices by prefs.styleChoices.collectAsState()
    val theme = chosenTheme.takeIf { id -> themes.any { it.id == id } } ?: themes.firstOrNull()?.id ?: MapThemes.TOPO
    val categories = remember(theme, styleChoices, themes) { themeLib.categories(theme, styleChoices) }
    val contours by prefs.contours.collectAsState()
    val hillshade by prefs.hillshade.collectAsState()
    val terrain by offline.terrain.state.collectAsState()
    val trailsOn by prefs.trails.collectAsState()
    val trailData by offline.trails.state.collectAsState()
    val background by prefs.mapBackground.collectAsState()
    val vectorOnBackground by prefs.vectorOnBackground.collectAsState()
    val rasterMaps by rastersLib.maps.collectAsState()
    val baseMap: BaseMap = onlineSource?.let { BaseMap.Online(it) } ?: BaseMap.Offline(
        maps = localMaps,
        theme = theme,
        categories = categories,
        contours = contours,
        hillshade = hillshade,
        terrainVersion = terrain.version,
        trails = trailsOn,
        trailsVersion = trailData.version,
        background = background,
        vectorOnTop = vectorOnBackground,
        rasterMaps = rasterMaps,
    )
    LaunchedEffect(baseMap) { controller.setBaseMap(baseMap) }
    val terrainShown = showingOffline && terrain.tiles > 0 && (contours || hillshade)

    // Terrain for the contour lines and hill shading: fetched by itself where the offline maps
    // are, a few tiles at most, once the map shows an area closely enough (as the 3D view does).
    val mapAreas by produceState(emptyList<GeoBounds>(), localMaps) {
        value = withContext(Dispatchers.Default) { localMaps.filterNot { it.isWorld }.mapNotNull { mapBounds(it.path) } }
    }
    // Tiles asked for already (the sea has none: not again and again).
    val terrainAsked = remember { HashSet<Pair<Int, Int>>() }
    LaunchedEffect(showingOffline, contours || hillshade, mapAreas) {
        if (!showingOffline || !(contours || hillshade) || mapAreas.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(AUTO_TERRAIN_CHECK_MS)
            if (offline.terrain.state.value.progress != null) continue
            val pos = controller.position()
            if (pos.zoom < AUTO_TERRAIN_ZOOM) continue
            val view = around(pos, controller.metresPerDp() * AUTO_TERRAIN_HALF_DP)
            for (area in mapAreas) {
                val part = overlap(view, area) ?: continue
                val missing = offline.terrain.missingCells(part).filterNot { it in terrainAsked }
                if (missing.isEmpty()) continue
                if (offline.terrain.ensure(AUTO_TERRAIN_LABEL, part) == null) terrainAsked += missing
                break
            }
        }
    }
    // Marked trails: drawn from downloaded data or the trails built into MapAround's maps in the
    // Topo style, otherwise the online overlay.
    val mapsWithTrails by produceState(false, localMaps) {
        value = withContext(Dispatchers.Default) { localMaps.any { MapAroundMaps.hasTrails(it.path) } }
    }
    val localTrails = trailsOn && showingOffline && MapThemes.drawsTerrain(theme) && (trailData.cells > 0 || mapsWithTrails)
    val trailOverlay = trailsOn && !localTrails
    LaunchedEffect(trailOverlay) { controller.setTrailOverlay(trailOverlay) }

    // ---- routes & tracks ------------------------------------------------------------------------
    val planner by PlannerSession.state.collectAsState()
    val nav by NavigationSession.state.collectAsState()
    val navVoice by prefs.navVoice.collectAsState()
    val recorder by TrackRecorder.state.collectAsState()
    val savedTracks by tracksRepo.tracks.collectAsState()
    val visibleTracks by tracksRepo.visible.collectAsState()
    LaunchedEffect(planner.active, planner.route, planner.waypoints, planner.preview, nav.route) {
        val line = when {
            nav.route != null -> nav.route!!.points
            !planner.active -> emptyList()
            planner.preview != null -> planner.preview!!
            planner.route != null -> planner.route!!.points
            planner.profile == RouteProfile.LINE -> planner.waypoints
            else -> emptyList()
        }
        controller.setPlannedRoute(line, if (planner.active) planner.waypoints else emptyList())
    }
    LaunchedEffect(savedTracks, visibleTracks) {
        controller.setShownTracks(savedTracks.filter { it.id in visibleTracks }.associate { it.id to it.track.points })
    }
    LaunchedEffect(recorder.points) { controller.setRecording(recorder.points) }
    val focus by MapFocus.request.collectAsState()
    LaunchedEffect(focus) {
        focus?.let {
            controller.showPoints(it)
            MapFocus.consumed()
        }
    }
    val startRecording = recording.rememberStarter(hasLocation, requestLocation)
    var saveDialog by remember { mutableStateOf(false) }

    // ---- map tools (azimuth, line of sight, viewshed, coordinates) --------------------------------
    val tools by ToolsSession.state.collectAsState()
    LaunchedEffect(tools.active, tools.mode, tools.a, tools.b, tools.los, tools.viewshed) {
        if (!tools.active) {
            controller.setTools(null, null, emptyList(), null, null)
            return@LaunchedEffect
        }
        val a = tools.a
        val b = tools.b?.takeIf { tools.usesB }
        val lines = when {
            a == null || b == null -> emptyList()
            tools.mode == ToolsSession.Mode.SIGHT && tools.los != null -> {
                // Runs of ground that A can / can't see.
                val out = ArrayList<ToolLine>()
                var run = ArrayList<GeoPoint>()
                var vis: Boolean? = null
                for (s in tools.los!!.samples) {
                    val p = GeoPoint(s.lat, s.lon)
                    if (vis != null && s.visible != vis) {
                        run += p
                        out += ToolLine(run, vis)
                        run = arrayListOf(p)
                    } else {
                        run += p
                    }
                    vis = s.visible
                }
                if (run.size >= 2) out += ToolLine(run, vis)
                out
            }
            else -> listOf(ToolLine(Geodesy.greatCircle(a, b), null))
        }
        val ring = if (tools.mode == ToolsSession.Mode.VIEW && a != null) a to (tools.viewshed?.radiusM ?: tools.radiusM) else null
        controller.setTools(a, b, lines, ring, tools.viewshed?.takeIf { tools.mode == ToolsSession.Mode.VIEW })
    }

    // ---- party ------------------------------------------------------------------------------------
    val party by partyControl.state.collectAsState()
    val partyClock by produceState(nowMillis()) {
        while (true) {
            delay(30_000)
            value = nowMillis()
        }
    }
    var selectedParty by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(party, partyClock) { controller.setPartyMarkers(partyMapMarkers(party, partyClock)) }

    // ---- saved places --------------------------------------------------------------------------------
    val places by placesRepo.places.collectAsState()
    val placesOn by prefs.placesOnMap.collectAsState()
    var selectedPlace by remember { mutableStateOf<String?>(null) }
    var editPlace by remember { mutableStateOf<Pair<app.maparound.places.Place, Boolean>?>(null) } // place, isNew
    LaunchedEffect(places, placesOn) { controller.setPlaces(if (placesOn) placeMapMarkers(places) else emptyList()) }
    val placeFocus by placesRepo.focus.collectAsState()
    LaunchedEffect(placeFocus) {
        placeFocus?.let {
            selectedPlace = it
            placesRepo.focus.value = null
        }
    }
    fun newPlace(lat: Double, lon: Double, name: String?) {
        editPlace = app.maparound.places.Place(
            id = app.maparound.places.Place.newId(), name = name.orEmpty(), lat = lat, lon = lon,
            category = app.maparound.places.PlaceCategory.FAVORITE,
        ) to true
    }

    // ---- my location ---------------------------------------------------------------------------
    val locationFlow: Flow<GeoFix?> = remember(hasLocation) {
        if (hasLocation) sensors.locations() else flowOf(null)
    }
    val location by locationFlow.collectAsStateWithLifecycle(initialValue = null)
    var follow by rememberSaveable { mutableStateOf(false) }
    var pin by remember { mutableStateOf<GeoPoint?>(null) }
    var pinLabel by remember { mutableStateOf<String?>(null) }
    // The point of interest the pin is on (tapped on the map), while the pin stays there.
    var pinPoi by remember { mutableStateOf<app.maparound.map.MapPoi?>(null) }
    // "What's here?" open for a point (and the point of interest there).
    var whatsHere by remember { mutableStateOf<Pair<GeoPoint, app.maparound.map.MapPoi?>?>(null) }
    // The pinned place's own Wikipedia article and its picture, when its tags name one.
    var pinArticle by remember { mutableStateOf<Pair<app.maparound.map.MapPoi, app.maparound.search.Wikipedia.Article>?>(null) }
    // A photo over the whole screen.
    var viewPhoto by remember { mutableStateOf<app.maparound.search.Commons.Photo?>(null) }
    LaunchedEffect(pinPoi) {
        val place = pinPoi?.takeIf { it.hasArticle } ?: return@LaunchedEffect
        if (pinArticle?.first == place) return@LaunchedEffect
        // Which article (in the user's language when there is one), then its picture.
        val a = app.maparound.search.Wikipedia.articleOf(place, app.maparound.shared.util.deviceLanguage()) ?: return@LaunchedEffect
        pinArticle = place to a
        pinArticle = place to app.maparound.search.Wikipedia.withPicture(a)
    }
    var showMe by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    // (lat, lon, label) for the share sheet
    var shareTarget by remember { mutableStateOf<Triple<Double, Double, String?>?>(null) }
    SideEffect {
        controller.listener = object : MapListener {
            override fun onUserGesture() {
                follow = false
            }

            override fun onLongPress(point: GeoPoint) {
                // In the planner, a long press on a stop or the line picks it up to drag.
                val planning = PlannerSession.state.value.active && !NavigationSession.state.value.active &&
                    !ToolsSession.state.value.active
                if (planning && PlannerSession.grab(point, controller.metresPerDp())) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    return
                }
                pin = point
                pinLabel = null
            }

            override fun onLongPressMove(point: GeoPoint) = PlannerSession.drag(point)

            override fun onLongPressEnd(point: GeoPoint) = PlannerSession.drop(point)

            override fun onMyLocationTap() {
                showMe = true
            }

            override fun onTap(point: GeoPoint): Boolean = when {
                ToolsSession.state.value.active -> {
                    ToolsSession.tap(point)
                    true
                }
                PlannerSession.state.value.active && !NavigationSession.state.value.active -> {
                    PlannerSession.add(point)
                    true
                }
                else -> false
            }

            override fun onPoiTap(poi: app.maparound.map.MapPoi) {
                // The pin on the point of interest, with what it is.
                showMe = false
                selectedPlace = null
                selectedParty = null
                pin = poi.point
                pinLabel = poi.title
                pinPoi = poi
            }

            override fun onWaypointTap(index: Int) {
                if (!NavigationSession.state.value.active) PlannerSession.removeAt(index)
            }

            override fun onPartyMarkerTap(key: String) {
                selectedParty = key
            }

            override fun onPlaceTap(id: String) {
                selectedPlace = id
            }
        }
    }
    LaunchedEffect(location) {
        val loc = location
        controller.updateLocation(loc)
        // The background service feeds navigation too; the same fix counts once.
        if (loc != null && NavigationSession.state.value.active) NavigationSession.onFix(loc)
        if (follow && loc != null) controller.animateTo(loc.lat, loc.lon)
    }
    LaunchedEffect(nav.active) {
        if (nav.active) {
            follow = true
            pin = null
            showMe = false
            location?.let { controller.animateTo(it.lat, it.lon, minZoom = if (nav.profile == RouteProfile.CAR) 16 else 17) }
        }
    }
    if (nav.active) KeepScreenOn()
    LaunchedEffect(pin) { controller.setPin(pin) }

    // ---- things other apps shared with us ---------------------------------------------------------
    val incoming by Incoming.pending.collectAsState()
    LaunchedEffect(incoming) {
        when (val i = incoming) {
            is Incoming.Item.Point -> {
                Incoming.consume()
                val p = i.point
                follow = false
                pin = GeoPoint(p.lat, p.lon)
                pinLabel = p.label
                controller.moveTo(MapPos(p.lat, p.lon, p.zoom ?: maxOf(controller.position().zoom, 15)))
            }
            is Incoming.Item.Route -> {
                Incoming.consume()
                follow = false
                PlannerSession.load(i.route.points, i.route.profile)
                controller.showPoints(i.route.points)
            }
            is Incoming.Item.Track -> {
                Incoming.consume()
                follow = false
                controller.showPoints(i.saved.track.points)
                snackbar.showSnackbar(
                    "Imported “${i.saved.track.name}” into Library" +
                        if (i.placesAdded > 0) " and ${i.placesAdded} waypoint${if (i.placesAdded == 1) "" else "s"} into Places" else "",
                )
            }
            is Incoming.Item.Places -> {
                Incoming.consume()
                follow = false
                prefs.setPlacesOnMap(true)
                controller.showPoints(i.points)
                snackbar.showSnackbar(if (i.added == 0) "Those places are already saved" else "Added ${i.added} place${if (i.added == 1) "" else "s"} to Library → Places")
            }
            else -> Unit
        }
    }


    var showLayers by remember { mutableStateOf(false) }
    var scene3d by remember { mutableStateOf<app.maparound.three.Camera3D?>(null) }
    var searching by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        mapView(controller, Modifier.fillMaxSize())

        if (onOpenOffline != null && showingOffline && localMaps.isEmpty() && !planner.active && !tools.active && !nav.active) {
            ElevatedCard(
                Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 72.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No offline maps yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Download a country to browse without internet, or pick an online map in Layers.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenOffline) { Text("Get offline maps") }
                }
            }
        }

        Column(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallFloatingActionButton(onClick = { searching = true }) {
                Icon(Icons.Filled.Search, contentDescription = "Search places")
            }
            SmallFloatingActionButton(onClick = { showLayers = true }) {
                Icon(Icons.Filled.Layers, contentDescription = "Map layers")
            }
            if (!nav.active) SmallFloatingActionButton(
                onClick = {
                    if (planner.active) {
                        PlannerSession.close()
                    } else {
                        ToolsSession.close()
                        PlannerSession.open()
                    }
                },
                containerColor = if (planner.active) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (planner.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Route, contentDescription = "Plan a route")
            }
            if (!nav.active) SmallFloatingActionButton(
                onClick = {
                    if (tools.active) {
                        ToolsSession.close()
                    } else {
                        PlannerSession.close()
                        ToolsSession.open()
                    }
                },
                containerColor = if (tools.active) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (tools.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Straighten, contentDescription = "Map tools: azimuth, line of sight, viewshed, coordinates")
            }
            SmallFloatingActionButton(onClick = {
                follow = false
                controller.camera3D()?.let { scene3d = it }
            }) {
                Icon(Icons.Filled.ViewInAr, contentDescription = "3D view of this area")
            }
        }

        if (nav.active) {
            // Left of the button column on the right.
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 64.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NavigationBanner(nav, Modifier.fillMaxWidth())
                if (recorder.recording) RecordingBar(recorder, onStop = { recording.stop() })
            }
        } else if (recorder.recording) {
            RecordingBar(
                recorder,
                onStop = { recording.stop() },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        } else {
            terrain.progress?.takeIf { it.label == AUTO_TERRAIN_LABEL }?.let { p ->
                // Terrain coming in for the contours and shading.
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 64.dp, end = 64.dp),
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Getting terrain for contours" + (if (p.total > 1) " (${minOf(p.done + 1, p.total)} of ${p.total})" else "") + "…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp)) {
            if (showMe) {
                location?.let { me ->
                    PinCard(
                        GeoPoint(me.lat, me.lon), "My location" + (me.accuracyM?.let { " (±${it.roundToInt()} m)" } ?: ""),
                        null, onClose = { showMe = false },
                        onRoute = null,
                        onShare = { shareTarget = Triple(me.lat, me.lon, "My location") },
                        onSavePlace = { newPlace(me.lat, me.lon, null) },
                        onTool = { mode ->
                            showMe = false
                            PlannerSession.close()
                            ToolsSession.open(mode, GeoPoint(me.lat, me.lon))
                        },
                    )
                } ?: run { showMe = false }
            }
            selectedPlace?.let { id -> placesRepo.get(id) }?.let { pl ->
                PlaceCard(
                    pl, location,
                    onClose = { selectedPlace = null },
                    onRoute = {
                        selectedPlace = null
                        ToolsSession.close()
                        PlannerSession.close()
                        PlannerSession.open()
                        location?.let { PlannerSession.add(GeoPoint(it.lat, it.lon)) }
                        PlannerSession.add(pl.point)
                    },
                    onShare = { platform.sharePlaces(listOf(pl), pl.name) },
                    onEdit = { editPlace = pl to false },
                    onDelete = { placesRepo.delete(pl.id); selectedPlace = null },
                    onViewshed = {
                        selectedPlace = null
                        PlannerSession.close()
                        ToolsSession.open(ToolsSession.Mode.VIEW, pl.point)
                    },
                )
            }
            selectedParty?.let { key ->
                PartyMarkerCard(key, party, location, onClose = { selectedParty = null }, onShare = { la, lo, title -> shareTarget = Triple(la, lo, title) }, onRoute = { target ->
                    selectedParty = null
                    ToolsSession.close()
                    PlannerSession.close()
                    PlannerSession.open()
                    location?.let { PlannerSession.add(GeoPoint(it.lat, it.lon)) }
                    PlannerSession.add(target)
                })
            }
            pin?.let { p ->
                val poi = pinPoi?.takeIf { it.point == p }
                val article = pinArticle?.takeIf { it.first == poi }?.second
                PinCard(
                    p, pinLabel, location, onClose = { pin = null },
                    details = poi?.details,
                    picture = article?.picture,
                    onPicture = { viewPhoto = article?.picture },
                    onWhatsHere = { whatsHere = p to poi },
                    onWikipedia = poi?.takeIf { it.hasArticle }?.let { place ->
                        {
                            // Its article in the user's language when there is one (Wikidata knows).
                            val known = article?.url
                            if (known != null) {
                                platform.openUrl(known)
                            } else {
                                scope.launch {
                                    app.maparound.search.Wikipedia.linkFor(place, app.maparound.shared.util.deviceLanguage())?.let(platform::openUrl)
                                }
                            }
                        }
                    },
                    onRoute = {
                        pin = null
                        ToolsSession.close()
                        PlannerSession.close()
                        PlannerSession.open()
                        location?.let { PlannerSession.add(GeoPoint(it.lat, it.lon)) }
                        PlannerSession.add(p)
                    },
                    onShare = { shareTarget = Triple(p.lat, p.lon, pinLabel) },
                    onSavePlace = { newPlace(p.lat, p.lon, pinLabel) },
                    onTool = { mode ->
                        pin = null
                        PlannerSession.close()
                        ToolsSession.open(mode, p)
                    },
                    onMeetHere = if (party.active && !partyControl.watchOnly) ({
                        partyControl.setMeeting(p.lat, p.lon, pinLabel ?: "Meeting point")
                        pin = null
                    }) else null,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = listOfNotNull(
                    if (!showingOffline) onlineSource?.attribution else "© OpenStreetMap contributors · mapsforge",
                    if (showingOffline) backgroundAttribution(background, rasterMaps) else null,
                    if (terrainShown) "terrain: Mapzen, AWS Terrain Tiles" else null,
                    if (trailOverlay) TileSources.trailsOverlay.attribution else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(
            Modifier.padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!recorder.recording) {
                SmallFloatingActionButton(onClick = startRecording) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = "Record a track", tint = Color(0xFFD50000))
                }
            }
            FloatingActionButton(
                onClick = {
                    if (!hasLocation) {
                        requestLocation()
                    } else {
                        follow = true
                        val loc = location
                        if (loc != null) {
                            controller.animateTo(loc.lat, loc.lon, minZoom = 15)
                        } else {
                            scope.launch { snackbar.showSnackbar("Waiting for a location fix…") }
                        }
                    }
                },
                containerColor = if (follow) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (follow) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    if (follow) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                    contentDescription = "My location",
                )
            }
        }
        }
        if (nav.active) {
            NavigationPanel(
                nav,
                voice = navVoice,
                onVoice = {
                    prefs.setNavVoice(it)
                    NavigationSession.voiceChanged(it)
                },
                onStop = { NavigationSession.stop() },
            )
        } else if (planner.active) {
            PlannerPanel(
                planner,
                onSave = { saveDialog = true },
                onShare = {
                    PlannerSession.currentTrack("Route " + Fmt.dayMonth(nowMillis()))?.let { platform.shareRoute(it, null) }
                },
                onNavigate = {
                    val route = planner.route
                    when {
                        !hasLocation -> requestLocation()
                        route != null -> {
                            ToolsSession.close()
                            NavigationSession.start(route, planner.waypoints, planner.profile)
                        }
                    }
                },
                onClose = { PlannerSession.close() },
            )
        }
        if (tools.active) {
            ToolsPanel(
                tools,
                myLocation = location,
                mapCenter = { controller.position().let { GeoPoint(it.lat, it.lon) } },
                onShowPoint = { p ->
                    follow = false
                    controller.animateTo(p.lat, p.lon, minZoom = 14)
                },
                onShare = { p -> shareTarget = Triple(p.lat, p.lon, null) },
                onGetTerrain = onOpenOffline,
            )
        }
        }
    }

    shareTarget?.let { (la, lo, label) -> PointShareSheet(la, lo, label, onDismiss = { shareTarget = null }) }

    whatsHere?.let { (p, poi) ->
        // The places the map draws around the point (offline), nearest first.
        val around = remember(p) { controller.poisNear(p, WHATS_HERE_RADIUS_DP) }
        WhatsHereSheet(p, poi, around, onShow = { other ->
            whatsHere = null
            pin = other.point
            pinLabel = other.title
            pinPoi = other
            controller.animateTo(other.lat, other.lon)
        }, onShowPoint = { at, label ->
            // Where a photo was taken.
            whatsHere = null
            pin = at
            pinLabel = label
            pinPoi = null
            controller.animateTo(at.lat, at.lon)
        }, onDismiss = { whatsHere = null }, known = pinArticle?.takeIf { it.first == poi }?.second)
    }

    viewPhoto?.let { photo -> PhotoViewer(photo, onDismiss = { viewPhoto = null }) }

    scene3d?.let { req ->
        Scene3DScreen(req, onClose = { last ->
            scene3d = null
            // The 2D map comes back where the 3D view was looking.
            last?.let(controller::showFrom3D)
        }) { view3d(it) }
    }

    if (searching) {
        SearchOverlay(
            mapCenter = controller.position().let { GeoPoint(it.lat, it.lon) },
            myLocation = location,
            onPick = { r ->
                searching = false
                follow = false
                if (r.placeId != null) {
                    prefs.setPlacesOnMap(true)
                    selectedPlace = r.placeId
                    pin = null
                } else {
                    pin = GeoPoint(r.lat, r.lon)
                    pinLabel = r.name
                }
                controller.moveTo(MapPos(r.lat, r.lon, maxOf(controller.position().zoom, 14)))
            },
            onClose = { searching = false },
        )
    }

    editPlace?.let { (pl, isNew) ->
        PlaceEditDialog(pl, isNew, onDismiss = { editPlace = null }, onSave = {
            placesRepo.save(it)
            editPlace = null
            if (isNew) {
                pin = null
                showMe = false
                prefs.setPlacesOnMap(true)
                scope.launch { snackbar.showSnackbar("Saved “${it.name}” to Places") }
            }
        })
    }

    if (saveDialog) {
        SaveRouteDialog(
            suggested = (if (planner.profile == RouteProfile.LINE) "Drawn route " else "Route ") +
                Fmt.dayMonthTime(nowMillis()),
            onDismiss = { saveDialog = false },
            onSave = { name ->
                saveDialog = false
                PlannerSession.save(name)
                PlannerSession.close()
                scope.launch { snackbar.showSnackbar("Saved \"$name\" to Routes") }
            },
        )
    }

    if (showLayers) {
        LayersSheet(
            selectedId = if (showingOffline) TileSources.OFFLINE_ID else baseLayerId,
            custom = customSources,
            offlineCount = localMaps.size,
            theme = theme,
            contours = contours,
            hillshade = hillshade,
            terrainTiles = terrain.tiles,
            trails = trailsOn,
            localTrails = trailData.cells > 0,
            places = placesOn,
            background = background,
            vectorOnBackground = vectorOnBackground,
            rasterMaps = rasterMaps,
            onRasterError = { e -> scope.launch { snackbar.showSnackbar(rasterError(e)) } },
            onMessage = { m -> scope.launch { snackbar.showSnackbar(m) } },
            onOpenOffline = onOpenOffline?.let { open ->
                {
                    showLayers = false
                    open()
                }
            },
            onSelect = {
                prefs.setBaseLayer(it)
                showLayers = false
            },
            onAddCustom = {
                prefs.addCustomSource(it)
                prefs.setBaseLayer(it.id)
                showLayers = false
            },
            onRemoveCustom = { prefs.removeCustomSource(it) },
            onDismiss = { showLayers = false },
        )
    }
}

/** Saved places as map markers. */
fun placeMapMarkers(places: List<app.maparound.places.Place>): List<PlaceMarker> =
    places.map { PlaceMarker(it.id, it.lat, it.lon, it.name, it.category.emoji, it.category.argb.toInt()) }

/** Party members, the meeting point and other radios as map markers (phone and car screens). */
fun partyMapMarkers(party: PartyState, now: Long): List<PartyMarker> = buildList {
    party.members.forEach { m ->
        m.position?.let { p ->
            add(PartyMarker(
                "m${m.id}", p.lat, p.lon, m.displayName, m.color,
                if (m.sos) PartyMarker.Kind.SOS else PartyMarker.Kind.MEMBER,
                stale = now - m.positionAt > 10 * 60_000,
                icon = m.icon,
            ))
        }
    }
    party.meeting?.let {
        add(PartyMarker("meet", it.lat, it.lon, it.label.ifBlank { "Meet" }, 0, PartyMarker.Kind.MEET, false))
    }
    party.meshNodes.forEach { n ->
        n.position?.let {
            add(PartyMarker(
                "r${n.num}", it.lat, it.lon, n.shortName.ifBlank { n.name }, 0, PartyMarker.Kind.RADIO,
                stale = now - n.heardAt > 30 * 60_000,
            ))
        }
    }
}

@Composable
private fun PartyMarkerCard(
    key: String,
    party: PartyState,
    myLocation: GeoFix?,
    onClose: () -> Unit,
    onShare: (Double, Double, String) -> Unit,
    onRoute: (GeoPoint) -> Unit,
) {
    val member = party.members.firstOrNull { "m${it.id}" == key }
    val radio = party.meshNodes.firstOrNull { "r${it.num}" == key }
    val meet = party.meeting?.takeIf { key == "meet" }
    val memberAt = member?.position
    val radioAt = radio?.position
    val (title, lat, lon, at) = when {
        member != null && memberAt != null -> Quad(member.displayName + if (member.sos) " · SOS" else "", memberAt.lat, memberAt.lon, member.positionAt)
        radio != null && radioAt != null -> Quad("Radio ${radio.name}", radioAt.lat, radioAt.lon, radio.heardAt)
        meet != null -> Quad("Meeting point", meet.lat, meet.lon, 0L)
        else -> return
    }
    Card(Modifier.widthIn(max = 360.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            val parts = mutableListOf<String>()
            myLocation?.let { me ->
                val from = GeoPoint(me.lat, me.lon)
                val to = GeoPoint(lat, lon)
                parts += "${Fmt.distance(Geo.distance(from, to).toFloat())} ${Fmt.cardinal(Geodesy.bearing(from, to).toFloat())}"
            }
            if (at > 0) parts += Fmt.relativeTime(at)
            member?.position?.speedKmh?.takeIf { it >= 3 }?.let { parts += "$it km/h" }
            member?.position?.batteryPct?.let { parts += "battery $it%" }
            member?.let { parts += "via " + it.heard.keys.joinToString(" + ") { v -> v.label } }
            if (member?.sos == true && member.sosText.isNotBlank()) parts.add(0, member.sosText)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { onRoute(GeoPoint(lat, lon)) }) {
                    Icon(Icons.Filled.Route, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Route here")
                }
                TextButton(onClick = { onShare(lat, lon, title) }) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

private data class Quad(val a: String, val b: Double, val c: Double, val d: Long)

@Composable
private fun PinCard(
    pin: GeoPoint,
    label: String?,
    myLocation: GeoFix?,
    onClose: () -> Unit,
    onRoute: (() -> Unit)?,
    onShare: () -> Unit,
    onMeetHere: (() -> Unit)? = null,
    onSavePlace: (() -> Unit)? = null,
    onTool: ((ToolsSession.Mode) -> Unit)? = null,
    /** What the point of interest under the pin is ("Peak · 2085 m"). */
    details: String? = null,
    onWhatsHere: (() -> Unit)? = null,
    /** Opens its Wikipedia article (it has one, from its own tags). */
    onWikipedia: (() -> Unit)? = null,
    /** Its picture (its article's), and showing it big. */
    picture: app.maparound.search.Commons.Photo? = null,
    onPicture: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val platform = LocalPlatform.current
    val format by LocalPrefs.current.coordFormat.collectAsState()
    val primary = app.maparound.tools.Coords.format(pin, format) ?: Fmt.latLon(pin.lat, pin.lon)
    val secondary = if (format == app.maparound.tools.CoordFormat.DD) Fmt.dms(pin.lat, pin.lon) else Fmt.latLon(pin.lat, pin.lon)
    Card(Modifier.widthIn(max = 360.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (picture != null) {
                    RemoteImage(
                        picture.thumb,
                        contentDescription = label,
                        modifier = Modifier.padding(end = 12.dp).size(76.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = onPicture != null) { onPicture?.invoke() },
                    )
                }
                // Tapping the coordinates opens the converter with this point.
                Column(Modifier.weight(1f).clickable(enabled = onTool != null) { onTool?.invoke(ToolsSession.Mode.COORDS) }) {
                    if (label != null) Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (details != null) Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        primary,
                        style = if (label == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    )
                    Text(secondary, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            if (myLocation != null) {
                val from = GeoPoint(myLocation.lat, myLocation.lon)
                val bearing = Geodesy.bearing(from, pin).toFloat()
                Text(
                    "${Fmt.distance(Geo.distance(from, pin).toFloat())} from you · ${bearing.roundToInt()}° ${Fmt.cardinal(bearing)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (onWhatsHere != null || onWikipedia != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onWhatsHere != null) {
                        TextButton(onClick = onWhatsHere) {
                            Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("What's here?")
                        }
                    }
                    if (onWikipedia != null) {
                        TextButton(onClick = onWikipedia) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Wikipedia")
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRoute != null) {
                    IconButton(onClick = onRoute) { Icon(Icons.Filled.Route, contentDescription = "Route here") }
                }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "Share location") }
                IconButton(onClick = { clipboard.setText(AnnotatedString(primary)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy coordinates")
                }
                if (onSavePlace != null) {
                    IconButton(onClick = onSavePlace) { Icon(Icons.Filled.BookmarkAdd, contentDescription = "Save as a place") }
                }
                if (onTool != null) {
                    IconButton(onClick = { onTool(ToolsSession.Mode.VIEW) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = "What can be seen from here")
                    }
                }
                if (onMeetHere != null) {
                    TextButton(onClick = onMeetHere) { Text("Meet here") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LayersSheet(
    selectedId: String,
    custom: List<OnlineSource>,
    offlineCount: Int,
    theme: String,
    contours: Boolean,
    hillshade: Boolean,
    terrainTiles: Int,
    trails: Boolean,
    localTrails: Boolean,
    places: Boolean,
    background: String,
    vectorOnBackground: Boolean,
    rasterMaps: List<app.maparound.raster.RasterMap>,
    onRasterError: (Throwable) -> Unit,
    onMessage: (String) -> Unit,
    onOpenOffline: (() -> Unit)?,
    onSelect: (String) -> Unit,
    onAddCustom: (OnlineSource) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs = LocalPrefs.current
    val rasters = LocalRasters.current
    val platform = LocalPlatform.current
    val themeLib = LocalMapThemes.current
    val themes by themeLib.themes.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    fun showStyle(id: String) {
        prefs.setMapTheme(id)
        prefs.setBaseLayer(TileSources.OFFLINE_ID)
    }
    val importTheme = rememberFilePicker(listOf("*/*")) { file ->
        if (file == null) return@rememberFilePicker
        scope.launch {
            withContext(ioDispatcher) { themeLib.import(file.name, file.bytes) }.fold(
                { added ->
                    showStyle(added.first().id)
                    onMessage(if (added.size == 1) "Added the style \"${added[0].name}\"" else "Added ${added.size} styles")
                },
                { onMessage(it.message ?: "Couldn't import that style") },
            )
        }
    }
    val importSources = rememberFilePicker(listOf("*/*")) { file ->
        if (file == null) return@rememberFilePicker
        SourceList.decode(Xml.decode(file.bytes)).fold(
            { found ->
                val added = prefs.addCustomSources(found, "custom_${nowMillis()}")
                onMessage(
                    when {
                        added.isEmpty() -> "Those tile sources are already in the list"
                        added.size == 1 -> "Added the tile source \"${added[0].name}\""
                        else -> "Added ${added.size} tile sources"
                    },
                )
            },
            { onMessage(it.message ?: "Couldn't read that list") },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Offline")
            LayerRow(
                selected = selectedId == TileSources.OFFLINE_ID,
                title = "Offline vector maps",
                subtitle = if (offlineCount == 0) "No maps downloaded yet" else "$offlineCount map file(s) on this device",
                icon = Icons.Filled.DownloadForOffline,
                onClick = { onSelect(TileSources.OFFLINE_ID) },
            )
            Column(Modifier.padding(start = 56.dp, end = 16.dp)) {
                Text("Style", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                themes.forEach { t ->
                    StyleRow(
                        theme = t,
                        selected = t.id == theme,
                        onClick = { showStyle(t.id) },
                        onRemove = if (!t.imported) null else {
                            {
                                themeLib.delete(t.id)
                                onMessage("Removed the style \"${t.name}\"")
                            }
                        },
                    )
                }
                if (themeLib.canImport) {
                    TextButton(onClick = importTheme) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Import a style (.xml or .zip)")
                    }
                }
            }
            Column(Modifier.padding(start = 72.dp, end = 16.dp)) {
                StyleOptions(theme, themeLib)
                Text("Terrain", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                SwitchRow(
                    "Contour lines",
                    if (!MapThemes.drawsTerrain(theme)) "Drawn by the Topo style and imported styles" else null,
                    checked = contours,
                    enabled = MapThemes.drawsTerrain(theme),
                ) { prefs.setContours(it) }
                SwitchRow("Hill shading", null, checked = hillshade) { prefs.setHillshade(it) }
                if (terrainTiles == 0 && onOpenOffline != null) {
                    TextButton(onClick = onOpenOffline, contentPadding = PaddingValues(0.dp)) {
                        Text("Terrain for contours & shading comes by itself as you look closer at your maps. All of a map's: Offline maps →")
                    }
                }
            }
            SheetHeader("Background of the offline map")
            val addRaster = rasters.rememberPicker { r -> r.fold({ showRasterOnMap(prefs, it); onDismiss() }, onRasterError) }
            fun pick(bg: String) {
                prefs.setBaseLayer(TileSources.OFFLINE_ID)
                if (bg == app.maparound.data.Prefs.BACKGROUND_SATELLITE && background != bg) prefs.setVectorOnBackground(true)
                if (bg.startsWith(app.maparound.data.Prefs.BACKGROUND_RASTER) && background != bg) prefs.setVectorOnBackground(false)
                prefs.setMapBackground(bg)
            }
            val offlineSelected = selectedId == TileSources.OFFLINE_ID
            LayerRow(
                selected = offlineSelected && background == app.maparound.data.Prefs.BACKGROUND_NONE,
                title = "Map colours",
                subtitle = "The vector map as it is",
                icon = Icons.Filled.Map,
                onClick = { pick(app.maparound.data.Prefs.BACKGROUND_NONE) },
            )
            LayerRow(
                selected = offlineSelected && background == app.maparound.data.Prefs.BACKGROUND_SATELLITE,
                title = "Satellite imagery",
                subtitle = "Esri World Imagery under roads, trails & labels (online, cached)",
                icon = Icons.Filled.SatelliteAlt,
                onClick = { pick(app.maparound.data.Prefs.BACKGROUND_SATELLITE) },
            )
            rasterMaps.forEach { m ->
                LayerRow(
                    selected = offlineSelected && background == app.maparound.data.Prefs.BACKGROUND_RASTER + m.id,
                    title = m.name,
                    subtitle = "Raster map · ${m.projection}",
                    icon = Icons.Filled.Image,
                    onClick = { pick(app.maparound.data.Prefs.BACKGROUND_RASTER + m.id) },
                    onRemove = { removeRaster(prefs, rasters, m) },
                )
            }
            TextButton(onClick = addRaster, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add a raster map (.rmap, .mbtiles, .sqlitedb)")
            }
            Column(Modifier.padding(start = 24.dp, end = 16.dp)) {
                SwitchRow(
                    "Roads, trails & labels on top",
                    "From the offline vector map, drawn over the background",
                    checked = vectorOnBackground,
                    enabled = background != app.maparound.data.Prefs.BACKGROUND_NONE,
                ) { prefs.setVectorOnBackground(it) }
            }
            SheetHeader("Overlays")
            Column(Modifier.padding(start = 24.dp, end = 16.dp)) {
                SwitchRow(
                    "Marked hiking trails",
                    if (localTrails) "Offline in the Topo style; online overlay on other maps" else "Online overlay (waymarkedtrails.org). Download them per map for offline use.",
                    checked = trails,
                ) { prefs.setTrails(it) }
                SwitchRow("Saved places", "Your places from Library → Places", checked = places) { prefs.setPlacesOnMap(it) }
            }
            SheetHeader("Online")
            TileSources.builtIn.forEach { src ->
                LayerRow(
                    selected = selectedId == src.id,
                    title = src.name,
                    subtitle = src.description,
                    icon = when (src.kind) {
                        OnlineSource.Kind.IMAGERY -> Icons.Filled.SatelliteAlt
                        OnlineSource.Kind.TOPO -> Icons.Filled.Terrain
                        OnlineSource.Kind.STREET -> Icons.Filled.Public
                    },
                    onClick = { onSelect(src.id) },
                )
            }
            custom.forEach { src ->
                LayerRow(
                    selected = selectedId == src.id,
                    title = src.name,
                    subtitle = src.urlTemplate,
                    icon = Icons.Filled.Public,
                    onClick = { onSelect(src.id) },
                    onRemove = { onRemoveCustom(src.id) },
                )
            }
            TextButton(onClick = { showAdd = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add custom tile source")
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = importSources) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Import list")
                }
                TextButton(
                    onClick = { platform.shareTextFile(SourceList.FILE_NAME, "application/json", SourceList.encode(custom), "MapAround tile sources") },
                    enabled = custom.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export list")
                }
            }
        }
    }
    if (showAdd) AddSourceDialog(onDismiss = { showAdd = false }, onAdd = { showAdd = false; onAddCustom(it) })
}

/** One style of the offline maps (radio choice), removable when the user imported it. */
@Composable
private fun StyleRow(theme: MapTheme, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.name, style = MaterialTheme.typography.bodyLarge)
            Text(theme.description, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${theme.name}") }
    }
}

/** The chosen style's options (its style menu): a variant if it has several, and what to show. */
@Composable
private fun StyleOptions(themeId: String, lib: MapThemeLibrary) {
    val prefs = LocalPrefs.current
    val menu = remember(themeId) { lib.menu(themeId) } ?: return
    val choices by prefs.styleChoices.collectAsState()
    val choice = choices[themeId] ?: StyleChoice()
    val style = menu.style(choice.style) ?: return
    if (menu.styles.size > 1) {
        Text("Variant", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        menu.styles.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { prefs.setStyleChoice(themeId, choice.copy(style = s.id)) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.id == style.id, onClick = null, modifier = Modifier.padding(end = 12.dp))
                Text(menu.name(s), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    val overlays = menu.overlays(style)
    if (overlays.isNotEmpty()) {
        Text("Show", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        overlays.forEach { o ->
            SwitchRow(menu.name(o), null, checked = menu.isOn(o, choice)) { on ->
                prefs.setStyleChoice(themeId, choice.copy(overlays = choice.overlays + (o.id to on)))
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun SheetHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun LayerRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRemove != null) {
                    IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove $title") }
                }
                RadioButton(selected = selected, onClick = null)
            }
        },
        colors = ListItemDefaults.colors(),
    )
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (OnlineSource) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var maxZoom by remember { mutableStateOf("19") }
    val urlError = TileSources.validateTemplate(url.trim())
    val zoom = maxZoom.toIntOrNull()
    val valid = name.isNotBlank() && urlError == null && zoom != null && zoom in 1..22
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom tile source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Any XYZ raster tile service, e.g. https://{s}.example.com/{z}/{x}/{y}.png. " +
                        "Check the provider's usage terms.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("URL template") },
                    isError = url.length > 8 && urlError != null,
                    supportingText = { if (url.length > 8 && urlError != null) Text(urlError) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    maxZoom, { maxZoom = it.filter(Char::isDigit).take(2) },
                    label = { Text("Max zoom") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onAdd(
                        OnlineSource(
                            id = "custom_${nowMillis()}",
                            name = name.trim(),
                            urlTemplate = url.trim(),
                            maxZoom = zoom ?: 19,
                            attribution = name.trim(),
                            custom = true,
                        ),
                    )
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerPanel(
    state: PlannerSession.State,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onNavigate: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    RouteProfile.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = state.profile == p,
                            onClick = { PlannerSession.setProfile(p) },
                            shape = SegmentedButtonDefaults.itemShape(i, RouteProfile.entries.size),
                            icon = {},
                        ) { Text(p.label, maxLines = 1) }
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close route planner") }
            }
            val route = state.route
            when {
                state.waypoints.isEmpty() -> Text(
                    "Tap the map to set the start. Tap a numbered point to remove it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.waypoints.size == 1 -> Text(
                    "Now tap the destination, then more stops. To change the route, hold a stop or the line and drag it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.error != null -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                route != null -> {
                    StatsLine(route.stats, route.durationS)
                    Text(
                        when (route.source) {
                            RouteResult.Source.OFFLINE -> "Routed on this phone (BRouter)"
                            RouteResult.Source.ONLINE -> "Routed online by brouter.de"
                            RouteResult.Source.STRAIGHT ->
                                if (route.points.any { it.ele != null }) "Straight lines · heights from terrain data"
                                else "Straight lines · add terrain data in Offline maps for heights"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ElevationProfile(route.points, Modifier.fillMaxWidth().height(72.dp))
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { PlannerSession.undo() }, enabled = PlannerSession.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                }
                IconButton(onClick = { PlannerSession.reverse() }, enabled = state.waypoints.size >= 2) {
                    Icon(Icons.Filled.SwapVert, contentDescription = "Reverse direction")
                }
                IconButton(onClick = { PlannerSession.clear() }, enabled = state.waypoints.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear all points")
                }
                IconButton(onClick = onShare, enabled = route != null && !state.busy) {
                    Icon(Icons.Filled.Share, contentDescription = "Share route")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSave, enabled = route != null && !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Save")
                }
                Button(onClick = onNavigate, enabled = route != null && !state.busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start")
                }
            }
        }
    }
}

@Composable
private fun SaveRouteDialog(suggested: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save route") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifEmpty { suggested }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How far around a point "What's here?" lists the map's places, on screen. */
private const val WHATS_HERE_RADIUS_DP = 140.0

/** The terrain download the map starts by itself (its label). */
private const val AUTO_TERRAIN_LABEL = "contours here"

/** How often the map looks for terrain it lacks, and from which zoom (the contours'). */
private const val AUTO_TERRAIN_CHECK_MS = 2_500L
private const val AUTO_TERRAIN_ZOOM = 10

/** How far from the centre the map's terrain is fetched (about the screen), in dp. */
private const val AUTO_TERRAIN_HALF_DP = 300.0

/** The area [halfM] metres around [pos] each way. */
private fun around(pos: MapPos, halfM: Double): GeoBounds {
    val dLat = halfM / 111_320.0
    val dLon = halfM / (111_320.0 * kotlin.math.cos(pos.lat * kotlin.math.PI / 180).coerceAtLeast(0.05))
    return GeoBounds(pos.lat - dLat, pos.lon - dLon, pos.lat + dLat, pos.lon + dLon)
}

/** Where two areas overlap, or null. */
private fun overlap(a: GeoBounds, b: GeoBounds): GeoBounds? {
    val s = maxOf(a.minLat, b.minLat)
    val n = minOf(a.maxLat, b.maxLat)
    val w = maxOf(a.minLon, b.minLon)
    val e = minOf(a.maxLon, b.maxLon)
    return if (s < n && w < e) GeoBounds(s, w, n, e) else null
}
