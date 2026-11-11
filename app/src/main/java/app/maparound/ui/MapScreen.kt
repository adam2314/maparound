package app.maparound.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import app.maparound.routing.GeoPoint
import app.maparound.routing.MapFocus
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.routing.RouteResult
import app.maparound.tracks.Recorder
import app.maparound.tracks.TrackRecorder
import app.maparound.share.IncomingShare
import app.maparound.share.Sharing
import androidx.compose.material.icons.filled.Share
import app.maparound.party.PartySession
import app.maparound.tools.Geodesy
import app.maparound.tools.ToolsSession
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import android.text.format.DateUtils
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.AppGraph
import app.maparound.data.MapPos
import app.maparound.map.MapController
import app.maparound.map.OnlineSource
import app.maparound.map.TileSources
import app.maparound.sensors.LocationSource
import app.maparound.sensors.toGeoFix
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import kotlin.math.roundToInt

@Composable
fun MapScreen(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    onOpenOffline: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val prefs = AppGraph.prefs
    val scope = rememberCoroutineScope()

    val controller = remember {
        MapController(context).apply { moveTo(prefs.lastPosition() ?: MapPos(30.0, 10.0, 3)) }
    }
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
    val localMaps by AppGraph.mapFiles.files.collectAsState()
    val onlineSource = TileSources.find(baseLayerId, customSources)
    // "offline" (or an id that no longer exists) resolves to no online source -> render offline maps.
    val showingOffline = onlineSource == null
    val topo by prefs.topoStyle.collectAsState()
    val contours by prefs.contours.collectAsState()
    val hillshade by prefs.hillshade.collectAsState()
    val terrain by AppGraph.terrain.state.collectAsState()
    val trailsOn by prefs.trails.collectAsState()
    val trailData by AppGraph.trails.state.collectAsState()
    val offlineStyle = MapController.OfflineStyle(
        topo = topo,
        contours = contours,
        hillshade = hillshade,
        demDir = AppGraph.terrain.demDir,
        terrainVersion = terrain.version,
        trails = trailsOn,
        trailsDir = AppGraph.trails.dir,
        trailsVersion = trailData.version,
    )
    val background by prefs.mapBackground.collectAsState()
    val vectorOnBackground by prefs.vectorOnBackground.collectAsState()
    val rasterMaps by AppGraph.rasters.maps.collectAsState()
    LaunchedEffect(onlineSource, localMaps, offlineStyle, background, vectorOnBackground, rasterMaps) {
        val src = onlineSource
        if (src != null) {
            controller.showOnline(src)
        } else {
            // Opening a raster map reads its tables: off the main thread.
            val bg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.maparound.map.resolveBackground(background) }
            controller.showOffline(localMaps.map { it.file }, offlineStyle, bg, vectorOnBackground)
        }
    }
    val terrainShown = showingOffline && terrain.tiles > 0 && (contours || hillshade)
    // Marked trails: drawn from downloaded data in the Topo style, otherwise the online overlay.
    val localTrails = trailsOn && showingOffline && topo && trailData.cells > 0
    val trailOverlay = trailsOn && !localTrails
    LaunchedEffect(trailOverlay) { controller.setTrailOverlay(trailOverlay) }

    // ---- routes & tracks ------------------------------------------------------------------------
    val planner by PlannerSession.state.collectAsState()
    val recorder by TrackRecorder.state.collectAsState()
    val savedTracks by AppGraph.tracks.tracks.collectAsState()
    val visibleTracks by AppGraph.tracks.visible.collectAsState()
    LaunchedEffect(planner.active, planner.route, planner.waypoints) {
        val line = when {
            !planner.active -> emptyList()
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
    val startRecording = LocalRecordingControl.current.rememberStarter(hasLocation, requestLocation)
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
                val out = ArrayList<MapController.ToolLine>()
                var run = ArrayList<GeoPoint>()
                var vis: Boolean? = null
                for (s in tools.los!!.samples) {
                    val p = GeoPoint(s.lat, s.lon)
                    if (vis != null && s.visible != vis) {
                        run += p
                        out += MapController.ToolLine(run, vis)
                        run = arrayListOf(p)
                    } else {
                        run += p
                    }
                    vis = s.visible
                }
                if (run.size >= 2) out += MapController.ToolLine(run, vis)
                out
            }
            else -> listOf(MapController.ToolLine(Geodesy.greatCircle(a, b), null))
        }
        val ring = if (tools.mode == ToolsSession.Mode.VIEW && a != null) a to (tools.viewshed?.radiusM ?: tools.radiusM) else null
        controller.setTools(a, b, lines, ring, tools.viewshed?.takeIf { tools.mode == ToolsSession.Mode.VIEW })
    }

    // ---- party ------------------------------------------------------------------------------------
    val party by PartySession.state.collectAsState()
    val partyClock by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    var selectedParty by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(party, partyClock) { controller.setPartyMarkers(partyMapMarkers(party, partyClock)) }

    // ---- saved places --------------------------------------------------------------------------------
    val places by AppGraph.places.places.collectAsState()
    val placesOn by prefs.placesOnMap.collectAsState()
    var selectedPlace by remember { mutableStateOf<String?>(null) }
    var editPlace by remember { mutableStateOf<Pair<app.maparound.places.Place, Boolean>?>(null) } // place, isNew
    LaunchedEffect(places, placesOn) { controller.setPlaces(if (placesOn) placeMapMarkers(places) else emptyList()) }
    val placeFocus by AppGraph.places.focus.collectAsState()
    LaunchedEffect(placeFocus) {
        placeFocus?.let {
            selectedPlace = it
            AppGraph.places.focus.value = null
        }
    }
    fun newPlace(lat: Double, lon: Double, name: String?) {
        editPlace = app.maparound.places.Place(
            id = app.maparound.places.Place.newId(), name = name.orEmpty(), lat = lat, lon = lon,
            category = app.maparound.places.PlaceCategory.FAVORITE,
        ) to true
    }

    // ---- my location ---------------------------------------------------------------------------
    val locationFlow: Flow<Location?> = remember(hasLocation) {
        if (hasLocation) LocationSource.updates(context) else flowOf(null)
    }
    val location by locationFlow.collectAsStateWithLifecycle(initialValue = null)
    var follow by rememberSaveable { mutableStateOf(false) }
    var pin by remember { mutableStateOf<LatLong?>(null) }
    var pinLabel by remember { mutableStateOf<String?>(null) }
    var showMe by remember { mutableStateOf(false) }
    // (lat, lon, label) for the share sheet
    var shareTarget by remember { mutableStateOf<Triple<Double, Double, String?>?>(null) }
    SideEffect {
        controller.onUserGesture = { follow = false }
        controller.onLongPress = { pin = it; pinLabel = null }
        controller.onMyLocationTap = { showMe = true }
        controller.onTap = { ll ->
            when {
                ToolsSession.state.value.active -> {
                    ToolsSession.tap(GeoPoint(ll.latitude, ll.longitude))
                    true
                }
                PlannerSession.state.value.active -> {
                    PlannerSession.add(GeoPoint(ll.latitude, ll.longitude))
                    true
                }
                else -> false
            }
        }
        controller.onWaypointTap = { PlannerSession.removeAt(it) }
        controller.onPartyMarkerTap = { selectedParty = it }
        controller.onPlaceTap = { selectedPlace = it }
    }
    LaunchedEffect(location) {
        val loc = location
        controller.updateLocation(loc)
        if (follow && loc != null) controller.animateTo(loc.latitude, loc.longitude)
    }
    LaunchedEffect(pin) { controller.setPin(pin) }

    // ---- things other apps shared with us ---------------------------------------------------------
    val incoming by IncomingShare.pending.collectAsState()
    LaunchedEffect(incoming) {
        when (val i = incoming) {
            is IncomingShare.Item.Point -> {
                IncomingShare.consume()
                val p = i.point
                follow = false
                pin = LatLong(p.lat, p.lon)
                pinLabel = p.label
                controller.moveTo(MapPos(p.lat, p.lon, p.zoom ?: maxOf(controller.position().zoom, 15)))
            }
            is IncomingShare.Item.Route -> {
                IncomingShare.consume()
                follow = false
                PlannerSession.load(i.route.points, i.route.profile)
                controller.showPoints(i.route.points)
            }
            is IncomingShare.Item.Track -> {
                IncomingShare.consume()
                follow = false
                controller.showPoints(i.saved.track.points)
                snackbar.showSnackbar(
                    "Imported “${i.saved.track.name}” into Library" +
                        if (i.placesAdded > 0) " and ${i.placesAdded} waypoint${if (i.placesAdded == 1) "" else "s"} into Places" else "",
                )
            }
            is IncomingShare.Item.Places -> {
                IncomingShare.consume()
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
        AndroidView(factory = { controller.mapView }, modifier = Modifier.fillMaxSize())

        if (showingOffline && localMaps.isEmpty() && !planner.active && !tools.active) {
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
            SmallFloatingActionButton(
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
            SmallFloatingActionButton(
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

        if (recorder.recording) {
            RecordingBar(
                recorder,
                onStop = { Recorder.stop(context) },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            )
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp)) {
            if (showMe) {
                location?.let { me ->
                    PinCard(
                        LatLong(me.latitude, me.longitude), "My location" + if (me.hasAccuracy()) " (±${me.accuracy.roundToInt()} m)" else "",
                        null, onClose = { showMe = false },
                        onRoute = null,
                        onShare = { shareTarget = Triple(me.latitude, me.longitude, "My location") },
                        onSavePlace = { newPlace(me.latitude, me.longitude, null) },
                        onTool = { mode ->
                            showMe = false
                            PlannerSession.close()
                            ToolsSession.open(mode, GeoPoint(me.latitude, me.longitude))
                        },
                    )
                } ?: run { showMe = false }
            }
            selectedPlace?.let { id -> AppGraph.places.get(id) }?.let { pl ->
                PlaceCard(
                    pl, location?.toGeoFix(),
                    onClose = { selectedPlace = null },
                    onRoute = {
                        selectedPlace = null
                        ToolsSession.close()
                        PlannerSession.close()
                        PlannerSession.open()
                        location?.let { PlannerSession.add(GeoPoint(it.latitude, it.longitude)) }
                        PlannerSession.add(pl.point)
                    },
                    onShare = { app.maparound.share.Sharing.sharePlaces(context, listOf(pl), pl.name) },
                    onEdit = { editPlace = pl to false },
                    onDelete = { AppGraph.places.delete(pl.id); selectedPlace = null },
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
                    location?.let { PlannerSession.add(GeoPoint(it.latitude, it.longitude)) }
                    PlannerSession.add(target)
                })
            }
            pin?.let { p ->
                PinCard(
                    p, pinLabel, location, onClose = { pin = null },
                    onRoute = {
                        pin = null
                        ToolsSession.close()
                        PlannerSession.close()
                        PlannerSession.open()
                        location?.let { PlannerSession.add(GeoPoint(it.latitude, it.longitude)) }
                        PlannerSession.add(GeoPoint(p.latitude, p.longitude))
                    },
                    onShare = { shareTarget = Triple(p.latitude, p.longitude, pinLabel) },
                    onSavePlace = { newPlace(p.latitude, p.longitude, pinLabel) },
                    onTool = { mode ->
                        pin = null
                        PlannerSession.close()
                        ToolsSession.open(mode, GeoPoint(p.latitude, p.longitude))
                    },
                    onMeetHere = if (party.active) ({
                        PartySession.setMeeting(p.latitude, p.longitude, pinLabel ?: "Meeting point")
                        pin = null
                    }) else null,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = listOfNotNull(
                    if (!showingOffline) onlineSource?.attribution else "© OpenStreetMap contributors · mapsforge",
                    if (showingOffline) app.maparound.map.backgroundAttribution(background) else null,
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
                            controller.animateTo(loc.latitude, loc.longitude, minZoom = 15)
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
        if (planner.active) {
            PlannerPanel(
                planner,
                onSave = { saveDialog = true },
                onShare = {
                    PlannerSession.currentTrack(
                        "Route " + java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date()),
                    )?.let { Sharing.shareRoute(context, it) }
                },
                onClose = { PlannerSession.close() },
            )
        }
        if (tools.active) {
            ToolsPanel(
                tools,
                myLocation = location?.toGeoFix(),
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

    scene3d?.let { req -> Scene3DScreen(req, onClose = { scene3d = null }) { Scene3DAndroidView(it) } }

    if (searching) {
        SearchOverlay(
            mapCenter = controller.position().let { GeoPoint(it.lat, it.lon) },
            myLocation = location?.toGeoFix(),
            onPick = { r ->
                searching = false
                follow = false
                if (r.placeId != null) {
                    prefs.setPlacesOnMap(true)
                    selectedPlace = r.placeId
                    pin = null
                } else {
                    pin = LatLong(r.lat, r.lon)
                    pinLabel = r.name
                }
                controller.moveTo(MapPos(r.lat, r.lon, maxOf(controller.position().zoom, 14)))
            },
            onClose = { searching = false },
        )
    }

    editPlace?.let { (pl, isNew) ->
        PlaceEditDialog(pl, isNew, onDismiss = { editPlace = null }, onSave = {
            AppGraph.places.save(it)
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
                java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()),
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
            topo = topo,
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
            onOpenOffline = {
                showLayers = false
                onOpenOffline()
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

/** Party members, the meeting point and other radios as map markers (phone and car screens). */
fun partyMapMarkers(party: PartySession.State, now: Long): List<MapController.PartyMarker> = buildList {
    party.members.forEach { m ->
        m.position?.let { p ->
            add(MapController.PartyMarker(
                "m${m.id}", p.lat, p.lon, m.displayName, PartyColors.of(m.color).toArgb(),
                if (m.sos) MapController.PartyMarker.Kind.SOS else MapController.PartyMarker.Kind.MEMBER,
                stale = now - m.positionAt > 10 * 60_000,
            ))
        }
    }
    party.meeting?.let {
        add(MapController.PartyMarker("meet", it.lat, it.lon, it.label.ifBlank { "Meet" }, 0, MapController.PartyMarker.Kind.MEET, false))
    }
    party.meshNodes.forEach { n ->
        n.position?.let {
            add(MapController.PartyMarker(
                "r${n.num}", it.lat, it.lon, n.shortName.ifBlank { n.name }, 0, MapController.PartyMarker.Kind.RADIO,
                stale = now - n.heardAt > 30 * 60_000,
            ))
        }
    }
}

@Composable
private fun PartyMarkerCard(
    key: String,
    party: PartySession.State,
    myLocation: Location?,
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
                val r = FloatArray(2)
                Location.distanceBetween(me.latitude, me.longitude, lat, lon, r)
                parts += "${Fmt.distance(r[0])} ${Fmt.cardinal((r[1] + 360f) % 360f)}"
            }
            if (at > 0) parts += DateUtils.getRelativeTimeSpanString(at).toString()
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
    pin: LatLong,
    label: String?,
    myLocation: Location?,
    onClose: () -> Unit,
    onRoute: (() -> Unit)?,
    onShare: () -> Unit,
    onMeetHere: (() -> Unit)? = null,
    onSavePlace: (() -> Unit)? = null,
    onTool: ((ToolsSession.Mode) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val format by AppGraph.prefs.coordFormat.collectAsState()
    val point = GeoPoint(pin.latitude, pin.longitude)
    val primary = app.maparound.tools.Coords.format(point, format) ?: Fmt.latLon(pin.latitude, pin.longitude)
    val secondary = if (format == app.maparound.tools.CoordFormat.DD) Fmt.dms(pin.latitude, pin.longitude) else Fmt.latLon(pin.latitude, pin.longitude)
    Card(Modifier.widthIn(max = 360.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Tapping the coordinates opens the converter with this point.
                Column(Modifier.weight(1f).clickable(enabled = onTool != null) { onTool?.invoke(ToolsSession.Mode.COORDS) }) {
                    if (label != null) Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        primary,
                        style = if (label == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    )
                    Text(secondary, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            if (myLocation != null) {
                val result = FloatArray(2)
                Location.distanceBetween(myLocation.latitude, myLocation.longitude, pin.latitude, pin.longitude, result)
                val bearing = (result[1] + 360f) % 360f
                Text(
                    "${Fmt.distance(result[0])} from you · ${bearing.roundToInt()}° ${Fmt.cardinal(bearing)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
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
    topo: Boolean,
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
    onOpenOffline: () -> Unit,
    onSelect: (String) -> Unit,
    onAddCustom: (OnlineSource) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val prefs = AppGraph.prefs
    var showAdd by remember { mutableStateOf(false) }
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
            Column(Modifier.padding(start = 72.dp, end = 16.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SegmentedButton(
                        selected = topo,
                        onClick = { prefs.setTopoStyle(true) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Topo") }
                    SegmentedButton(
                        selected = !topo,
                        onClick = { prefs.setTopoStyle(false) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Classic") }
                }
                SwitchRow(
                    "Contour lines",
                    if (!topo) "Shown in the Topo style" else null,
                    checked = contours,
                    enabled = topo,
                ) { prefs.setContours(it) }
                SwitchRow("Hill shading", null, checked = hillshade) { prefs.setHillshade(it) }
                if (terrainTiles == 0) {
                    TextButton(onClick = onOpenOffline, contentPadding = PaddingValues(0.dp)) {
                        Text("Contours & shading need terrain data. Get it in Offline maps →")
                    }
                }
            }
            SheetHeader("Background of the offline map")
            val addRaster = AppGraph.rasters.rememberPicker { r -> r.fold({ showRasterOnMap(AppGraph.prefs, it); onDismiss() }, onRasterError) }
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
                    onRemove = { removeRaster(AppGraph.prefs, AppGraph.rasters, m) },
                )
            }
            TextButton(onClick = addRaster, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add a raster map (.rmap)")
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
                    icon = when (src.id) {
                        "esri_imagery" -> Icons.Filled.SatelliteAlt
                        "opentopomap" -> Icons.Filled.Terrain
                        else -> Icons.Filled.Public
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
        }
    }
    if (showAdd) AddSourceDialog(onDismiss = { showAdd = false }, onAdd = { showAdd = false; onAddCustom(it) })
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
                            id = "custom_${System.currentTimeMillis()}",
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
private fun PlannerPanel(state: PlannerSession.State, onSave: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
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
                    "Now tap the destination. Keep tapping to add stops along the way.",
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
                Button(onClick = onSave, enabled = route != null && !state.busy) { Text("Save") }
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
