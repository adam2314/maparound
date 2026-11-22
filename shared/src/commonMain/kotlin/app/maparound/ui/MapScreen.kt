package app.maparound.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material3.Badge
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import app.maparound.map.satelliteSource
import app.maparound.sources.Sources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.mutableStateListOf
import app.maparound.map.MapRegions
import app.maparound.stats.CountryIndicator
import app.maparound.stats.LocalStats
import app.maparound.stats.LocalityIndicator
import app.maparound.stats.StatsLayers
import app.maparound.stats.TimeZones
import app.maparound.shared.util.currentYear
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.data.MapAroundMaps
import app.maparound.data.MapPos
import app.maparound.map.BaseMap
import app.maparound.map.LocalMapThemes
import app.maparound.map.MapTheme
import app.maparound.map.MapThemeLibrary
import app.maparound.map.MapThemes
import app.maparound.map.SourceList
import app.maparound.map.StyleChoice
import app.maparound.map.StyleMenu
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
import app.maparound.routing.NavView
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.routing.RouteResult
import app.maparound.sensors.GeoFix
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.share.Incoming
import app.maparound.share.RouteText
import app.maparound.shared.ioDispatcher
import app.maparound.shared.util.Xml
import app.maparound.shared.util.nowMillis
import app.maparound.tools.Geodesy
import app.maparound.tools.ToolsSession
import app.maparound.tracks.LocalTracks
import app.maparound.tracks.TrackRecorder
import kotlin.math.abs
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
    /**
     * The map embedded in another website (the web app's `?embed`): the map with its layers and 3D,
     * what it was given (a pin, a track) and a button that opens it in MapAround ([openFull]); no
     * search, planning, tools, recording or position. Null in the app.
     */
    openFull: (() -> Unit)? = null,
) {
    val embedded = openFull != null
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

    val controller = remember { createMap().apply { moveTo(prefs.lastPosition() ?: WORLD_START) } }
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
    val theme = chosenTheme.takeIf { id -> themes.any { it.id == id } } ?: themes.firstOrNull()?.id ?: MapThemes.DEFAULT
    val categories = remember(theme, styleChoices, themes) { themeLib.categories(theme, styleChoices) }
    val contours by prefs.contours.collectAsState()
    val hillshade by prefs.hillshade.collectAsState()
    val terrain by offline.terrain.state.collectAsState()
    val trailsOn by prefs.trails.collectAsState()
    val background by prefs.mapBackground.collectAsState()
    val vectorOnBackground by prefs.vectorOnBackground.collectAsState()
    val rasterMaps by rastersLib.maps.collectAsState()
    // Where no installed map reaches, maps read over the internet (Android).
    val noStreaming = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<app.maparound.map.RemoteMap>()) }
    val streamed by (offline.streamed ?: noStreaming).collectAsState()
    // An online vector map's style, read when it's picked (null while it's read); one that can't
    // be read gives way to the default online map.
    val vectorStyleUrl = onlineSource?.style
    val vectorStyle by produceState<Pair<String, String?>?>(null, vectorStyleUrl) {
        value = vectorStyleUrl?.let { it to app.maparound.map.maplibre.OnlineVectorStyle.load(it) }
    }
    val baseMap: BaseMap = onlineSource?.let { src ->
        val read = vectorStyle?.takeIf { it.first == src.style }
        when {
            src.style == null -> BaseMap.Online(src)
            read == null -> BaseMap.Online(src)
            read.second == null -> BaseMap.Online(TileSources.builtIn.first { it.id == TileSources.DEFAULT_ID })
            else -> BaseMap.Online(src, read.second, app.maparound.i18n.I18n.contentLanguage)
        }
    } ?: BaseMap.Offline(
        maps = localMaps,
        theme = theme,
        categories = categories,
        contours = contours,
        hillshade = hillshade,
        terrainVersion = terrain.version,
        trails = trailsOn,
        background = background,
        vectorOnTop = vectorOnBackground,
        rasterMaps = rasterMaps,
        streamed = streamed,
        satellite = if (background == app.maparound.data.Prefs.BACKGROUND_SATELLITE) satelliteSource.urlTemplate else "",
    )
    // Streamed maps readable again after tiles were made without them: the map asks for those again.
    val streamGeneration by app.maparound.shared.stream.MapStream.generation.collectAsState()
    // The coordinate grid (Layers, or the converter): drawn again when the view or the format changes.
    val showGrid by prefs.showGrid.collectAsState()
    val gridFormat by prefs.coordFormat.collectAsState()
    LaunchedEffect(showGrid, gridFormat) {
        if (!showGrid) {
            controller.setGrid(null)
            return@LaunchedEffect
        }
        var grid: app.maparound.tools.MapGrid.Grid? = null
        while (true) {
            val view = controller.visibleBounds()
            if (view != null) {
                val mpd = controller.metresPerDp()
                val at = controller.position().let { GeoPoint(it.lat, it.lon) }
                val shown = grid
                if (shown == null || !app.maparound.tools.MapGrid.fits(shown, gridFormat, view, mpd, at)) {
                    val made = withContext(Dispatchers.Default) { app.maparound.tools.MapGrid.make(gridFormat, view, mpd, at) }
                    grid = made
                    controller.setGrid(made)
                }
            }
            delay(GRID_CHECK_MS)
        }
    }

    // The online services' addresses (Settings → Sources) are in the map's style too.
    val sourceChoices = Sources.settings
    LaunchedEffect(baseMap, streamGeneration, sourceChoices) { controller.setBaseMap(baseMap) }

    // Where the map is, closely enough for a region's own sources (the automatic map layer, the
    // satellite): Settings → Sources' regional defaults.
    LaunchedEffect(Unit) {
        val regions = regionsWhenRead() ?: return@LaunchedEffect
        while (true) {
            val pos = controller.position()
            val here = if (pos.zoom < Sources.REGIONAL_ZOOM) emptyList() else withContext(Dispatchers.Default) { regions.at(pos.lat, pos.lon).map { it.path } }
            if (here != Sources.regionPaths) Sources.regionPaths = here
            delay(OFFER_CHECK_MS)
        }
    }
    val terrainShown = showingOffline && terrain.tiles > 0 && (contours || hillshade)

    // Terrain for the contour lines and hill shading: fetched by itself where the offline maps
    // are, a few tiles at most, once the map shows an area closely enough (as the 3D view does).
    val mapAreas by produceState(emptyList<GeoBounds>(), localMaps) {
        value = withContext(Dispatchers.Default) { localMaps.filterNot { it.isWorld }.mapNotNull { mapBounds(it.path) } }
    }
    // Where no installed map reaches: the map made for what the map shows (a state of a big country,
    // else the country), offered; "not now" for the rest of the visit.
    var offerRegion by remember { mutableStateOf<MapRegions.Region?>(null) }
    val declinedRegions = remember { mutableStateListOf<String>() }
    LaunchedEffect(showingOffline, localMaps, onOpenOffline != null) {
        offerRegion = null
        if (!showingOffline || onOpenOffline == null) return@LaunchedEffect
        val regions = regionsWhenRead() ?: return@LaunchedEffect
        val installed = localMaps.map { it.fileName }.toSet()
        // Maps from elsewhere (not one of the regions): their areas.
        val others = withContext(Dispatchers.Default) {
            localMaps.filterNot { it.isWorld || regions.byFileName(it.fileName) != null }.mapNotNull { mapBounds(it.path) }
        }
        while (true) {
            val pos = controller.position()
            offerRegion = if (pos.zoom < OFFER_ZOOM || others.any { overlap(around(pos, 1.0), it) != null }) {
                null
            } else {
                val here = regions.at(pos.lat, pos.lon)
                if (here.any { it.fileName in installed }) null else here.firstOrNull()
            }
            delay(OFFER_CHECK_MS)
        }
    }
    val offer = offerRegion?.takeIf { it.path !in declinedRegions }

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
    // Marked trails: the ones built into MapAround's maps, in the Topo styles; otherwise the online
    // overlay.
    val mapsWithTrails by produceState(false, localMaps) {
        value = withContext(Dispatchers.Default) { localMaps.any { MapAroundMaps.hasTrails(it.path) } }
    }
    // Looking at a MapAround map streamed: its trails are in it too.
    var streamedTrailsHere by remember { mutableStateOf(false) }
    LaunchedEffect(showingOffline, streamed) {
        streamedTrailsHere = false
        val withTrails = streamed.filter { it.trails }
        if (!showingOffline || withTrails.isEmpty()) return@LaunchedEffect
        val regions = withContext(Dispatchers.Default) { MapRegions.bundled } ?: return@LaunchedEffect
        while (true) {
            val pos = controller.position()
            val here = regions.at(pos.lat, pos.lon).map { it.path }
            streamedTrailsHere = pos.zoom >= OFFER_ZOOM && withTrails.any { it.path in here }
            delay(OFFER_CHECK_MS)
        }
    }
    val localTrails = trailsOn && showingOffline && MapThemes.drawsTerrain(theme) && (mapsWithTrails || streamedTrailsHere)
    val trailOverlay = trailsOn && !localTrails
    LaunchedEffect(trailOverlay) { controller.setTrailOverlay(trailOverlay) }

    // ---- routes & tracks ------------------------------------------------------------------------
    val planner by PlannerSession.state.collectAsState()
    val nav by NavigationSession.state.collectAsState()
    val speedUnit by prefs.speedUnit.collectAsState()
    val elevationSourceNow by prefs.elevationSource.collectAsState()
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
    // The saved route being edited isn't drawn as well: the planner draws it.
    val editingId = planner.editing?.id
    LaunchedEffect(savedTracks, visibleTracks, editingId) {
        controller.setShownTracks(savedTracks.filter { it.id in visibleTracks && it.id != editingId }.associate { it.id to it.track.points })
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
    LaunchedEffect(tools.active, tools.mode, tools.a, tools.b, tools.los, tools.viewshed, tools.next) {
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
        // The point the next tap moves, ringed (where there are two to choose from).
        val selected = if (!tools.usesB) null else if (tools.next == ToolsSession.Slot.A) a else tools.b
        controller.setTools(a, b, lines, ring, tools.viewshed?.takeIf { tools.mode == ToolsSession.Mode.VIEW }, selected)
    }

    // ---- party ------------------------------------------------------------------------------------
    val party by partyControl.state.collectAsState()
    // Every party I'm in, on the map (a controller with one: that one).
    val parties by partyControl.parties.collectAsState()
    val partyClock by produceState(nowMillis()) {
        while (true) {
            delay(30_000)
            value = nowMillis()
        }
    }
    var selectedParty by remember { mutableStateOf<String?>(null) }
    // A member's history replayed: their marker goes along their way, drawn in their colour.
    val replay by PartyReplay.state.collectAsState()
    val partyOn by prefs.partyOnMap.collectAsState()
    LaunchedEffect(parties, party, partyClock, replay, partyOn) {
        val all = parties.ifEmpty { listOf(party) }
        controller.setPartyMarkers(partyMapMarkers(all, partyClock, replay, shown = partyOn))
        controller.setPartyTrails(partyTrails(all, partyClock, replay, shown = partyOn))
        controller.setPartyMarks(partyMarks(all, shown = partyOn))
    }
    LaunchedEffect(replay?.playing) {
        var last = nowMillis()
        while (PartyReplay.state.value?.playing == true) {
            delay(REPLAY_FRAME_MS)
            val now = nowMillis()
            PartyReplay.advance(now - last)
            last = now
        }
    }
    val replayPoints = replay?.points
    LaunchedEffect(replay?.memberId, replayPoints) {
        if (replayPoints == null) {
            controller.setPartyTrail(emptyList(), emptyList(), 0)
            return@LaunchedEffect
        }
        // The whole way in view, the planner and tools out of the way.
        PlannerSession.close()
        ToolsSession.close()
        selectedParty = null
        // Above the replay's panel (about the bottom third): the area fitted reaches further south.
        val way = replayPoints.map { GeoPoint(it.position.lat, it.position.lon) }
        val south = way.minOf { it.lat }
        val below = GeoPoint(south - (way.maxOf { it.lat } - south) * 0.6, way.first().lon)
        controller.showPoints(if (way.size > 1) way + below else way)
    }
    // What's been gone: redrawn when the moment passes a position, in steps on long histories
    // (each time the whole line goes to the map).
    val replayStep = replay?.let { r -> maxOf(1, r.points.size / REPLAY_TRAIL_UPDATES) } ?: 1
    val replayDone = replay?.let { r -> if (r.t >= r.end) r.points.size else r.index / replayStep * replayStep + 1 }
    LaunchedEffect(replayPoints, replayDone) {
        val r = PartyReplay.state.value ?: return@LaunchedEffect
        val full = r.points.map { GeoPoint(it.position.lat, it.position.lon) }
        controller.setPartyTrail(full, full.take(replayDone ?: 0), r.color)
    }
    DisposableEffect(Unit) { onDispose { PartyReplay.close() } }

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

    // First open (no place kept yet): the map goes to where the user is, once, when a location
    // better than ±1 km comes within 20 s, while it still shows the whole world (not moved by
    // the user, nor by a link, a pin or an invite opened); there the region's map is offered and
    // its own sources apply. (Android asks for the location on first open; the web app only has
    // one when it was allowed before.)
    val openedAt = remember { nowMillis() }
    var firstPlace by rememberSaveable { mutableStateOf(prefs.lastPosition() == null) }
    LaunchedEffect(location, firstPlace) {
        if (!firstPlace) return@LaunchedEffect
        val fix = location ?: return@LaunchedEffect
        val now = controller.position()
        val stillTheWorld = now.zoom <= WORLD_START.zoom && abs(now.lat - WORLD_START.lat) < 1 && abs(now.lon - WORLD_START.lon) < 1
        if (!stillTheWorld || nowMillis() - openedAt > FIRST_FIX_WAIT_MS) {
            firstPlace = false
        } else if ((fix.accuracyM ?: 0f) <= 1000f) {
            firstPlace = false
            controller.moveTo(MapPos(fix.lat, fix.lon, FIRST_FIX_ZOOM))
        }
    }
    var pin by remember { mutableStateOf<GeoPoint?>(null) }
    var pinLabel by remember { mutableStateOf<String?>(null) }
    // Query mode: every tap says what's there (and the ground and sky); what the last one found.
    var queryMode by rememberSaveable { mutableStateOf(false) }
    var queried by remember { mutableStateOf<Pair<GeoPoint, List<app.maparound.map.MapFeature>>?>(null) }
    // Where the buttons at the bottom right start: the zoom slider above them fits in what's left.
    var bottomButtonsTop by remember { mutableStateOf<Float?>(null) }

    // The data view (the web app): countries and Israel's localities coloured by their figures.
    val stats = LocalStats.current
    // Its state is shared with its explorer, which is in the side panel on a big screen ([DataView]).
    var dataView by DataView::on
    var dataLayers by DataView::layers
    // The data view's explorer, in its drawer over the map on a phone: opens with the view.
    var explorerOpen by DataView::explorerOpen
    val sidePanels = LocalSidePanels.current
    var mapWidth by remember { mutableStateOf(0.dp) }
    val narrowScreen = mapWidth < NARROW_SCREEN
    val explorerWidth = minOf(EXPLORER_WIDTH, mapWidth * 0.88f)
    val explorerBeside = dataView && explorerOpen && !narrowScreen && !sidePanels
    val countryIndicator = dataLayers.country.takeUnless { dataLayers.timeZones }
    val localityIndicator = dataLayers.locality
    var hiddenCountryClasses by remember { mutableStateOf(emptySet<Int>()) }
    var hiddenLocalityClasses by remember { mutableStateOf(emptySet<Int>()) }
    var hiddenZoneClasses by remember { mutableStateOf(emptySet<Int>()) }
    var dataSelected by remember { mutableStateOf<String?>(null) }
    var dataZoom by remember { mutableStateOf(0.0) }
    val statsCountries = stats?.countries?.collectAsState()?.value
    val statsFigures = stats?.figures?.collectAsState()?.value.orEmpty()
    val statsLocalities = stats?.localities?.collectAsState()?.value
    val statsStatus = stats?.status?.collectAsState()?.value.orEmpty()
    val statsZones = stats?.timeZones?.collectAsState()?.value
    val statsBuilds = stats?.mapBuilds?.collectAsState()?.value
    // GPS traces over the data view's colours (their source can be turned off in Settings → Sources).
    val gpsTraces = TileSources.gpsTraces
    val dataOverlay = gpsTraces?.takeIf { dataView && dataLayers.gpsTraces }
    // The live layers: the rain radar, the weather at points, street photos, buses.
    val live = rememberLiveLayers(controller, stats, dataView, dataLayers)
    val radarSource = live.radar?.takeIf { dataView && dataLayers.rainRadar }?.let(app.maparound.stats.RainRadar::source)
    val dataRasters = listOfNotNull(dataOverlay, radarSource)
    val photoTiles = stats?.photoTiles?.takeIf { dataView && dataLayers.photos }
    /** New data layers picked: the hidden classes of what changed come back; Israel's picked from afar: there. */
    fun pickDataLayers(new: DataLayers) {
        if (new.country != dataLayers.country || new.timeZones != dataLayers.timeZones || new.mapBuilds != dataLayers.mapBuilds) {
            hiddenCountryClasses = emptySet()
            hiddenZoneClasses = emptySet()
        }
        if (new.weather != dataLayers.weather) live.hiddenWeather = emptySet()
        if (new.locality != dataLayers.locality) {
            hiddenLocalityClasses = emptySet()
            if (new.locality != null && dataZoom < LOCALITIES_ZOOM) controller.animateTo(31.6, 35.0, minZoom = 7)
        }
        dataLayers = new
    }
    // The explorer in the side panel picks through this too.
    androidx.compose.runtime.SideEffect { DataView.pick = ::pickDataLayers }
    // The data view's effects, in a function of their own (see MapTop).
    @Composable
    fun DataViewEffects() {
        LaunchedEffect(dataRasters, photoTiles) { controller.setDataTiles(dataRasters, photoTiles) }
        // The time zones' offsets are worked out again every quarter of an hour (clocks change).
        var zonesQuarter by remember { mutableStateOf(0L) }
        LaunchedEffect(stats, dataView, dataLayers.timeZones) {
            if (stats == null || !dataView || !dataLayers.timeZones) return@LaunchedEffect
            stats.loadTimeZones()
            while (true) {
                zonesQuarter = nowMillis() / ZONES_REFRESH_MS
                delay(ZONES_REFRESH_MS - nowMillis() % ZONES_REFRESH_MS)
            }
        }
        // How MapAround's maps stand: the list read once a visit, when the layer is first on.
        LaunchedEffect(stats, dataView, dataLayers.mapBuilds) {
            if (stats != null && dataView && dataLayers.mapBuilds) stats.loadMapBuilds()
        }
        LaunchedEffect(stats, dataView) {
            if (stats == null || !dataView) return@LaunchedEffect
            launch { stats.loadCountries() }
            launch { stats.loadLocalities() }
            while (true) {
                dataZoom = controller.zoomLevel()
                delay(400)
            }
        }
        // A data view in a link (the web app's `data=` in its address): open, with its layers and what
        // was tapped; the map stays where the link put it.
        LaunchedEffect(stats) {
            if (stats == null) return@LaunchedEffect
            DataViewLink.pending.collect { name ->
                val layers = name?.let(DataViewLink::layers) ?: return@collect
                DataViewLink.pending.value = null
                hiddenCountryClasses = emptySet()
                hiddenZoneClasses = emptySet()
                hiddenLocalityClasses = emptySet()
                live.hiddenWeather = emptySet()
                dataLayers = layers
                dataSelected = DataViewLink.pendingSelected
                DataViewLink.pendingSelected = null
                explorerOpen = false
                dataView = true
            }
        }
        // Left (here or in the side panel): nothing stays tapped.
        LaunchedEffect(dataView) { if (!dataView) dataSelected = null }
        // The data view as it is now, for the web app's address.
        LaunchedEffect(dataView, dataLayers, dataSelected) {
            DataViewLink.current.value = if (dataView) MapAddress.data(dataLayers) else null
            DataViewLink.currentSelected.value = dataSelected?.takeIf { dataView }
        }
        LaunchedEffect(stats, dataView, countryIndicator) {
            if (stats != null && dataView && countryIndicator != null) stats.loadFigures(countryIndicator, currentYear())
        }
        LaunchedEffect(
            dataView, statsCountries, statsFigures, countryIndicator, hiddenCountryClasses, statsLocalities, localityIndicator,
            hiddenLocalityClasses, statsZones, dataLayers.timeZones, hiddenZoneClasses, zonesQuarter, app.maparound.i18n.I18n.language,
            statsBuilds, dataLayers.mapBuilds,
        ) {
            if (!dataView) {
                controller.setStats(null, null, null)
                return@LaunchedEffect
            }
            val countriesJson = countryIndicator?.let { indicator ->
                statsCountries?.let { cs ->
                    withContext(Dispatchers.Default) {
                        StatsLayers.countries(cs, indicator, statsFigures[indicator].orEmpty(), hiddenCountryClasses, he = app.maparound.i18n.I18n.language == app.maparound.i18n.Language.HE)
                    }
                }
            }
            val localitiesJson = localityIndicator?.let { indicator ->
                statsLocalities?.let { ls -> withContext(Dispatchers.Default) { StatsLayers.localities(ls, indicator, hiddenLocalityClasses) } }
            }
            val zonesJson = statsZones?.takeIf { dataLayers.timeZones }?.let { zs ->
                val now = nowMillis()
                val offsets = zs.mapNotNull { z -> stats?.zoneOffset(z.name, now)?.let { z.name to it } }.toMap()
                withContext(Dispatchers.Default) { TimeZones.geoJson(zs, offsets, hiddenZoneClasses) }
            }
            // The map builds are drawn as the time zones are (one or the other colours the world).
            val buildsJson = statsBuilds?.takeIf { dataLayers.mapBuilds }?.let { b ->
                withContext(Dispatchers.Default) { app.maparound.stats.MapBuilds.geoJson(b, hiddenZoneClasses) }
            }
            controller.setStats(countriesJson, localitiesJson, zonesJson ?: buildsJson)
        }
        // The country whose card is open, highlighted on the map.
        LaunchedEffect(dataView, dataSelected, statsCountries, countryIndicator) {
            val iso = dataSelected?.takeIf { dataView && countryIndicator != null && it.startsWith(StatsLayers.TAP_COUNTRY) }
                ?.removePrefix(StatsLayers.TAP_COUNTRY)
            val geometry = iso?.let { statsCountries?.get(it)?.geometry }
            controller.setDataPoints(app.maparound.map.maplibre.MapStyle.DATA_SELECTED, geometry?.let(StatsLayers::selection))
        }
    }
    DataViewEffects()
    // The point of interest the pin is on (tapped on the map), while the pin stays there.
    var pinPoi by remember { mutableStateOf<app.maparound.map.MapPoi?>(null) }
    // "What's here?" open for a point (and the point of interest there).
    var whatsHere by remember { mutableStateOf<Pair<GeoPoint, app.maparound.map.MapPoi?>?>(null) }
    // The pinned place's own Wikipedia article and its picture, when its tags name one.
    var pinArticle by remember { mutableStateOf<Pair<app.maparound.map.MapPoi, app.maparound.search.Wikipedia.Article>?>(null) }
    // A photo over the whole screen.
    var viewPhoto by remember { mutableStateOf<app.maparound.search.Commons.Photo?>(null) }
    LaunchedEffect(pinPoi) {
        val place = pinPoi?.takeIf { it.hasArticle && !Sources.isOff(app.maparound.sources.SourceKind.WIKIPEDIA) } ?: return@LaunchedEffect
        if (pinArticle?.first == place) return@LaunchedEffect
        // Which article (in the user's language when there is one), then its picture.
        val a = app.maparound.search.Wikipedia.articleOf(place, I18n.contentLanguage) ?: return@LaunchedEffect
        pinArticle = place to a
        pinArticle = place to app.maparound.search.Wikipedia.withPicture(a)
    }
    var showMe by remember { mutableStateOf(false) }

    // Back closes what's open over the map, the top one first (search, the 3D view, sheets and
    // dialogs close themselves); with nothing open it leaves the app. Not while navigating.
    val somethingOpen = pin != null || selectedPlace != null || selectedParty != null || showMe ||
        dataSelected != null || dataView || explorerOpen || tools.active || (planner.active && !nav.active) || replay != null
    PlatformBackHandler(enabled = somethingOpen) {
        when {
            replay != null -> PartyReplay.close()
            pin != null -> pin = null
            selectedPlace != null -> selectedPlace = null
            selectedParty != null -> selectedParty = null
            showMe -> showMe = false
            explorerOpen -> explorerOpen = false
            dataSelected != null -> dataSelected = null
            dataView -> dataView = false
            tools.active -> ToolsSession.close()
            planner.active -> PlannerSession.close()
        }
    }
    val haptic = LocalHapticFeedback.current
    // (lat, lon, label) for the share sheet
    var shareTarget by remember { mutableStateOf<Triple<Double, Double, String?>?>(null) }
    // A map tool's point being dragged, and how far the finger is from it (so it doesn't jump).
    val toolDrag = remember { ToolDrag() }
    SideEffect {
        controller.listener = object : MapListener {
            /** The tool's point under the finger, if any: within a fingertip's reach on screen. */
            fun toolPointAt(p: GeoPoint): ToolsSession.Slot? =
                if (!ToolsSession.state.value.active || NavigationSession.state.value.active) null
                else ToolsSession.pointNear(p, controller.metresPerDp() * TOOL_GRAB_DP)

            /** Picks the tool's point under [p] to drag; false when there's none. */
            fun grabTool(p: GeoPoint): Boolean {
                val slot = toolPointAt(p) ?: return false
                val at = ToolsSession.pointOf(slot) ?: return false
                ToolsSession.select(slot)
                toolDrag.slot = slot
                toolDrag.dLat = at.lat - p.lat
                toolDrag.dLon = at.lon - p.lon
                toolDrag.moved = false
                return true
            }

            override fun onPress(point: GeoPoint): Boolean = grabTool(point)

            override fun onUserGesture() {
                follow = false
                firstPlace = false
            }

            override fun onLongPress(point: GeoPoint) {
                // A tool's point (where the map didn't give it to us at the press): picked up to drag.
                if (toolDrag.slot == null && grabTool(point)) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    return
                }
                if (toolDrag.slot != null) return
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

            override fun onLongPressMove(point: GeoPoint) {
                val slot = toolDrag.slot ?: return PlannerSession.drag(point)
                toolDrag.moved = true
                ToolsSession.drag(slot, GeoPoint(point.lat + toolDrag.dLat, point.lon + toolDrag.dLon))
            }

            override fun onLongPressEnd(point: GeoPoint) {
                val slot = toolDrag.slot ?: return PlannerSession.drop(point)
                // Lifted without moving: just picked (the next tap moves it).
                if (toolDrag.moved) ToolsSession.drag(slot, GeoPoint(point.lat + toolDrag.dLat, point.lon + toolDrag.dLon))
                toolDrag.slot = null
            }

            override fun onMyLocationTap() {
                showMe = true
            }

            override fun onTap(point: GeoPoint): Boolean = when {
                ToolsSession.state.value.active -> {
                    // On a point: picks it; elsewhere: moves the picked one there.
                    toolPointAt(point)?.let(ToolsSession::select) ?: ToolsSession.tap(point)
                    true
                }
                PlannerSession.state.value.active && !NavigationSession.state.value.active -> {
                    PlannerSession.add(point)
                    true
                }
                // The weather map on: the forecast where it was tapped.
                dataView && stats != null && dataLayers.weatherMaps -> {
                    val r = { v: Double -> kotlin.math.round(v * 1e4) / 1e4 }
                    dataSelected = app.maparound.stats.Weather.TAP + "${r(point.lat)},${r(point.lon)}"
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

            override fun onDataTap(key: String) {
                dataSelected = key
            }

            override fun onQueryTap(point: GeoPoint) {
                queried = point to controller.featuresAt(point, QUERY_RADIUS_DP)
            }
        }
    }
    // Navigation's view: tilted, the way ahead up, our position low, further out the faster we go.
    val navView = remember { NavView() }
    var navShown by remember { mutableStateOf(false) }

    /** Navigation's view at [loc], now. */
    val navigation3d by prefs.navigation3d.collectAsState()
    fun showNavigation(loc: GeoFix) {
        if (navView.zoom.isNaN()) navView.update(loc, NavigationSession.state.value)
        controller.followNavigation(loc.lat, loc.lon, navView.bearing, navView.zoom, tilted = prefs.navigation3d.value)
        navShown = true
    }
    // The map following navigation and the query, in a function of their own (see MapTop).
    @Composable
    fun NavigationEffects() {
        // 2D or 3D chosen while navigating: the view changes at once.
        LaunchedEffect(navigation3d) {
            if (NavigationSession.state.value.active && follow) location?.let(::showNavigation)
        }
        LaunchedEffect(location) {
            val loc = location
            controller.updateLocation(loc)
            val navigating = loc != null && NavigationSession.state.value.active
            if (navigating) {
                // The background service feeds navigation too; the same fix counts once.
                NavigationSession.onFix(loc!!)
                navView.update(loc, NavigationSession.state.value)
            }
            if (follow && loc != null) {
                if (navigating) showNavigation(loc) else controller.animateTo(loc.lat, loc.lon)
            }
        }
        LaunchedEffect(nav.active) {
            if (nav.active) {
                follow = true
                pin = null
                showMe = false
                navView.reset()
                location?.let(::showNavigation)
            } else {
                // Navigation's over, wherever it was stopped (the car, the notification, another tab):
                // the map flat and north up again, nothing of navigation's view left.
                navShown = false
                controller.endNavigation()
            }
        }
        LaunchedEffect(queryMode) {
            controller.setQueryMode(queryMode)
            if (!queryMode) queried = null
        }
        // The pin marks the point queried, while there is one.
        LaunchedEffect(pin, queried) { controller.setPin(queried?.first ?: pin) }
    }
    NavigationEffects()

    // ---- things other apps shared with us ---------------------------------------------------------
    // The embedded map's track: its name, length and climb over the map (it isn't kept anywhere),
    // and only the last one given (another link in its address replaces it).
    var embedTitle by remember { mutableStateOf<String?>(null) }
    // What other apps shared, taken in a function of its own (see MapTop).
    @Composable
    fun TakeIncoming() {
        fun showOnly(id: String) = tracksRepo.visible.value.filter { it != id }.forEach { tracksRepo.setVisible(it, false) }
        val incoming by Incoming.pending.collectAsState()
        // consume() changes `incoming`, which restarts this effect and cancels it: anything that waits
        // (a snackbar) goes in the screen's scope.
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
                    if (embedded) {
                        showOnly(i.saved.id)
                        embedTitle = RouteText.summary(i.saved.track)
                    } else {
                        scope.launch { snackbar.showSnackbar(T.map.importedTrack(i.saved.track.name, i.placesAdded)) }
                    }
                }
                is Incoming.Item.LinkedTrack -> {
                    Incoming.consume()
                    follow = false
                    val link = i.track
                    val name = link.name ?: T.map.sharedTrackName
                    // Opened before (the same link again): shown, not saved twice.
                    val known = tracksRepo.tracks.value.firstOrNull { it.track.name == name && it.track.points == link.points }
                    val track = known?.track ?: app.maparound.tracks.Track(name, app.maparound.tracks.TrackKind.IMPORTED, link.points, nowMillis())
                    val id = if (known != null) {
                        tracksRepo.setVisible(known.id, true)
                        known.id
                    } else {
                        tracksRepo.save(track).id
                    }
                    controller.showPoints(link.points)
                    if (embedded) {
                        showOnly(id)
                        embedTitle = RouteText.summary(track)
                    } else {
                        scope.launch { snackbar.showSnackbar(if (known != null) T.map.sharedTrackShown(name) else T.map.importedTrack(name, 0)) }
                    }
                }
                is Incoming.Item.Places -> {
                    Incoming.consume()
                    follow = false
                    prefs.setPlacesOnMap(true)
                    controller.showPoints(i.points)
                    if (!embedded) scope.launch { snackbar.showSnackbar(if (i.added == 0) T.map.placesAlreadySaved else T.map.placesAdded(i.added)) }
                }
                else -> Unit
            }
        }
    }
    TakeIncoming()


    var showLayers by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }
    // The drawers over the map (tools, planner, a replayed history) folded away; open again each time they open.
    var toolsFolded by rememberSaveable { mutableStateOf(false) }
    var plannerFolded by rememberSaveable { mutableStateOf(false) }
    var replayFolded by rememberSaveable { mutableStateOf(false) }
    // Each drawer opens unfolded (see MapTop).
    @Composable
    fun ResetFolds() {
        LaunchedEffect(tools.active) { if (tools.active) toolsFolded = false }
        LaunchedEffect(planner.active) { if (planner.active) plannerFolded = false }
        LaunchedEffect(replay?.memberId) { if (replay != null) replayFolded = false }
    }
    ResetFolds()
    var scene3d by remember { mutableStateOf<app.maparound.three.Camera3D?>(null) }
    var scene3dControl by remember { mutableStateOf<Scene3DControl?>(null) }
    var searching by remember { mutableStateOf(false) }

    /**
     * The map's button column, the same in 2D and 3D: search, layers, route and tools, 3D, the zoom
     * slider. In 3D ([in3d]) the 3D button is lit and goes back to 2D, route is greyed out, and tools
     * gives way to the relief's settings ([relief] open, [onRelief] toggles them).
     */
    @Composable
    fun MapButtons(
        modifier: Modifier,
        in3d: Boolean,
        zoom: app.maparound.map.ZoomTarget?,
        relief: Boolean,
        onRelief: () -> Unit,
        onSwitch3d: () -> Unit,
        bottomLimit: Float?,
    ) {
        Column(
            modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (openFull != null) {
                Tip(T.map.openMapInMapAround) { SmallFloatingActionButton(onClick = openFull) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = T.map.openMapInMapAround)
                } }
            } else {
                Tip(T.map.searchPlaces) {
                    SmallFloatingActionButton(onClick = { searching = true }) {
                        Icon(Icons.Filled.Search, contentDescription = T.map.searchPlaces)
                    }
                }
            }
            Tip(T.map.mapLayers) { SmallFloatingActionButton(onClick = { showLayers = true }) {
                Icon(Icons.Filled.Layers, contentDescription = T.map.mapLayers)
            } }
            // Query mode: on, it's coloured, and every tap on the map says what's there.
            Tip(T.map.queryHint) { SmallFloatingActionButton(
                onClick = { queryMode = !queryMode },
                containerColor = if (queryMode) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (queryMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.TravelExplore, contentDescription = T.map.query)
            } }
            if (!nav.active && !embedded) Tip(T.map.planRoute) { SmallFloatingActionButton(
                onClick = {
                    if (in3d) return@SmallFloatingActionButton
                    if (planner.active) {
                        PlannerSession.close()
                    } else {
                        ToolsSession.close()
                        PlannerSession.open()
                    }
                },
                modifier = Modifier.alpha(if (in3d) 0.38f else 1f),
                containerColor = if (planner.active && !in3d) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (planner.active && !in3d) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Route, contentDescription = T.map.planRoute)
            } }
            if (in3d) Tip(T.map.view3dSettings) { SmallFloatingActionButton(
                onClick = onRelief,
                containerColor = if (relief) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (relief) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Filled.Terrain, contentDescription = T.map.view3dSettings)
            } } else if (!nav.active && !embedded) Tip(T.map.mapToolsButton) { SmallFloatingActionButton(
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
                Icon(Icons.Filled.Straighten, contentDescription = T.map.mapToolsButton)
            } }
            // 2D / 3D: says where it goes. Navigating, it tilts navigation's view (3D) or lays it flat,
            // north up (2D); otherwise it opens the 3D view, whose "2D" brings the flat map back.
            val navigating = nav.active && !in3d
            val lit = if (navigating) navigation3d else in3d
            val what = when {
                navigating -> if (navigation3d) T.map.navFlat else T.map.navTilted
                in3d -> T.map.close3d
                else -> T.map.view3dHere
            }
            Tip(what) {
                SmallFloatingActionButton(
                    onClick = if (navigating) ({ prefs.setNavigation3d(!navigation3d) }) else onSwitch3d,
                    containerColor = if (lit) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                    contentColor = if (lit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = what },
                ) {
                    Text(if (lit) "2D" else "3D", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            // The zoom slider.
            zoom?.let { ZoomScale(it, Modifier.padding(top = 4.dp), bottomLimit = bottomLimit) }
        }
    }

    /**
     * The cards over the bottom of the map: navigating's speed, a query, my location, a place, a
     * party member, the pin, and the data view's. (A function of its own: all in MapScreen made a
     * method too big for Android's verifier.)
     */
    @Composable
    fun ColumnScope.Cards() {
            // Navigating: how fast we're going.
            if (nav.active) NavSpeed(location, speedUnit, Modifier.padding(bottom = 4.dp))
            queried?.let { (p, found) -> QueryCard(p, found, onClose = { queried = null }) }
            if (showMe) {
                location?.let { me ->
                    PinCard(
                        GeoPoint(me.lat, me.lon), T.map.myLocationWithin(me.accuracyM?.let { Fmt.height(it) }),
                        null, onClose = { showMe = false },
                        onRoute = null,
                        onShare = { shareTarget = Triple(me.lat, me.lon, T.map.myLocation) },
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
                PartyMarkerCard(key, parties.ifEmpty { listOf(party) }, location, speedUnit, onClose = { selectedParty = null }, onShare = { la, lo, title -> shareTarget = Triple(la, lo, title) }, onRoute = { target ->
                    selectedParty = null
                    ToolsSession.close()
                    PlannerSession.close()
                    PlannerSession.open()
                    location?.let { PlannerSession.add(GeoPoint(it.lat, it.lon)) }
                    PlannerSession.add(target)
                }, onHistory = { owner, member ->
                    selectedParty = null
                    // The history is the shown party's: theirs, shown.
                    partyControl.select(owner)
                    PartyReplay.open(member, partyControl.history(member.id))
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
                                    app.maparound.search.Wikipedia.linkFor(place, I18n.contentLanguage)?.let(platform::openUrl)
                                }
                            }
                        }
                    },
                    // Embedded: no planner, places or tools to take it to.
                    onRoute = if (embedded) null else ({
                        pin = null
                        ToolsSession.close()
                        PlannerSession.close()
                        PlannerSession.open()
                        location?.let { PlannerSession.add(GeoPoint(it.lat, it.lon)) }
                        PlannerSession.add(p)
                    }),
                    onShare = { shareTarget = Triple(p.lat, p.lon, pinLabel) },
                    onSavePlace = if (embedded) null else ({ newPlace(p.lat, p.lon, pinLabel) }),
                    onTool = if (embedded) null else ({ mode ->
                        pin = null
                        PlannerSession.close()
                        ToolsSession.open(mode, p)
                    }),
                    // The parties that are on: there, one picked (and shown on the Party screen).
                    meetParties = if (partyControl.watchOnly) emptyList() else parties.ifEmpty { listOf(party) }.filter { it.active }.mapNotNull { it.party },
                    onMeetHere = { k ->
                        partyControl.select(k)
                        partyControl.setMeeting(p.lat, p.lon, pinLabel ?: T.map.meetingPoint)
                        pin = null
                    },
                )
            }
            if (dataView && stats != null && pin == null && !(explorerOpen && narrowScreen && !sidePanels)) {
                // A tapped country or locality, else what colours the map.
                val key = dataSelected
                val country = key?.takeIf { it.startsWith(StatsLayers.TAP_COUNTRY) }?.let { statsCountries?.get(it.removePrefix(StatsLayers.TAP_COUNTRY)) }
                val locality = key?.takeIf { it.startsWith(StatsLayers.TAP_LOCALITY) }
                    ?.let { k -> statsLocalities?.firstOrNull { it.code.toString() == k.removePrefix(StatsLayers.TAP_LOCALITY) } }
                val zone = key?.takeIf { it.startsWith(TimeZones.TAP_ZONE) }?.removePrefix(TimeZones.TAP_ZONE)
                val build = key?.takeIf { it.startsWith(app.maparound.stats.MapBuilds.TAP) }?.removePrefix(app.maparound.stats.MapBuilds.TAP)
                val weatherAt = key?.takeIf { it.startsWith(app.maparound.stats.Weather.TAP) }?.let(app.maparound.stats.Weather::placeOf)
                val photo = key?.takeIf { it.startsWith(app.maparound.stats.StreetPhotos.TAP) }?.removePrefix(app.maparound.stats.StreetPhotos.TAP)
                val bus = key?.takeIf { it.startsWith(app.maparound.stats.Buses.TAP) }?.removePrefix(app.maparound.stats.Buses.TAP)?.toLongOrNull()
                    ?.let { ride -> live.buses.firstOrNull { it.rideId == ride } }
                val camera = key?.takeIf { it.startsWith(app.maparound.stats.Cameras.TAP) }?.removePrefix(app.maparound.stats.Cameras.TAP)
                    ?.let { id -> live.cameras.firstOrNull { it.id == id } }
                // Israel's localities' legend zoomed in, when they're on; else the countries'.
                val showLocalities = dataZoom >= LOCALITIES_ZOOM && dataLayers.locality != null
                when {
                    camera != null -> CameraCard(camera, onClose = { dataSelected = null })
                    weatherAt != null -> WeatherCard(weatherAt.first, weatherAt.second, stats, onClose = { dataSelected = null })
                    photo != null -> PhotoCard(photo, stats, onClose = { dataSelected = null })
                    bus != null -> BusCard(bus, live.routes[bus.lineRef], stats, onClose = { dataSelected = null })
                    zone != null -> ZoneCard(zone, stats::zoneOffset, onClose = { dataSelected = null })
                    build != null && statsBuilds != null -> MapBuildCard(statsBuilds, build, onClose = { dataSelected = null })
                    country != null -> CountryCard(country, statsFigures, stats.flagUrl(country), onClose = { dataSelected = null })
                    locality != null -> LocalityCard(
                        locality,
                        onPin = {
                            pin = GeoPoint(locality.lat, locality.lon)
                            pinLabel = if (app.maparound.i18n.I18n.language == app.maparound.i18n.Language.HE) locality.nameHe else locality.nameEn
                            dataSelected = null
                        },
                        onClose = { dataSelected = null },
                    )
                    else -> DataViewPanel(
                        showLocalities = showLocalities,
                        layers = dataLayers,
                        hidden = when {
                            // The map builds' legend at every zoom; the time zones' (their classes kept alike).
                            dataLayers.mapBuilds -> hiddenZoneClasses
                            showLocalities -> hiddenLocalityClasses
                            dataLayers.timeZones -> hiddenZoneClasses
                            else -> hiddenCountryClasses
                        },
                        onToggleClass = { c ->
                            when {
                                dataLayers.mapBuilds -> hiddenZoneClasses = hiddenZoneClasses.toggled(c)
                                showLocalities -> hiddenLocalityClasses = hiddenLocalityClasses.toggled(c)
                                dataLayers.timeZones -> hiddenZoneClasses = hiddenZoneClasses.toggled(c)
                                else -> hiddenCountryClasses = hiddenCountryClasses.toggled(c)
                            }
                        },
                        hiddenWeather = live.hiddenWeather,
                        onToggleWeather = { c -> live.hiddenWeather = live.hiddenWeather.toggled(c) },
                        weatherTime = { WeatherTimeRow(live, stats) },
                        hiddenCameras = live.hiddenCameras,
                        onToggleCameras = { c -> live.hiddenCameras = live.hiddenCameras.toggled(c) },
                        notes = liveNotes(live, dataLayers, dataZoom, stats) + listOfNotNull(
                            statsBuilds?.takeIf { dataLayers.mapBuilds }?.let { b ->
                                T.stats.buildsSummary(b.built, b.total, b.latest?.let { Fmt.dayMonth(it) }) + " · " + T.stats.tapARegion
                            },
                        ),
                        status = statsStatus,
                        onGoTo = {
                            if (showLocalities) controller.moveTo(MapPos(30.0, 20.0, 2))
                            else controller.animateTo(31.6, 35.0, minZoom = 7)
                        },
                        onExplore = { explorerOpen = true },
                        onClose = { dataView = false; dataSelected = null; explorerOpen = false },
                    )
                }
            }
    }

    // What's over the map, and the sheets over the screen, in functions of their own as Cards is:
    // all in MapScreen, it was too big for Android to compile (it only ran interpreted).
    @Composable
    fun BoxScope.MapTop() {
        // The scale, in the top corner away from the buttons (the compass goes under it).
        if (scene3d == null) ScaleLine(controller, Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp))

        if (onOpenOffline != null && showingOffline && localMaps.isEmpty() && streamed.isEmpty() && !planner.active && !tools.active && !nav.active) {
            ElevatedCard(
                Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 72.dp).widthIn(max = CARD_MAX_WIDTH),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.map.noOfflineMapsYet, style = MaterialTheme.typography.titleMedium)
                    Text(
                        T.map.noOfflineMapsHint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (offer != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { OfflineFocus.show(offer.path); onOpenOffline() }) {
                                Text(T.map.getRegion(offer.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = onOpenOffline) { Text(T.map.allMaps) }
                        }
                    } else {
                        Button(onClick = onOpenOffline) { Text(T.map.getOfflineMaps) }
                    }
                }
            }
        } else if (onOpenOffline != null && offer != null && !planner.active && !tools.active && !nav.active) {
            // Looking where no installed map reaches: the one made for it.
            ElevatedCard(
                Modifier.align(Alignment.TopCenter).padding(top = 56.dp, start = 16.dp, end = 72.dp).widthIn(max = CARD_MAX_WIDTH),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(T.map.noOfflineMapHere, style = MaterialTheme.typography.titleSmall)
                        Text(
                            // Streamed meanwhile: the map is there, online.
                            if (showingOffline && streamed.isNotEmpty()) T.map.shownOnline(offer.label) else offer.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { OfflineFocus.show(offer.path); onOpenOffline() }) { Text(T.map.getMap) }
                    Tip(T.common.notNow) { IconButton(onClick = { declinedRegions += offer.path }) { Icon(Icons.Filled.Close, contentDescription = T.common.notNow) } }
                }
            }
        }

        // Under the 3D view the 3D view's own column is shown: this one (its zoom slider following the
        // map as it moves) would only redraw the screen behind it.
        if (scene3d == null) MapButtons(
            Modifier.align(Alignment.TopEnd), in3d = false, zoom = controller, relief = false, onRelief = {},
            onSwitch3d = {
                follow = false
                controller.camera3D()?.let { scene3d = it }
            },
            bottomLimit = bottomButtonsTop,
        )

        if (embedded) embedTitle?.let { title ->
            Surface(
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 12.dp, end = 64.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
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
                            T.map.gettingTerrain(minOf(p.done + 1, p.total), p.total),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    /** The cards and buttons along the bottom, the panels, the data explorer's drawer. */
    @Composable
    fun BoxScope.MapPanels() {
        // Beside the explorer's drawer on a wide screen.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = if (explorerBeside) explorerWidth else 0.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        // The cards: as wide as the screen on a phone, no wider than CARD_MAX_WIDTH on a big one.
        Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp).wrapContentWidth(Alignment.Start).widthIn(max = CARD_MAX_WIDTH)) {
            Cards()
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = listOfNotNull(
                    if (!showingOffline) onlineSource?.attribution else T.map.osmAttribution,
                    if (showingOffline) backgroundAttribution(background, rasterMaps) else null,
                    if (terrainShown) terrainCredit(elevationSourceNow) else null,
                    if (trailOverlay) TileSources.trailsOverlay.attribution else null,
                    // The countries' and localities' sources, while they're shown.
                    if (dataView && stats != null && (dataLayers.country != null || dataLayers.locality != null)) T.stats.credits else null,
                    if (dataView && stats != null && dataLayers.timeZones) T.stats.zonesCredit else null,
                    if (dataView && stats != null && dataLayers.mapBuilds) T.stats.buildsCredit else null,
                    dataOverlay?.attribution,
                ).plus(if (dataView && stats != null) liveCredits(dataLayers) else emptyList()).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                maxLines = if (dataView && stats != null) 4 else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(
            Modifier.onGloballyPositioned { bottomButtonsTop = it.positionInRoot().y }.padding(12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!recorder.recording && !embedded) {
                Tip(T.map.recordTrack) { SmallFloatingActionButton(onClick = startRecording) {
                    Icon(Icons.Filled.FiberManualRecord, contentDescription = T.map.recordTrack, tint = Color(0xFFD50000))
                } }
            }
            if (!embedded) Tip(T.map.myLocation) { FloatingActionButton(
                onClick = {
                    if (!hasLocation) {
                        requestLocation()
                    } else {
                        follow = true
                        val loc = location
                        if (loc != null) {
                            if (nav.active) showNavigation(loc) else controller.animateTo(loc.lat, loc.lon, minZoom = 15)
                        } else {
                            scope.launch { snackbar.showSnackbar(T.map.waitingForFix) }
                        }
                    }
                },
                containerColor = if (follow) MaterialTheme.colorScheme.primary else FloatingActionButtonDefaults.containerColor,
                contentColor = if (follow) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    if (follow) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                    contentDescription = T.map.myLocation,
                )
            } }
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
        } else if (replay != null) {
            PartyReplayPanel(replay!!, speedUnit, folded = replayFolded, onFold = { replayFolded = it })
        } else if (planner.active) {
            PlannerPanel(
                planner,
                onSave = { saveDialog = true },
                onShare = {
                    PlannerSession.currentTrack(T.map.routeName(Fmt.dayMonth(nowMillis())))?.let { platform.shareRoute(it, null) }
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
                folded = plannerFolded,
                onFold = { plannerFolded = it },
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
                folded = toolsFolded,
                onFold = { toolsFolded = it },
            )
        }
        }
        // On a phone; on a big screen the explorer is in the side panel (Root).
        if (stats != null && !sidePanels) DataExplorerDrawer(
            open = dataView && explorerOpen,
            narrow = narrowScreen,
            width = explorerWidth,
            layers = dataLayers,
            offers = dataOffers(stats),
            onLayers = { pickDataLayers(it) },
            onClose = { explorerOpen = false },
            onLeave = {
                dataView = false
                dataSelected = null
                explorerOpen = false
            },
            inView = dataView,
            onOpen = { explorerOpen = true },
        )
    }

    /** The sheets and dialogs over the whole screen. */
    @Composable
    fun Sheets() {
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
            // The 3D view drapes the same map: its sources' credits (the relief's are the 3D screen's).
            val mapCredits = listOfNotNull(
                if (!showingOffline) onlineSource?.attribution else T.map.osmAttribution,
                if (showingOffline) backgroundAttribution(background, rasterMaps) else null,
                if (trailOverlay) TileSources.trailsOverlay.attribution else null,
            ).joinToString(" · ")
            Scene3DScreen(
                req, mapCredits,
                onClose = { last ->
                    scene3d = null
                    scene3dControl = null
                    // The 2D map comes back where the 3D view was looking: flat, north up.
                    last?.let(controller::showFrom3D)
                    controller.endNavigation()
                },
                onControl = { scene3dControl = it },
                buttons = { zoom, relief, onRelief, close, limit ->
                    MapButtons(Modifier, in3d = true, zoom = zoom, relief = relief, onRelief = onRelief, onSwitch3d = close, bottomLimit = limit)
                },
            ) { view3d(it) }
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
                    // In 3D: the view goes there too.
                    scene3dControl?.lookAt(r.lat, r.lon)
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
                    scope.launch { snackbar.showSnackbar(T.map.savedToPlaces(it.name)) }
                }
            })
        }

        if (saveDialog) {
            SaveRouteDialog(
                suggested = planner.editing?.name ?: Fmt.dayMonthTime(nowMillis()).let {
                    if (planner.profile == RouteProfile.LINE) T.map.drawnRouteName(it) else T.map.routeName(it)
                },
                editing = planner.editing != null,
                onDismiss = { saveDialog = false },
                onSave = { name, asNew ->
                    saveDialog = false
                    PlannerSession.save(name, asNew)
                    PlannerSession.close()
                    scope.launch { snackbar.showSnackbar(T.map.savedToRoutes(name)) }
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
                localTrails = localTrails,
                places = placesOn,
                inParty = party.party != null,
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
                onLegend = {
                    showLayers = false
                    showLegend = true
                },
                dataView = if (stats != null) dataView else null,
                onDataView = {
                    dataView = it
                    explorerOpen = it
                    dataSelected = null
                    showLayers = false
                },
                onDismiss = { showLayers = false },
            )
        }

        if (showLegend) {
            // The legend of what's on screen, made from it: the offline style (with its options, and
            // over satellite imagery or a raster map only what it draws there), or the online map.
            val legendThemes = LocalMapThemes.current
            val satelliteUnder = background == app.maparound.data.Prefs.BACKGROUND_SATELLITE
            val rasterUnder = background.startsWith(app.maparound.data.Prefs.BACKGROUND_RASTER)
            val overImagery = showingOffline && (satelliteUnder || rasterUnder)
            val styleShown = showingOffline && (!overImagery || vectorOnBackground)
            val sections by produceState<List<app.maparound.map.legend.MapLegend.Section>?>(null, styleShown, overImagery, theme, categories, I18n.language) {
                value = if (!styleShown) null else withContext(Dispatchers.Default) {
                    legendThemes.themeXml(theme)?.let { app.maparound.map.legend.MapLegend.build(it, categories, overImagery = overImagery) }
                }
            }
            val symbolPx = with(LocalDensity.current) { 22.dp.roundToPx() }
            val src = onlineSource
            LegendSheet(
                mapName = if (src != null) TileSources.title(src) else themes.firstOrNull { it.id == theme }?.let(MapThemes::title) ?: theme,
                sections = sections,
                online = src?.let { app.maparound.map.legend.OnlineLegends.of(it) }
                    // Under the offline map: the imagery itself needs none; a raster map's own legend is on the sheet it scans.
                    ?: if (overImagery) (if (satelliteUnder) app.maparound.map.legend.OnlineLegends.Info.Imagery else app.maparound.map.legend.OnlineLegends.Info.None) else null,
                trailOverlay = trailOverlay,
                symbol = { path -> legendThemes.symbol(theme, path, symbolPx) },
                onDismiss = { showLegend = false },
                under = when {
                    !overImagery -> null
                    !vectorOnBackground -> if (satelliteUnder) T.legend.imageryAlone else T.legend.rasterAlone
                    satelliteUnder -> T.legend.overImagery
                    else -> T.legend.overRaster
                },
                overSatellite = satelliteUnder && vectorOnBackground,
            )
        }
    }

    /** The whole screen: the map, what's over it, the sheets (one call from MapScreen, not three). */
    @Composable
    fun Screen() {
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().onSizeChanged { mapWidth = with(density) { it.width.toDp() } }) {
            mapView(controller, Modifier.fillMaxSize())
            MapTop()
            MapPanels()
        }
        Sheets()
    }
    Screen()
}

