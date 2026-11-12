package app.maparound.routing

import app.maparound.tracks.SavedTrack
import app.maparound.tracks.Track
import app.maparound.tracks.TrackKind
import app.maparound.tracks.TrackRepository
import app.maparound.shared.util.nowMillis
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
        /** While a stop is dragged: the line to draw instead of the route (straight to the stop). */
        val preview: List<GeoPoint>? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Calculates a route; set by the platform at start-up (Android: offline BRouter, else online). */
    var router: suspend (List<GeoPoint>, RouteProfile) -> RouteResult = { _, _ -> throw RoutingException("No router on this device") }

    /** Where saved routes go; set by the platform at start-up. */
    var library: TrackRepository? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val undo = ArrayList<List<GeoPoint>>()

    /** The stop being dragged, if any, and what it needs (see [grab]). */
    private class Drag(
        val grab: RouteEdit.Grab,
        val finger: GeoPoint,
        val stops: List<GeoPoint>,
        val line: List<GeoPoint>,
        val stopIndices: List<Int>,
        val metresPerDp: Double,
    )
    private var drag: Drag? = null

    fun open() {
        _state.value = _state.value.copy(active = true)
    }

    fun close() {
        job?.cancel()
        undo.clear()
        drag = null
        _state.value = State(profile = _state.value.profile)
    }

    /** Opens the planner with a route someone shared (BRouter-Web / Google directions link). */
    fun load(points: List<GeoPoint>, profile: RouteProfile?) {
        job?.cancel()
        undo.clear()
        drag = null
        _state.value = State(active = true, profile = profile ?: _state.value.profile, waypoints = points)
        recompute()
    }

    /** The current route as a track (for saving or sharing), or null while there is none. */
    fun currentTrack(name: String): Track? {
        val s = _state.value
        val route = s.route ?: return null
        val kind = if (s.profile == RouteProfile.LINE) TrackKind.DRAWN else TrackKind.PLANNED
        return Track(name, kind, route.points, nowMillis(), s.profile, s.waypoints)
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

    /**
     * A long press at [p]: picks up the stop there, or a new stop on the route line, to move with
     * [drag] and put down with [drop]. False when there is nothing there to pick up.
     * [metresPerDp] is the map's scale, for how near the finger has to be.
     */
    fun grab(p: GeoPoint, metresPerDp: Double): Boolean {
        val s = _state.value
        if (!s.active) return false
        // What's drawn: the route, or straight lines in Line mode before it's calculated.
        val drawn = s.route?.points ?: if (s.profile == RouteProfile.LINE) s.waypoints else emptyList()
        val grab = RouteEdit.grab(p, s.waypoints, drawn, metresPerDp) ?: return false
        // Without a route (one stop, or routing failed) the preview joins the stops directly.
        val line = if (drawn.size >= 2) drawn else s.waypoints
        val indices = if (drawn.size >= 2) Guidance.stopIndices(drawn, s.waypoints) else s.waypoints.indices.toList()
        drag = Drag(grab, p, s.waypoints, line, indices, metresPerDp)
        show(grab.at)
        return true
    }

    /** The finger holding a picked-up stop moved to [p]. */
    fun drag(p: GeoPoint) {
        val d = drag ?: return
        show(follow(d, p))
    }

    /** The finger was lifted at [p]: the stop stays there and the route is calculated again. */
    fun drop(p: GeoPoint) {
        val d = drag ?: return
        drag = null
        val at = follow(d, p)
        val moved = Geo.distance(at, d.grab.at) > MOVE_SLOP_DP * d.metresPerDp
        if (d.grab.inserted || moved) {
            edit(RouteEdit.place(d.stops, d.grab, at), before = d.stops)
        } else {
            _state.value = _state.value.copy(waypoints = d.stops, preview = null) // just a long press on a stop
        }
    }

    /** Where the stop is with the finger at [p]: it keeps the offset it had from the finger when picked up. */
    private fun follow(d: Drag, p: GeoPoint) =
        GeoPoint(d.grab.at.lat + (p.lat - d.finger.lat), d.grab.at.lon + (p.lon - d.finger.lon))

    private fun show(at: GeoPoint) {
        val d = drag ?: return
        _state.value = _state.value.copy(
            waypoints = RouteEdit.place(d.stops, d.grab, at),
            preview = RouteEdit.preview(d.line, d.stopIndices, d.grab, at),
        )
    }

    fun undo() {
        val previous = undo.removeLastOrNull() ?: return
        drag = null
        _state.value = _state.value.copy(waypoints = previous, preview = null)
        recompute()
    }

    val canUndo: Boolean get() = undo.isNotEmpty()

    /** Saves the planned route to the library (and shows it on the map). */
    fun save(name: String): SavedTrack? = currentTrack(name)?.let { library?.save(it) }

    private fun edit(waypoints: List<GeoPoint>, before: List<GeoPoint> = _state.value.waypoints) {
        drag = null
        undo += before
        if (undo.size > 50) undo.removeAt(0)
        _state.value = _state.value.copy(waypoints = waypoints, preview = null)
        recompute()
    }

    /** A stop dragged less than this (in dp) stays where it was. */
    private const val MOVE_SLOP_DP = 4.0

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
                val route = router(s.waypoints, s.profile)
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
