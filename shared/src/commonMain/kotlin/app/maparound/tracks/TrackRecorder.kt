package app.maparound.tracks

import app.maparound.routing.GeoPoint
import app.maparound.routing.TrackStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live state of the GPS recorder, observed by the map and the Routes screen. The platform's
 * recorder (Android: RecordingService) feeds it with [begin], [append], [accuracy] and [end].
 */
object TrackRecorder {
    data class State(
        val recording: Boolean = false,
        val points: List<GeoPoint> = emptyList(),
        val startedAt: Long? = null,
        val stats: TrackStats = TrackStats.EMPTY,
        /** Latest fix accuracy in metres, for the "waiting for GPS" hint. */
        val accuracyM: Float? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun begin(startedAt: Long, restored: List<GeoPoint>) {
        _state.value = State(recording = true, points = restored, startedAt = startedAt, stats = TrackStats.of(restored))
    }

    fun append(p: GeoPoint, accuracy: Float?) {
        val s = _state.value
        val pts = s.points + p
        // Incremental stats would be cheaper, but a few thousand points recompute in well under 1 ms.
        _state.value = s.copy(points = pts, stats = TrackStats.of(pts), accuracyM = accuracy)
    }

    fun accuracy(accuracy: Float?) {
        _state.value = _state.value.copy(accuracyM = accuracy)
    }

    fun end(): State {
        val s = _state.value
        _state.value = State()
        return s
    }
}