/** Saved places as map markers. */
fun placeMapMarkers(places: List<app.maparound.places.Place>): List<PlaceMarker> =
    places.map { PlaceMarker(it.id, it.lat, it.lon, it.name, it.category.emoji, it.category.argb.toInt()) }

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun PartyMarkerCard(
    key: String,
    parties: List<PartyState>,
    myLocation: GeoFix?,
    speedUnit: app.maparound.sensors.SpeedUnit,
    onClose: () -> Unit,
    onShare: (Double, Double, String) -> Unit,
    onRoute: (GeoPoint) -> Unit,
    /** Replays the member's way so far, in their party. */
    onHistory: (app.maparound.party.PartyKey, app.maparound.party.Member) -> Unit,
) {
    val target = partyTarget(parties, key)
    val member = (target as? PartyTarget.MemberOf)?.member
    val meet = (target as? PartyTarget.MeetingOf)?.meeting
    val radio = (target as? PartyTarget.Radio)?.node
    val mark = (target as? PartyTarget.MarkOf)?.mark
    val owner = (target as? PartyTarget.MemberOf)?.party ?: (target as? PartyTarget.MeetingOf)?.party ?: (target as? PartyTarget.MarkOf)?.party
    val markAt = mark?.let { markAnchor(it.shape, myLocation?.let { me -> GeoPoint(me.lat, me.lon) }) }
    // Where its marker is (partyMapMarkers): the newest from a shown connection; an SOS, the newest.
    val memberAt = member?.let { m ->
        if (m.sos) m.position?.let { app.maparound.party.TimedPosition(it, m.positionAt) } else shownPosition(m, owner?.hiddenConnections.orEmpty())
    }
    val radioAt = radio?.position
    val (title, lat, lon, at) = when {
        member != null && memberAt != null ->
            Quad(if (member.sos) T.party.sosName(member.displayName) else member.displayName, memberAt.position.lat, memberAt.position.lon, memberAt.at)
        radio != null && radioAt != null -> Quad(T.map.radioNamed(radio.name), radioAt.lat, radioAt.lon, radio.heardAt)
        meet != null -> Quad(T.map.meetingPoint, meet.lat, meet.lon, 0L)
        mark != null && markAt != null -> Quad(mark.name.ifBlank { T.map.partyMark }, markAt.lat, markAt.lon, mark.edited ?: 0L)
        else -> return
    }
    Card(Modifier.widthIn(max = 360.dp).padding(bottom = 6.dp)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            val parts = mutableListOf<String>()
            myLocation?.let { me ->
                val from = GeoPoint(me.lat, me.lon)
                val to = GeoPoint(lat, lon)
                parts += T.map.distanceToward(Fmt.distance(Geo.distance(from, to).toFloat()), Fmt.heading(Geodesy.bearing(from, to).toFloat()))
            }
            // When: how long ago, and the time it was taken (theirs).
            if (at > 0) parts += Fmt.relativeTime(at) + " (" + Fmt.moment(at, seconds = true) + ")"
            member?.let { parts += T.map.via(it.heard.keys.joinToString(" + ") { v -> v.label }) }
            if (member?.sos == true && member.sosText.isNotBlank()) parts.add(0, member.sosText)
            // A mark: its note, a line's length, who changed it last.
            mark?.let { k ->
                if (k.note.isNotBlank()) parts.add(0, k.note)
                markLength(k.shape)?.let { parts += Fmt.distance(it.toFloat()) }
                k.editor?.let { ed ->
                    val who = if (ed == owner?.me?.id) T.party.you else owner?.members?.firstOrNull { it.id == ed }?.displayName
                    who?.let { parts += T.map.markChangedBy(it) }
                }
            }
            // Several parties on the map: whose it is.
            if (parties.count { it.party != null } > 1) owner?.party?.let { parts.add(0, it.name) }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            member?.let { m ->
                // No GPS fix now: the marker stays at the last good position; when they were heard.
                noFixLine(m, nowMillis())?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
                // Speed and heading, height, how exact, battery.
                memberDetails(m, speedUnit).takeIf { it.isNotEmpty() }?.let { details ->
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // The buttons wrap to a second line where they don't fit (narrow phones, Hebrew, big text).
            FlowRow {
                val ownerKey = owner?.party
                if (member != null && ownerKey != null && (owner.historyCounts[member.id] ?: 0) > 0) {
                    TextButton(onClick = { onHistory(ownerKey, member) }) {
                        Icon(Icons.Filled.History, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(T.party.history)
                    }
                }
                TextButton(onClick = { onRoute(GeoPoint(lat, lon)) }) {
                    Icon(Icons.Filled.Route, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(T.map.routeHere)
                }
                TextButton(onClick = { onShare(lat, lon, title) }) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(T.common.share)
                }
            }
        }
    }
}

private data class Quad(val a: String, val b: Double, val c: Double, val d: Long)

/** A map tool's point being dragged: which, and the finger's offset from it (degrees). */
private class ToolDrag {
    var slot: ToolsSession.Slot? = null
    var dLat = 0.0
    var dLon = 0.0
    var moved = false
}

/** How near (dp on screen) a press has to be to a tool's point to pick it up. */
private const val TOOL_GRAB_DP = 26.0

/** Query mode looks this far around a tap for what the map draws. */
private const val QUERY_RADIUS_DP = 6.0

/** The replay's frames, ms. */
private const val REPLAY_FRAME_MS = 40L

/** At most this many redraws of a replayed way's line (it's sent whole each time). */
private const val REPLAY_TRAIL_UPDATES = 300

@Composable
private fun PinCard(
    pin: GeoPoint,
    label: String?,
    myLocation: GeoFix?,
    onClose: () -> Unit,
    onRoute: (() -> Unit)?,
    onShare: () -> Unit,
    /** Sets the meeting point here in one of [meetParties] (the parties that are on; none: no button). */
    meetParties: List<app.maparound.party.PartyKey> = emptyList(),
    onMeetHere: (app.maparound.party.PartyKey) -> Unit = {},
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
    // In the coordinate format chosen (tapping it opens the converter with every other).
    val primary = Fmt.point(pin.lat, pin.lon)
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
                        ltr(primary),
                        style = if (label == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    )
                }
                Tip(T.common.close) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
            }
            if (myLocation != null) {
                val from = GeoPoint(myLocation.lat, myLocation.lon)
                val bearing = Geodesy.bearing(from, pin).toFloat()
                Text(
                    T.map.fromYou(Fmt.distance(Geo.distance(from, pin).toFloat()), Fmt.heading(bearing)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (onWhatsHere != null || onWikipedia != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onWhatsHere != null) {
                        TextButton(onClick = onWhatsHere) {
                            Icon(Icons.Filled.TravelExplore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(T.map.whatsHere)
                        }
                    }
                    if (onWikipedia != null) {
                        TextButton(onClick = onWikipedia) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(T.map.wikipedia)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onRoute != null) {
                    Tip(T.map.routeHere) { IconButton(onClick = onRoute) { Icon(Icons.Filled.Route, contentDescription = T.map.routeHere) } }
                }
                Tip(T.map.shareLocation) { IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = T.map.shareLocation) } }
                Tip(T.map.copyCoordinates) { IconButton(onClick = { clipboard.setText(AnnotatedString(primary)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = T.map.copyCoordinates)
                } }
                if (onSavePlace != null) {
                    Tip(T.map.saveAsPlace) { IconButton(onClick = onSavePlace) { Icon(Icons.Filled.BookmarkAdd, contentDescription = T.map.saveAsPlace) } }
                }
                if (onTool != null) {
                    Tip(T.map.whatCanBeSeen) { IconButton(onClick = { onTool(ToolsSession.Mode.VIEW) }) {
                        Icon(Icons.Filled.Visibility, contentDescription = T.map.whatCanBeSeen)
                    } }
                }
                if (meetParties.isNotEmpty()) {
                    // Several parties on: which one's meeting point.
                    var choose by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { if (meetParties.size == 1) onMeetHere(meetParties[0]) else choose = true }) { Text(T.map.meetHere) }
                        DropdownMenu(expanded = choose, onDismissRequest = { choose = false }) {
                            for (k in meetParties) DropdownMenuItem(text = { Text(k.name) }, onClick = { choose = false; onMeetHere(k) })
                        }
                    }
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
    /** In a party: its layer can go on and off. */
    inParty: Boolean,
    background: String,
    vectorOnBackground: Boolean,
    rasterMaps: List<app.maparound.raster.RasterMap>,
    onRasterError: (Throwable) -> Unit,
    onMessage: (String) -> Unit,
    onOpenOffline: (() -> Unit)?,
    onSelect: (String) -> Unit,
    onAddCustom: (OnlineSource) -> Unit,
    onRemoveCustom: (String) -> Unit,
    onLegend: () -> Unit,
    /** The data view: on, off, or null where the app has none. */
    dataView: Boolean?,
    onDataView: (Boolean) -> Unit,
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
                    onMessage(T.map.stylesAdded(added.size, added[0].name))
                },
                { onMessage(it.message ?: T.map.couldntImportStyle) },
            )
        }
    }
    val importSources = rememberFilePicker(listOf("*/*")) { file ->
        if (file == null) return@rememberFilePicker
        SourceList.decode(Xml.decode(file.bytes)).fold(
            { found ->
                val added = prefs.addCustomSources(found, "custom_${nowMillis()}")
                onMessage(
                    if (added.isEmpty()) T.map.tileSourcesAlreadyListed else T.map.tileSourcesAdded(added.size, added[0].name),
                )
            },
            { onMessage(it.message ?: T.map.couldntReadList) },
        )
    }
    // Kept while the app runs: the sheet opens on the tab last looked at.
    var tab by rememberSaveable { mutableStateOf(LAYERS_TAB_MAP) }
    val partyOn by prefs.partyOnMap.collectAsState()
    val grid by prefs.showGrid.collectAsState()
    val terrainShown = selectedId == TileSources.OFFLINE_ID
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // The map under everything, its terrain, and what goes over it; each tab with how many of its layers are on.
        PrimaryTabRow(selectedTabIndex = tab) {
            LayersTab(tab == LAYERS_TAB_MAP, T.map.baseMap, 0) { tab = LAYERS_TAB_MAP }
            LayersTab(tab == LAYERS_TAB_TERRAIN, T.map.terrain, if (terrainShown) listOf(contours, hillshade).count { it } else 0) { tab = LAYERS_TAB_TERRAIN }
            LayersTab(
                tab == LAYERS_TAB_OVERLAYS, T.map.overlays,
                listOf(partyOn && inParty, trails, places, grid, dataView == true).count { it },
            ) { tab = LAYERS_TAB_OVERLAYS }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 24.dp)) {
            if (tab == LAYERS_TAB_TERRAIN) {
                LayerSwitch(
                    Icons.Filled.Terrain, Color(0xFF8D6E63), T.map.contourLines,
                    when {
                        !terrainShown -> T.map.terrainOnOffline
                        !MapThemes.drawsTerrain(theme) -> T.map.contoursOnlyIn
                        else -> T.map.contoursHint
                    },
                    checked = contours,
                    enabled = terrainShown && MapThemes.drawsTerrain(theme),
                ) { prefs.setContours(it) }
                LayerSwitch(
                    Icons.Filled.Landscape, Color(0xFF6D8B74), T.map.hillShading,
                    if (terrainShown) T.map.hillShadingHint else T.map.terrainOnOffline,
                    checked = hillshade,
                    enabled = terrainShown,
                ) { prefs.setHillshade(it) }
                if (terrainTiles == 0 && onOpenOffline != null) {
                    TextButton(onClick = onOpenOffline, modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(T.map.terrainComesByItself)
                    }
                }
            }
            if (tab == LAYERS_TAB_OVERLAYS) {
                LayerSwitch(
                    Icons.Filled.Groups, Color(0xFF1E88E5), T.map.partyLayer,
                    if (inParty) T.map.partyLayerHint else T.map.partyLayerNone,
                    checked = partyOn && inParty,
                    enabled = inParty,
                ) { prefs.setPartyOnMap(it) }
                // Each party and each connection, on and off.
                if (partyOn && inParty) PartyLayerRows()
                LayerSwitch(
                    Icons.AutoMirrored.Filled.DirectionsWalk, Color(0xFFD84315), T.map.markedTrails,
                    if (localTrails) T.map.trailsOfflineInTopo else T.map.trailsOnline,
                    checked = trails,
                ) { prefs.setTrails(it) }
                LayerSwitch(Icons.Filled.Place, Color(0xFF8E24AA), T.map.savedPlaces, T.map.savedPlacesHint, checked = places) { prefs.setPlacesOnMap(it) }
                // The coordinate grid, in the format chosen for coordinates; its spacing follows the zoom.
                val gridIn by prefs.coordFormat.collectAsState()
                LayerSwitch(Icons.Filled.GridOn, Color(0xFF546E7A), T.map.coordinateGrid, T.map.coordinateGridHint(gridIn.label), checked = grid) { prefs.setShowGrid(it) }
                if (dataView != null) {
                    LayerSwitch(Icons.Filled.Layers, Color(0xFF00897B), T.stats.dataView, T.stats.dataViewSubtitle, checked = dataView, onChange = onDataView)
                }
                // The map on a monitor, TV or wireless display: only while one is connected.
                LocalSecondScreen.current?.let { second ->
                    val screenName by second.display.collectAsState()
                    val onSecond by second.showing.collectAsState()
                    screenName?.let { name ->
                        LayerSwitch(Icons.Filled.Cast, Color(0xFF5E35B1), T.map.secondScreen, T.map.secondScreenSubtitle(name), checked = onSecond) { second.show(it) }
                    }
                }
            }
            if (tab == LAYERS_TAB_MAP) {
            // What the map on screen shows: its legend.
            OutlinedButton(onClick = onLegend, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(T.legend.legend)
            }
            SheetHeader(T.map.sectionOffline)
            LayerRow(
                selected = selectedId == TileSources.OFFLINE_ID,
                title = T.map.offlineVectorMaps,
                subtitle = if (offlineCount == 0) T.map.noMapsDownloadedYet else T.map.mapFilesOnDevice(offlineCount),
                icon = Icons.Filled.DownloadForOffline,
                onClick = { onSelect(TileSources.OFFLINE_ID) },
            )
            // Where none is downloaded, the region's map read over the internet (where the app can).
            if (LocalOfflineData.current.streamed != null) {
                val streaming by prefs.streamMaps.collectAsState()
                Column(Modifier.padding(start = 72.dp, end = 16.dp)) {
                    SwitchRow(T.map.streamMaps, T.map.streamMapsHint, checked = streaming) { prefs.setStreamMaps(it) }
                }
            }
            Column(Modifier.padding(start = 56.dp, end = 16.dp)) {
                Text(T.map.style, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                themes.forEach { t ->
                    StyleRow(
                        theme = t,
                        selected = t.id == theme,
                        onClick = { showStyle(t.id) },
                        onRemove = if (!t.imported) null else {
                            {
                                themeLib.delete(t.id)
                                onMessage(T.map.styleRemoved(t.name))
                            }
                        },
                    )
                }
                if (themeLib.canImport) {
                    TextButton(onClick = importTheme) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(T.map.importStyle)
                    }
                }
            }
            Column(Modifier.padding(start = 72.dp, end = 16.dp)) {
                StyleOptions(theme, themeLib)
            }
            SheetHeader(T.map.backgroundHeader)
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
                title = T.map.mapColours,
                subtitle = T.map.vectorAsItIs,
                icon = Icons.Filled.Map,
                onClick = { pick(app.maparound.data.Prefs.BACKGROUND_NONE) },
            )
            LayerRow(
                selected = offlineSelected && background == app.maparound.data.Prefs.BACKGROUND_SATELLITE,
                title = T.map.satelliteImagery,
                subtitle = T.map.satelliteUnder,
                icon = Icons.Filled.SatelliteAlt,
                onClick = { pick(app.maparound.data.Prefs.BACKGROUND_SATELLITE) },
            )
            rasterMaps.forEach { m ->
                LayerRow(
                    selected = offlineSelected && background == app.maparound.data.Prefs.BACKGROUND_RASTER + m.id,
                    title = m.name,
                    subtitle = T.map.rasterMap(m.projection),
                    icon = Icons.Filled.Image,
                    onClick = { pick(app.maparound.data.Prefs.BACKGROUND_RASTER + m.id) },
                    onRemove = { removeRaster(prefs, rasters, m) },
                )
            }
            TextButton(onClick = addRaster, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(T.map.addRasterMap)
            }
            RasterPreparingRow(rasters)
            Column(Modifier.padding(start = 24.dp, end = 16.dp)) {
                SwitchRow(
                    T.map.vectorOnTop,
                    T.map.vectorOnTopHint,
                    checked = vectorOnBackground,
                    enabled = background != app.maparound.data.Prefs.BACKGROUND_NONE,
                ) { prefs.setVectorOnBackground(it) }
            }
            SheetHeader(T.map.sectionOnline)
            // The map that suits where the map is (a country's own), until one is picked.
            LayerRow(
                selected = selectedId == TileSources.AUTO_ID,
                title = T.settings.automaticNow(TileSources.title(TileSources.automatic())),
                subtitle = T.map.srcAutomatic,
                icon = Icons.Filled.Public,
                onClick = { onSelect(TileSources.AUTO_ID) },
            )
            TileSources.builtIn.forEach { src ->
                LayerRow(
                    selected = selectedId == src.id,
                    title = TileSources.title(src),
                    subtitle = TileSources.description(src),
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
                Text(T.map.addCustomSource)
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = importSources) {
                    Icon(Icons.Filled.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(T.map.importList)
                }
                TextButton(
                    onClick = { platform.shareTextFile(SourceList.FILE_NAME, "application/json", SourceList.encode(custom), T.map.tileSourcesFileTitle) },
                    enabled = custom.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(T.map.exportList)
                }
            }
            }
        }
    }
    if (showAdd) AddSourceDialog(onDismiss = { showAdd = false }, onAdd = { showAdd = false; onAddCustom(it) })
}

