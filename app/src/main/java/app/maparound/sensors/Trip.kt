package app.maparound.sensors

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint

// Plain Kotlin: ground speed and trip statistics from GPS fixes. Unit-tested.

enum class SpeedUnit(val label: String, val perMps: Double) {
    KMH("km/h", 3.6),
    MPH("mph", 2.2369363),
    KNOTS("kn", 1.9438445),
    MS("m/s", 1.0),
}

/**
 * Distance, moving time, max and average speed from a stream of fixes. Fixes worse than
 * [MAX_ACCURACY_M] are shown but not counted; gaps over [MAX_GAP_MS] are not counted as moving.
 */
class TripStats {
    data class Fix(
        val timeMs: Long,
        val lat: Double,
        val lon: Double,
        /** Speed reported by the GPS (Doppler, much steadier than position differences), if any. */
        val speedMps: Double?,
        val accuracyM: Double?,
    )

    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var maxMps = 0.0
        private set
    var startMs: Long? = null
        private set
    var lastMs: Long? = null
        private set
    private var prev: Fix? = null

    val elapsedMs: Long get() = (lastMs ?: 0L) - (startMs ?: 0L)
    val averageMovingMps: Double? get() = if (movingMs < 5_000) null else distanceM / (movingMs / 1000.0)

    /** Adds a fix; returns its ground speed (m/s), or null if unknown. */
    fun add(f: Fix): Double? {
        val p = prev
        if (p != null && f.timeMs <= p.timeMs) return null
        val dtS = p?.let { (f.timeMs - it.timeMs) / 1000.0 }
        val d = p?.let { Geo.distance(GeoPoint(it.lat, it.lon), GeoPoint(f.lat, f.lon)) }
        val speed = f.speedMps ?: if (dtS != null && dtS in 0.5..10.0) d!! / dtS else null
        if (f.accuracyM != null && f.accuracyM > MAX_ACCURACY_M) return speed
        if (startMs == null) startMs = f.timeMs
        lastMs = f.timeMs
        if (p != null && dtS != null && f.timeMs - p.timeMs <= MAX_GAP_MS && (speed ?: 0.0) >= MOVING_MPS) {
            movingMs += f.timeMs - p.timeMs
            distanceM += d!!
        }
        if (speed != null && speed > maxMps && speed < MAX_PLAUSIBLE_MPS) maxMps = speed
        prev = f
        return speed
    }

    fun reset() {
        distanceM = 0.0
        movingMs = 0L
        maxMps = 0.0
        startMs = null
        lastMs = null
        prev = null
    }

    companion object {
        /** Slower than this (1.8 km/h) counts as standing still: GPS speed jitters at rest. */
        const val MOVING_MPS = 0.5
        const val MAX_ACCURACY_M = 50.0
        const val MAX_GAP_MS = 30_000L
        private const val MAX_PLAUSIBLE_MPS = 150.0 // 540 km/h; anything above is a glitch
    }
}
