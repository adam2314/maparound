package app.maparound.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The shared screens' [DeviceSensors] on Android: LocationManager and SensorManager. */
class AndroidSensors(context: Context) : DeviceSensors {
    private val context = context.applicationContext

    override fun hasLocationPermission(): Boolean = context.hasLocationPermission()

    override fun locations(intervalMs: Long): Flow<GeoFix> = LocationSource.updates(context, intervalMs).map { it.toGeoFix() }

    override val hasCompass: Boolean get() = OrientationSource.isAvailable(context)
    override fun orientation(): Flow<Orientation> = OrientationSource.updates(context)

    override fun motionInfo(sensor: MotionSensor): SensorInfo? = MotionSource.info(
        context,
        when (sensor) {
            MotionSensor.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            MotionSensor.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        },
    )

    override fun motion(periodMs: Long): Flow<MotionSample> = MotionSource.updates(context, periodMs)

    override val hasBarometer: Boolean get() = PressureSource.isAvailable(context)
    override fun pressure(): Flow<Float> = PressureSource.updates(context)

    override fun declination(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Float =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altitudeM.toFloat(), timeMs).declination

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
}

fun Location.toGeoFix() = GeoFix(
    lat = latitude,
    lon = longitude,
    altitudeM = bestAltitude(),
    accuracyM = accuracy.takeIf { hasAccuracy() },
    speedMps = speed.takeIf { hasSpeed() },
    speedAccuracyMps = if (Build.VERSION.SDK_INT >= 26 && hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingDeg = bearing.takeIf { hasBearing() },
    timeMs = time,
    elapsedMs = elapsedRealtimeNanos / 1_000_000,
    satellite = provider == LocationManager.GPS_PROVIDER,
)