private const val LAYERS_TAB_MAP = 0
private const val LAYERS_TAB_TERRAIN = 1
private const val LAYERS_TAB_OVERLAYS = 2

/** A tab of the layers sheet: its name, and how many of its layers are on (none: no badge). */
@Composable
private fun LayersTab(selected: Boolean, title: String, on: Int, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (on > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Text("$on")
                    }
                }
            }
        },
    )
}

/**
 * A layer that goes on and off: its picture (an icon on a tile in [tint]), name, what it shows,
 * and its switch. The whole row toggles it.
 */
@Composable
private fun LayerSwitch(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(tint.copy(alpha = if (enabled) 0.18f else 0.08f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked && enabled, onCheckedChange = onChange, enabled = enabled)
    }
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
            Text(MapThemes.title(theme), style = MaterialTheme.typography.bodyLarge)
            Text(MapThemes.about(theme), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) Tip(T.map.removeNamed(MapThemes.title(theme))) { IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = T.map.removeNamed(MapThemes.title(theme))) } }
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
    // In the app's language: the theme's own name in it if it has one, Topo's options' from the texts.
    fun nameOf(layer: StyleMenu.Layer): String =
        layer.names[I18n.language.code] ?: T.map.topoOption(layer.id)?.takeIf { MapThemes.hasTopoOptions(themeId) } ?: menu.name(layer)
    if (menu.styles.size > 1) {
        Text(T.map.variant, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        menu.styles.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { prefs.setStyleChoice(themeId, choice.copy(style = s.id)) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = s.id == style.id, onClick = null, modifier = Modifier.padding(end = 12.dp))
                Text(nameOf(s), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    val overlays = menu.overlays(style)
    if (overlays.isNotEmpty()) {
        Text(T.map.show, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        overlays.forEach { o ->
            SwitchRow(nameOf(o), null, checked = menu.isOn(o, choice)) { on ->
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
                    Tip(T.map.removeNamed(title)) { IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = T.map.removeNamed(title)) } }
                }
                RadioButton(selected = selected, onClick = null)
            }
        },
        colors = ListItemDefaults.colors(),
    )
}

