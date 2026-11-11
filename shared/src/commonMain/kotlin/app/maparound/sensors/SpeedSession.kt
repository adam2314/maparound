package app.maparound.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ground speed and the running trip, kept while the app is open (survives tab switches). */
object SpeedSession {
    data class State(
        /** Latest ground speed in m/s, or null if unknown. */
        val speedMps: Double? = null,
        val speedAccuracyMps: Float? = null,
        /** Direction of travel, degrees true, when moving. */
        val courseDeg: Float? = null,
        /** When the latest GPS fix arrived (elapsed-realtime ms), to spot a lost fix. */
        val fixAtElapsedMs: Long = 0L,
        val distanceM: Double = 0.0,
        val movingMs: Long = 0L,
        val elapsedMs: Long = 0L,
        val maxMps: Double = 0.0,
        val averageMovingMps: Double? = null,
        /** (elapsed-realtime ms, m/s) for the last 10 minutes. */
        val history: List<Pair<Long, Float>> = emptyList(),
    )

    private val trip = TripStats()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun add(fix: TripStats.Fix, elapsedMs: Long, speedAccuracyMps: Float?, courseDeg: Float?) {
        val speed = trip.add(fix)
        val s = _state.value
        val history = if (speed == null) s.history else (s.history + (elapsedMs to speed.toFloat())).dropWhile { elapsedMs - it.first > HISTORY_MS }
        _state.value = State(
            speedMps = speed,
            speedAccuracyMps = speedAccuracyMps,
            courseDeg = courseDeg?.takeIf { (speed ?: 0.0) >= TripStats.MOVING_MPS },
            fixAtElapsedMs = elapsedMs,
            distanceM = trip.distanceM,
            movingMs = trip.movingMs,
            elapsedMs = trip.elapsedMs,
            maxMps = trip.maxMps,
            averageMovingMps = trip.averageMovingMps,
            history = history,
        )
    }

    fun reset() {
        trip.reset()
        _state.value = _state.value.copy(distanceM = 0.0, movingMs = 0L, elapsedMs = 0L, maxMps = 0.0, averageMovingMps = null, history = emptyList())
    }

    private const val HISTORY_MS = 10 * 60_000L
}
