package app.maparound.sensors

import androidx.compose.runtime.staticCompositionLocalOf
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

// The phone's sensors as the shared screens see them. Android: LocationManager and
// SensorManager; iOS: Core Location and Core Motion.

/** A position fix: what the screens use of android.location.Location / CLLocation. */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    /** Above mean sea level where the platform knows it, else above the ellipsoid. */
    val altitudeM: Double?,
    val accuracyM: Float?,
    val speedMps: Float?,
    val speedAccuracyMps: Float?,
    /** Direction of travel, degrees from true north. */
    val bearingDeg: Float?,
    /** Wall clock, ms since 1970. */
    val timeMs: Long,
    /** Monotonic clock, ms (see [DeviceSensors.elapsedMs]). */
    val elapsedMs: Long,
    /** From satellites rather than Wi-Fi or cell towers: only these carry a usable speed and altitude. */
    val satellite: Boolean,
)

/** Compass accuracy, the same values as Android's SensorManager.SENSOR_STATUS_*. */
object SensorAccuracy {
    const val UNRELIABLE = 0
    const val LOW = 1
    const val MEDIUM = 2
    const val HIGH = 3
}

data class Orientation(
    val azimuth: Float,   // magnetic heading of the top of the screen, 0..360
    val pitch: Float,
    val roll: Float,
    val accuracy: Int,    // SensorAccuracy
)

/** One snapshot of the motion sensors, in phone coordinates. */
data class MotionSample(
    val timeNs: Long,
    /** m/s², including gravity (what the accelerometer reads). */
    val accel: Vec3?,
    /** m/s², gravity removed. */
    val linear: Vec3?,
    /** m/s², pointing up (the reaction to gravity). */
    val gravity: Vec3?,
    /** rad/s, counter-clockwise positive about each axis. */
    val gyro: Vec3?,
)

enum class MotionSensor { ACCELEROMETER, GYROSCOPE }

data class SensorInfo(val name: String, val vendor: String, val maxRange: Float, val resolution: Float, val maxRateHz: Int?)

interface DeviceSensors {
    fun hasLocationPermission(): Boolean

    /** Position fixes (satellite fixes preferred); ends at once without permission. */
    fun locations(intervalMs: Long = 1000L): Flow<GeoFix>

    val hasCompass: Boolean
    fun orientation(): Flow<Orientation>

    fun motionInfo(sensor: MotionSensor): SensorInfo?

    /** Accelerometer, gyroscope, gravity and linear acceleration, about every [periodMs]. */
    fun motion(periodMs: Long = 40): Flow<MotionSample>

    val hasBarometer: Boolean

    /** Air pressure, hPa. */
    fun pressure(): Flow<Float>

    /** Magnetic declination (true minus magnetic north), degrees east positive. */
    fun declination(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Float

    /** Monotonic clock in ms, the one [GeoFix.elapsedMs] uses. */
    fun elapsedMs(): Long
}

/** The platform's sensors, provided at the root of the shared UI. */
val LocalDeviceSensors = staticCompositionLocalOf<DeviceSensors> { error("No DeviceSensors provided") }

/** Height from air pressure, the standard-atmosphere formula (as Android's SensorManager.getAltitude). */
object Barometric {
    fun altitude(seaLevelHPa: Float, hPa: Float): Float = 44330f * (1f - (hPa / seaLevelHPa).pow(1f / 5.255f))
}

/**
 * Pressure samples recorded while the app is open (one every few seconds, last 3 hours),
 * kept across screen switches for the trend chart.
 */
object PressureHistory {
    data class Sample(val timeMs: Long, val hPa: Float)

    private const val INTERVAL_MS = 5_000L
    private const val WINDOW_MS = 3 * 60 * 60 * 1000L

    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    fun add(hPa: Float) {
        val now = nowMillis()
        val list = _samples.value
        if (list.isNotEmpty() && now - list.last().timeMs < INTERVAL_MS) return
        _samples.value = (list + Sample(now, hPa)).dropWhile { now - it.timeMs > WINDOW_MS }
    }

    /** Pressure change in hPa per hour over the last [windowMs], or null if too little data. */
    fun trendPerHour(samples: List<Sample>, windowMs: Long = 30 * 60 * 1000L): Float? {
        if (samples.size < 2) return null
        val end = samples.last()
        val start = samples.firstOrNull { end.timeMs - it.timeMs <= windowMs } ?: return null
        val span = end.timeMs - start.timeMs
        if (span < 10 * 60 * 1000L) return null
        return (end.hPa - start.hPa) / (span / 3_600_000f)
    }
}