@Composable
internal fun AddSourceDialog(onDismiss: () -> Unit, onAdd: (OnlineSource) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var maxZoom by remember { mutableStateOf("19") }
    val urlError = TileSources.validateTemplate(url.trim())
    val zoom = maxZoom.toIntOrNull()
    val valid = name.isNotBlank() && urlError == null && zoom != null && zoom in 1..22
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.map.customSource) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    T.map.customSourceHelp,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text(T.map.nameField) }, singleLine = true)
                OutlinedTextField(
                    url, { url = it },
                    label = { Text(T.map.urlTemplate) },
                    isError = url.length > 8 && urlError != null,
                    supportingText = { if (url.length > 8 && urlError != null) Text(urlError) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    maxZoom, { maxZoom = it.filter(Char::isDigit).take(2) },
                    label = { Text(T.map.maxZoom) },
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
            ) { Text(T.common.add) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.cancel) } },
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
    /** Folded away to one line, so the map shows ([MapDrawer]). */
    folded: Boolean,
    onFold: (Boolean) -> Unit,
) {
    MapDrawer(
        folded, onFold,
        summary = {
            // The profile, and the route's length, time and climb (or what to tap).
            val r = state.route
            Text(state.profile.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    state.waypoints.isEmpty() -> T.map.plannerTapStart
                    state.error != null -> state.error.orEmpty()
                    r != null -> listOfNotNull(
                        Fmt.distance(r.stats.distanceM.toFloat()),
                        r.durationS?.let { Fmt.duration(it) },
                        r.stats.ascentM?.let { "↑ ${Fmt.height(it)}" },
                    ).joinToString(" · ")
                    else -> T.map.plannerTapNext
                },
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Tip(T.common.start) { IconButton(onClick = onNavigate, enabled = r != null && !state.busy) {
                Icon(Icons.Filled.Navigation, contentDescription = T.common.start)
            } }
            Tip(T.map.closePlanner) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.map.closePlanner) } }
        },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Tip(T.map.closePlanner) { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = T.map.closePlanner) } }
            }
            val route = state.route
            // A saved route being edited: which one.
            state.editing?.let { e ->
                Text(T.map.editingRoute(e.name), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            when {
                state.waypoints.isEmpty() -> Text(
                    T.map.plannerTapStart,
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.waypoints.size == 1 -> Text(
                    T.map.plannerTapNext,
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.error != null -> Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                route != null -> {
                    StatsLine(route.stats, route.durationS)
                    Text(
                        when (route.source) {
                            RouteResult.Source.OFFLINE -> T.map.routedOffline
                            RouteResult.Source.ONLINE -> T.map.routedOnline
                            RouteResult.Source.SAVED -> T.map.routeAsSaved
                            RouteResult.Source.STRAIGHT ->
                                if (route.points.any { it.ele != null }) T.map.straightWithHeights
                                else T.map.straightNoHeights
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ElevationProfile(route.points, Modifier.fillMaxWidth().height(72.dp))
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tip(T.common.undo) { IconButton(onClick = { PlannerSession.undo() }, enabled = PlannerSession.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = T.common.undo)
                } }
                Tip(T.map.reverseDirection) { IconButton(onClick = { PlannerSession.reverse() }, enabled = state.waypoints.size >= 2) {
                    Icon(Icons.Filled.SwapVert, contentDescription = T.map.reverseDirection)
                } }
                Tip(T.map.clearAllPoints) { IconButton(onClick = { PlannerSession.clear() }, enabled = state.waypoints.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = T.map.clearAllPoints)
                } }
                Tip(T.map.shareRoute) { IconButton(onClick = onShare, enabled = route != null && !state.busy) {
                    Icon(Icons.Filled.Share, contentDescription = T.map.shareRoute)
                } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSave, enabled = route != null && !state.busy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(T.common.save)
                }
                Button(onClick = onNavigate, enabled = route != null && !state.busy, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Filled.Navigation, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(T.common.start)
                }
            }
        }
    }
}

/** [editing]: a saved route being edited: saved over it, or as a new one besides. */
@Composable
private fun SaveRouteDialog(suggested: String, editing: Boolean, onDismiss: () -> Unit, onSave: (name: String, asNew: Boolean) -> Unit) {
    var name by remember { mutableStateOf(suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) T.map.saveChanges else T.map.saveRoute) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(T.map.nameField) }, singleLine = true) },
        confirmButton = {
            Row {
                if (editing) TextButton(onClick = { onSave(name.trim().ifEmpty { suggested }, true) }) { Text(T.map.saveAsNew) }
                TextButton(onClick = { onSave(name.trim().ifEmpty { suggested }, false) }) { Text(T.common.save) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.cancel) } },
    )
}

