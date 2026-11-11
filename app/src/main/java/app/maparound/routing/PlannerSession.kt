package app.maparound.routing

import app.maparound.AppGraph
import app.maparound.tracks.SavedTrack
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The route being planned on the map. Lives outside the UI so it survives tab switches;
 * recalculates shortly after every change.
 */
object PlannerSession {
    data class State(
        val active: Boolean = false,
        val profile: RouteProfile = RouteProfile.HIKE,
        val waypoints: List<GeoPoint> = emptyList(),
        val route: RouteResult? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val undo = ArrayList<List<GeoPoint>>()

    fun open() {
        _state.value = _state.value.copy(active = true)
    }

    fun close() {
        job?.cancel()
        undo.clear()
        _state.value = State(profile = _state.value.profile)
    }

    /** Opens the planner with a route someone shared (BRouter-Web / Google directions link). */
    fun load(points: List<GeoPoint>, profile: RouteProfile?) {
        job?.cancel()
        undo.clear()
        _state.value = State(active = true, profile = profile ?: _state.value.profile, waypoints = points)
        recompute()
    }

    /** The current route as a track (for saving or sharing), or null while there is none. */
    fun currentTrack(name: String): Track? {
        val s = _state.value
        val route = s.route ?: return null
        val kind = if (s.profile == RouteProfile.LINE) TrackKind.DRAWN else TrackKind.PLANNED
        return Track(name, kind, route.points, System.currentTimeMillis(), s.profile, s.waypoints)
    }

    fun setProfile(profile: RouteProfile) {
        if (profile == _state.value.profile) return
        _state.value = _state.value.copy(profile = profile)
        recompute()
    }

    fun add(p: GeoPoint) = edit(_state.value.waypoints + p)

    fun removeAt(index: Int) = edit(_state.value.waypoints.filterIndexed { i, _ -> i != index })

    fun reverse() = edit(_state.value.waypoints.reversed())

    fun clear() = edit(emptyList())

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        _state.value = _state.value.copy(waypoints = previous)
        recompute()
    }

    val canUndo: Boolean get() = undo.isNotEmpty()

    /** Saves the planned route to the library (and shows it on the map). */
    fun save(name: String): SavedTrack? = currentTrack(name)?.let { AppGraph.tracks.save(it) }

    private fun edit(waypoints: List<GeoPoint>) {
        undo += _state.value.waypoints
        if (undo.size > 50) undo.removeAt(0)
        _state.value = _state.value.copy(waypoints = waypoints)
        recompute()
    }

    private fun recompute() {
        job?.cancel()
        val s = _state.value
        if (s.waypoints.size < 2) {
            _state.value = s.copy(route = null, busy = false, error = null)
            return
        }
        _state.value = s.copy(busy = true, error = null)
        job = scope.launch {
            delay(200) // let quick successive taps settle
            try {
                val route = AppGraph.planner.plan(s.waypoints, s.profile)
                _state.value = _state.value.copy(route = route, busy = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutingException) {
                _state.value = _state.value.copy(route = null, busy = false, error = e.message)
            } catch (e: Exception) {
                _state.value = _state.value.copy(route = null, busy = false, error = "Routing failed: ${e.message}")
            }
        }
    }
}
