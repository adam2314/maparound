package app.maparound.routing

import app.maparound.i18n.T
import app.maparound.sensors.GeoFix
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
 * Turn-by-turn navigation along a planned route: one at a time, shared by the phone screen, the
 * car screen and the background service that keeps it going with the screen off. The platform
 * feeds it position fixes ([onFix]; the same fix from several listeners counts once) and gives
 * it a voice ([speaker]).
 */
object NavigationSession {
    data class State(
        val active: Boolean = false,
        val profile: RouteProfile = RouteProfile.CAR,
        val route: RouteResult? = null,
        /** Everything announced along [route], the arrival last. */
        val maneuvers: List<Maneuver> = emptyList(),
        /** Null until the first fix. */
        val progress: Navigator.Progress? = null,
        /** A new route from here is being calculated (we left the route). */
        val rerouting: Boolean = false,
        val error: String? = null,
    )

    /** Says instructions aloud (Android: TextToSpeech). */
    interface Speaker {
        fun speak(text: String)

        /** Stops talking and lets the voice go; the next [speak] starts it again. */
        fun stop()
    }

    var speaker: Speaker? = null

    /** The user's voice setting; set by the platform. */
    var voiceOn: () -> Boolean = { true }

    /** Calculates new routes when we leave the route; by default the route planner's router. */
    var router: suspend (List<GeoPoint>, RouteProfile) -> RouteResult = { points, profile -> PlannerSession.router(points, profile) }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** A place the route still has to go to, with its stop number (null = the destination). */
    private class Target(val point: GeoPoint, val number: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var navigator: Navigator? = null
    private var targets: List<Target> = emptyList()
    private var routeStart: GeoPoint? = null
    private var lastFix: GeoFix? = null
    private var rerouteJob: Job? = null
    private var endJob: Job? = null
    private var nextRerouteAtMs = 0L
    private var offAnnounced = false
    private var run = 0

    /** Starts guidance along [route], planned through [waypoints] (the first is its start). */
    fun start(route: RouteResult, waypoints: List<GeoPoint>, profile: RouteProfile) {
        if (waypoints.size < 2 || route.points.size < 2) return
        cancelJobs()
        run++
        lastFix = null
        nextRerouteAtMs = 0L
        offAnnounced = false
        routeStart = waypoints.first()
        targets = waypoints.drop(1).mapIndexed { i, p -> Target(p, if (i == waypoints.size - 2) null else i + 1) }
        install(route, waypoints.first(), profile, fromHere = false)
    }

    fun stop() {
        if (!_state.value.active && navigator == null) return
        cancelJobs()
        run++
        navigator = null
        targets = emptyList()
        lastFix = null
        _state.value = State(profile = _state.value.profile)
        speaker?.stop()
    }

    /** The voice setting changed: when it went off, stop talking at once. */
    fun voiceChanged(on: Boolean) {
        if (!on) speaker?.stop()
    }

    fun onFix(fix: GeoFix) {
        val nav = navigator ?: return
        val last = lastFix
        if (last != null && fix.timeMs <= last.timeMs) return // the same fix from another listener
        if (nowMillis() - fix.timeMs > STALE_MS) return // a cached "last known" position
        lastFix = fix
        apply(nav, fix)
    }

    private fun apply(nav: Navigator, fix: GeoFix) {
        val p = nav.update(fix)
        _state.value = _state.value.copy(progress = p, error = if (p.offRoute) _state.value.error else null)
        p.speech?.let(::say)
        if (!p.offRoute) offAnnounced = false
        if (p.offRoute && !p.arrived) reroute(fix, p)
        if (p.arrived && endJob == null) {
            val id = run
            endJob = scope.launch {
                delay(END_AFTER_ARRIVAL_MS)
                if (run == id) stop()
            }
        }
    }

    /** A new route from where we are to the stops still ahead (and first to the route's start if we never got there). */
    private fun reroute(fix: GeoFix, p: Navigator.Progress) {
        if (rerouteJob?.isActive == true || fix.elapsedMs < nextRerouteAtMs) return
        var remaining = targets.drop(p.stopsPassed)
        val start = routeStart
        if (!p.joined && start != null && remaining.firstOrNull()?.number != 0) remaining = listOf(Target(start, 0)) + remaining
        if (remaining.isEmpty()) return
        if (!offAnnounced) {
            offAnnounced = true
            say(T.nav.recalculatingRoute)
        }
        val here = GeoPoint(fix.lat, fix.lon)
        val profile = _state.value.profile
        nextRerouteAtMs = fix.elapsedMs + REROUTE_GAP_MS
        _state.value = _state.value.copy(rerouting = true)
        val id = run
        rerouteJob = scope.launch {
            try {
                val route = router(listOf(here) + remaining.map { it.point }, profile)
                if (run != id) return@launch
                targets = remaining
                install(route, here, profile, fromHere = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (run != id) return@launch
                nextRerouteAtMs = (lastFix?.elapsedMs ?: fix.elapsedMs) + RETRY_AFTER_FAILURE_MS
                _state.value = _state.value.copy(rerouting = false, error = T.nav.couldNotRecalculate(e.message))
            }
        }
    }

    private fun install(route: RouteResult, from: GeoPoint, profile: RouteProfile, fromHere: Boolean) {
        val nav = Navigator(
            route, profile,
            waypoints = listOf(from) + targets.map { it.point },
            stopNumbers = targets.dropLast(1).map { it.number ?: 0 },
            fromHere = fromHere,
        )
        navigator = nav
        _state.value = State(
            active = true, profile = profile, route = route, maneuvers = nav.maneuvers,
            progress = _state.value.progress.takeIf { fromHere },
        )
        // Straight away from the last fix, so the new route is announced without waiting for GPS.
        if (fromHere) lastFix?.let { apply(nav, it) }
    }

    private fun say(text: String) {
        if (voiceOn()) speaker?.speak(text)
    }

    private fun cancelJobs() {
        rerouteJob?.cancel()
        rerouteJob = null
        endJob?.cancel()
        endJob = null
    }

    private const val STALE_MS = 60_000L
    private const val REROUTE_GAP_MS = 15_000L
    private const val RETRY_AFTER_FAILURE_MS = 30_000L
    private const val END_AFTER_ARRIVAL_MS = 120_000L
}
