package app.maparound.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class Orientation(
    val azimuth: Float,   // magnetic heading of the top of the screen, 0..360
    val pitch: Float,
    val roll: Float,
    val accuracy: Int,    // SensorManager.SENSOR_STATUS_*
)

/** Compass heading from the fused rotation-vector sensor (falls back to accelerometer + magnetometer). */
object OrientationSource {
    fun isAvailable(context: Context): Boolean {
        val sm = context.getSystemService(SensorManager::class.java) ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null ||
            (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null)
    }

    fun updates(context: Context): Flow<Orientation> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val rotationSensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (sm == null || (rotationSensor == null && (accel == null || magnet == null))) {
            close()
            return@callbackFlow
        }

        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val angles = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var haveGravity = false
        var haveMagnet = false
        var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var smoothSin = 0.0
        var smoothCos = 0.0
        var initialised = false

        fun publish() {
            val (x, y) = when (displayRotation(context)) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
            SensorManager.getOrientation(remapped, angles)
            val az = angles[0].toDouble()
            // Low-pass filter on the unit circle so 359° -> 1° doesn't swing through 180°.
            if (!initialised) {
                smoothSin = sin(az); smoothCos = cos(az); initialised = true
            } else {
                smoothSin += SMOOTHING * (sin(az) - smoothSin)
                smoothCos += SMOOTHING * (cos(az) - smoothCos)
            }
            val heading = ((Math.toDegrees(atan2(smoothSin, smoothCos)) + 360.0) % 360.0).toFloat()
            trySend(
                Orientation(
                    azimuth = heading,
                    pitch = Math.toDegrees(angles[1].toDouble()).toFloat(),
                    roll = Math.toDegrees(angles[2].toDouble()).toFloat(),
                    accuracy = accuracy,
                ),
            )
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        publish()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        lowPass(event.values, gravity, haveGravity)
                        haveGravity = true
                        if (rotationSensor == null && haveMagnet &&
                            SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)
                        ) {
                            publish()
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        accuracy = event.accuracy
                        lowPass(event.values, geomagnetic, haveMagnet)
                        haveMagnet = true
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, acc: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) accuracy = acc
            }
        }

        if (rotationSensor != null) {
            sm.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        }
        // Registered in both modes: its accuracy tells us whether the compass needs calibration.
        if (magnet != null) sm.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }

    private const val SMOOTHING = 0.15

    private fun lowPass(input: FloatArray, output: FloatArray, primed: Boolean) {
        for (i in 0 until 3) output[i] = if (primed) output[i] + 0.2f * (input[i] - output[i]) else input[i]
    }

    private fun displayRotation(context: Context): Int = try {
        if (Build.VERSION.SDK_INT >= 30) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        }
    } catch (e: Exception) {
        Surface.ROTATION_0
    }
}

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

/** Accelerometer, gyroscope and the fused gravity / linear-acceleration sensors, about 25 times a second. */
object MotionSource {
    data class Info(val name: String, val vendor: String, val maxRange: Float, val resolution: Float, val maxRateHz: Int?)

    fun info(context: Context, type: Int): Info? {
        val s = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(type) ?: return null
        return Info(s.name, s.vendor, s.maximumRange, s.resolution, s.minDelay.takeIf { it > 0 }?.let { 1_000_000 / it })
    }

    fun updates(context: Context, periodMs: Long = 40): Flow<MotionSample> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val types = listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_GRAVITY, Sensor.TYPE_GYROSCOPE)
        val sensors = types.mapNotNull { sm?.getDefaultSensor(it) }
        if (sm == null || sensors.isEmpty()) {
            close()
            return@callbackFlow
        }
        val hasGravity = sensors.any { it.type == Sensor.TYPE_GRAVITY }
        val hasLinear = sensors.any { it.type == Sensor.TYPE_LINEAR_ACCELERATION }
        var accel: Vec3? = null
        var linear: Vec3? = null
        var gravity: Vec3? = null
        var gyro: Vec3? = null
        var lastEmit = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v = Vec3.of(event.values)
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accel = v
                        // Phones without the fused sensors: split gravity off with a low-pass filter.
                        if (!hasGravity) gravity = gravity?.let { it + (v - it) * 0.1f } ?: v
                        if (!hasLinear) gravity?.let { linear = v - it }
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> linear = v
                    Sensor.TYPE_GRAVITY -> gravity = v
                    Sensor.TYPE_GYROSCOPE -> gyro = v
                }
                if (event.timestamp - lastEmit >= periodMs * 1_000_000) {
                    lastEmit = event.timestamp
                    trySend(MotionSample(event.timestamp, accel, linear, gravity, gyro))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.forEach { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        awaitClose { sm.unregisterListener(listener) }
    }.conflate()
}

/** Air pressure in hPa from the barometer. */
object PressureSource {
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PRESSURE) != null

    fun updates(context: Context): Flow<Float> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sm == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sm.unregisterListener(listener) }
    }
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
        val now = System.currentTimeMillis()
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
