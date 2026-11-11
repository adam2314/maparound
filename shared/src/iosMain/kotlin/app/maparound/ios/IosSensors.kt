package app.maparound.ios

import app.maparound.sensors.DeviceSensors
import app.maparound.sensors.GeoFix
import app.maparound.sensors.MotionSample
import app.maparound.sensors.MotionSensor
import app.maparound.sensors.Orientation
import app.maparound.sensors.SensorInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import platform.Foundation.NSProcessInfo

/** The phone's sensors on iOS. Core Location and Core Motion come in the next step. */
class IosSensors : DeviceSensors {
    override fun hasLocationPermission(): Boolean = false
    override fun locations(intervalMs: Long): Flow<GeoFix> = emptyFlow()
    override val hasCompass: Boolean = false
    override fun orientation(): Flow<Orientation> = emptyFlow()
    override fun motionInfo(sensor: MotionSensor): SensorInfo? = null
    override fun motion(periodMs: Long): Flow<MotionSample> = emptyFlow()
    override val hasBarometer: Boolean = false
    override fun pressure(): Flow<Float> = emptyFlow()
    override fun declination(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Float = 0f
    override fun elapsedMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000).toLong()
}
