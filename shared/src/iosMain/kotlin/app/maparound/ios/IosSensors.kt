@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.ios

import app.maparound.sensors.DeviceSensors
import app.maparound.sensors.GeoFix
import app.maparound.sensors.MotionSample
import app.maparound.sensors.MotionSensor
import app.maparound.sensors.Orientation
import app.maparound.sensors.SensorAccuracy
import app.maparound.sensors.SensorInfo
import app.maparound.sensors.Vec3
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreMotion.CMAltimeter
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSDate
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

/**
 * The phone's sensors on iOS: Core Location (position, compass) and Core Motion (motion,
 * barometer). Values use Android's conventions, which the shared screens were written for.
 */
class IosSensors : DeviceSensors {
    private val authManager = CLLocationManager()
    private val authDelegate = LocationDelegate(onAuthorization = { _permission.value = granted() })
    private val _permission = MutableStateFlow(false)

    /** Whether the app may use the location; follows changes made in Settings. */
    val permission: StateFlow<Boolean> = _permission.asStateFlow()

    /** The user hasn't been asked yet. */
    val canAsk: Boolean get() = authManager.authorizationStatus == kCLAuthorizationStatusNotDetermined

    /** True − magnetic north at the last compass reading with a position. */
    @Volatile
    private var lastDeclination: Float? = null

    init {
        authManager.delegate = authDelegate
        _permission.value = granted()
    }

    private fun granted(): Boolean = authManager.authorizationStatus.let {
        it == kCLAuthorizationStatusAuthorizedWhenInUse || it == kCLAuthorizationStatusAuthorizedAlways
    }