/** How far around a point "What's here?" lists the map's places, on screen. */
private const val WHATS_HERE_RADIUS_DP = 140.0

/** The terrain download the map starts by itself (its label). */
private val AUTO_TERRAIN_LABEL: String get() = T.map.contoursHere

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

/** From which zoom the map offers the map made for where it looks, and how often it looks. */
private const val OFFER_ZOOM = 7
private const val OFFER_CHECK_MS = 1_500L

/** The app's regions, once they're read (the web app fetches them as it starts); null if they never are. */
private suspend fun regionsWhenRead(): MapRegions? {
    repeat(60) {
        withContext(Dispatchers.Default) { MapRegions.bundled }?.let { return it }
        delay(500)
    }
    return null
}

/** How often the grid looks whether the view moved. */
private const val GRID_CHECK_MS = 250L

/** Where the map starts the first time: the whole world. */
private val WORLD_START = MapPos(30.0, 10.0, 3)

/** On first open: how long a first location is waited for, and how close the map goes to it. */
private const val FIRST_FIX_WAIT_MS = 20_000L
private const val FIRST_FIX_ZOOM = 12

/** The data view shows Israel's localities from this zoom (the app's: MapLibre's 6), the countries below it. */
private const val LOCALITIES_ZOOM = 7.0

/** Narrower than this, the explorer's drawer goes over the map (a phone); else beside it. */
private val NARROW_SCREEN = 600.dp

/** How often the time zones' offsets are worked out again. */
private const val ZONES_REFRESH_MS = 15 * 60_000L

private fun Set<Int>.toggled(c: Int): Set<Int> = if (c in this) this - c else this + c
