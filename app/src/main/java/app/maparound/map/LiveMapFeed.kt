package app.maparound.map

import app.maparound.AppGraph
import app.maparound.party.PartySession
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import app.maparound.routing.RouteProfile
import app.maparound.tracks.TrackRecorder
import app.maparound.ui.partyMapMarkers
import app.maparound.ui.partyMarks
import app.maparound.ui.partyTrails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Keeps a map that isn't the phone's own (the car screen, a second screen) showing what the
 * phone's does: the same base map (the offline style with its options, an online source, the
 * background under it), the planned or followed route, the shown tracks, the recording and the
 * party. Its own camera and position are the caller's. Runs in [scope], on the main thread.
 */
class LiveMapFeed(
    private val map: AndroidMapSurface,
    private val scope: CoroutineScope,
    /** Something the caller shows changed (the route, the party): its status, say. */
    private val onChange: () -> Unit = {},
) {
    /** Satellite imagery instead of the phone's map (the car screen's own switch). */
    var satellite = false
        set(value) {
            field = value
            scope.launch { applyBaseLayer() }
        }

    fun start() {
        val prefs = AppGraph.prefs
        // Same base map as the phone: offline vector (topo, contours, hill shading, trails) or an online source.
        scope.launch {
            combine(
                listOf(
                    prefs.baseLayer, prefs.customSources, AppGraph.mapFiles.files, prefs.mapTheme, prefs.styleChoices, prefs.contours,
                    prefs.hillshade, AppGraph.terrain.state, prefs.trails, AppGraph.trails.state,
                    prefs.mapBackground, prefs.vectorOnBackground, AppGraph.rasters.maps, AppGraph.streaming.maps,
                    app.maparound.shared.stream.MapStream.generation,
                ),
            ) { it.toList() }.collect { applyBaseLayer() }
        }
        scope.launch {
            combine(PlannerSession.state, NavigationSession.state) { planner, nav -> planner to nav }.collect { (planner, nav) ->
                val line = when {
                    nav.route != null -> nav.route!!.points
                    !planner.active -> emptyList()
                    planner.route != null -> planner.route!!.points
                    planner.profile == RouteProfile.LINE -> planner.waypoints
                    else -> emptyList()
                }
                map.setPlannedRoute(line, if (planner.active) planner.waypoints else emptyList())
                onChange()
            }
        }
        scope.launch {
            combine(AppGraph.tracks.tracks, AppGraph.tracks.visible) { tracks, visible ->
                tracks.filter { it.id in visible }.associate { it.id to it.track.points }
            }.collect { map.setShownTracks(it) }
        }
        scope.launch { TrackRecorder.state.collect { map.setRecording(it.points) } }
        scope.launch {
            val clock = flow {
                while (true) {
                    emit(System.currentTimeMillis())
                    delay(30_000)
                }
            }
            combine(PartySession.parties, PartySession.state, clock, AppGraph.prefs.partyOnMap) { parties, party, now, shown ->
                val all = parties.ifEmpty { listOf(party) }
                Triple(partyMapMarkers(all, now, shown = shown), partyTrails(all, now, shown = shown), partyMarks(all, shown = shown))
            }.collect { (markers, trails, marks) ->
                map.setPartyMarkers(markers)
                map.setPartyTrails(trails)
                map.setPartyMarks(marks)
                onChange()
            }
        }
    }

    suspend fun applyBaseLayer() {
        val prefs = AppGraph.prefs
        val online = if (satellite) satelliteSource else TileSources.find(prefs.baseLayer.value, prefs.customSources.value)
        val theme = prefs.mapTheme.value
        val base = online?.let { BaseMap.Online(it) } ?: BaseMap.Offline(
            maps = AppGraph.mapFiles.files.value,
            theme = theme,
            categories = AppGraph.themes.categories(theme, prefs.styleChoices.value),
            contours = prefs.contours.value,
            hillshade = prefs.hillshade.value,
            terrainVersion = AppGraph.terrain.state.value.version,
            trails = prefs.trails.value,
            trailsVersion = AppGraph.trails.state.value.version,
            // Same background as the phone (satellite / raster map under the vector map).
            background = prefs.mapBackground.value,
            vectorOnTop = prefs.vectorOnBackground.value,
            rasterMaps = AppGraph.rasters.maps.value,
            streamed = AppGraph.streaming.maps.value,
        )
        map.setBaseMap(base)
        val localTrails = online == null && prefs.trails.value && MapThemes.drawsTerrain(theme) && AppGraph.trails.state.value.cells > 0
        map.setTrailOverlay(prefs.trails.value && !localTrails)
    }
}