    /** Asks for the location (once); after a refusal, opens the app's page in Settings. */
    fun requestPermission() {
        if (canAsk) {
            authManager.requestWhenInUseAuthorization()
        } else {
            NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any?>(), null)
            }
        }
    }

    override fun hasLocationPermission(): Boolean = granted()

    override fun locations(intervalMs: Long): Flow<GeoFix> = callbackFlow {
        val manager = CLLocationManager()
        val delegate = LocationDelegate(onLocations = { list -> list.forEach { trySend(it.toGeoFix()) } })
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.startUpdatingLocation()
        awaitClose { stop(manager, delegate) { stopUpdatingLocation() } }
    }.flowOn(Dispatchers.Main)

    override val hasCompass: Boolean get() = CLLocationManager.headingAvailable()

    override fun orientation(): Flow<Orientation> = callbackFlow {
        val manager = CLLocationManager()
        val delegate = LocationDelegate(onHeading = { h ->
            // trueHeading is only known while the position is (the compass screen follows it).
            if (h.trueHeading >= 0) lastDeclination = normalize(h.trueHeading - h.magneticHeading).toFloat()
            trySend(Orientation(h.magneticHeading.toFloat(), 0f, 0f, accuracyOf(h.headingAccuracy)))
        })
        manager.delegate = delegate
        manager.headingFilter = 0.5
        manager.startUpdatingHeading()
        awaitClose { stop(manager, delegate) { stopUpdatingHeading() } }
    }.flowOn(Dispatchers.Main)

    override fun motionInfo(sensor: MotionSensor): SensorInfo? {
        val m = CMMotionManager()
        // iOS doesn't tell ranges or chips; a range of 0 is shown as unknown.
        return when (sensor) {
            MotionSensor.ACCELEROMETER -> SensorInfo("Accelerometer", "Apple", 0f, 0f, 100).takeIf { m.accelerometerAvailable }
            MotionSensor.GYROSCOPE -> SensorInfo("Gyroscope", "Apple", 0f, 0f, 100).takeIf { m.gyroAvailable }
        }
    }

    override fun motion(periodMs: Long): Flow<MotionSample> = callbackFlow {
        val m = CMMotionManager()
        if (!m.deviceMotionAvailable) {
            close()
            return@callbackFlow
        }
        m.deviceMotionUpdateInterval = periodMs / 1000.0
        m.startDeviceMotionUpdatesToQueue(NSOperationQueue.mainQueue) { motion, _ ->
            if (motion != null) {
                // Core Motion reports in g with the opposite sign to Android (−1 g on z lying face up).
                val gravity = motion.gravity.useContents { Vec3(-x.toFloat() * G, -y.toFloat() * G, -z.toFloat() * G) }
                val user = motion.userAcceleration.useContents { Vec3(-x.toFloat() * G, -y.toFloat() * G, -z.toFloat() * G) }
                val gyro = motion.rotationRate.useContents { Vec3(x.toFloat(), y.toFloat(), z.toFloat()) }
                trySend(MotionSample((motion.timestamp * 1e9).toLong(), gravity + user, user, gravity, gyro))
            }
        }
        awaitClose { m.stopDeviceMotionUpdates() }
    }

    override val hasBarometer: Boolean get() = CMAltimeter.isRelativeAltitudeAvailable()

    override fun pressure(): Flow<Float> = callbackFlow {
        val altimeter = CMAltimeter()
        altimeter.startRelativeAltitudeUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
            // kPa → hPa
            data?.pressure?.floatValue?.let { trySend(it * 10f) }
        }
        awaitClose { altimeter.stopRelativeAltitudeUpdates() }
    }

    /** iOS knows the declination only where the phone is (from the compass); elsewhere the same is used. */
    override fun declination(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Float = lastDeclination ?: 0f

    override fun elapsedMs(): Long = uptimeMs()

    private fun stop(manager: CLLocationManager, delegate: LocationDelegate, what: CLLocationManager.() -> Unit) {
        manager.what()
        manager.delegate = null
        delegate.stopped = true
    }

    private companion object {
        const val G = 9.80665f

        fun uptimeMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()

        fun normalize(deg: Double): Double = ((deg + 540.0) % 360.0) - 180.0

        fun accuracyOf(degrees: Double): Int = when {
            degrees < 0 -> SensorAccuracy.UNRELIABLE
            degrees <= 10 -> SensorAccuracy.HIGH
            degrees <= 25 -> SensorAccuracy.MEDIUM
            else -> SensorAccuracy.LOW
        }

        fun CLLocation.toGeoFix(): GeoFix {
            val (lat, lon) = coordinate.useContents { latitude to longitude }
            val ageMs = (NSDate().timeIntervalSince1970 - timestamp.timeIntervalSince1970) * 1000
            return GeoFix(
                lat = lat,
                lon = lon,
                altitudeM = altitude.takeIf { verticalAccuracy >= 0 },
                accuracyM = horizontalAccuracy.takeIf { it >= 0 }?.toFloat(),
                speedMps = speed.takeIf { it >= 0 }?.toFloat(),
                speedAccuracyMps = speedAccuracy.takeIf { it >= 0 }?.toFloat(),
                bearingDeg = course.takeIf { it >= 0 }?.toFloat(),
                timeMs = (timestamp.timeIntervalSince1970 * 1000).toLong(),
                elapsedMs = uptimeMs() - ageMs.toLong(),
                // iOS doesn't say where a fix came from; GPS fixes are the precise ones.
                satellite = horizontalAccuracy in 0.0..30.0,
            )
        }
    }
}

/** Core Location's callbacks as lambdas (a manager holds its delegate weakly: keep a reference). */
private class LocationDelegate(
    private val onLocations: (List<CLLocation>) -> Unit = {},
    private val onHeading: (CLHeading) -> Unit = {},
    private val onAuthorization: () -> Unit = {},
) : NSObject(), CLLocationManagerDelegateProtocol {
    var stopped = false

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        if (!stopped) onLocations(didUpdateLocations.filterIsInstance<CLLocation>())
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        if (!stopped) onHeading(didUpdateHeading)
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        onAuthorization()
    }
}
