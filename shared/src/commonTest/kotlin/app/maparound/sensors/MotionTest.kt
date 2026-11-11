package app.maparound.sensors

import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

class MotionTest {
    private fun rad(d: Double) = toRadians(d)

    /** "Up" seen by a phone lying flat (top forward) in a vehicle pitched/rolled by the given angles. */
    private fun flatUp(pitch: Double, roll: Double): Vec3 {
        // Body axes in world: start level (x right, y forward, z up), pitch about x, then roll about y.
        val p = rad(pitch)
        val r = rad(roll)
        // World up expressed in body coordinates.
        val x = -sin(r) * cos(p)
        val y = sin(p)
        val z = cos(r) * cos(p)
        return Vec3(x.toFloat(), y.toFloat(), z.toFloat()) * 9.81f
    }

    @Test
    fun flatPhonePitchAndRoll() {
        val level = LevelFrame.default(Vec3(0f, 0f, 9.81f))
        assertEquals(0f, level.tilt(Vec3(0f, 0f, 9.81f)).totalDeg, 0.01f)
        val t = level.tilt(flatUp(10.0, 0.0))
        assertEquals("nose up", 10f, t.pitchDeg, 0.01f)
        assertEquals(0f, t.rollDeg, 0.01f)
        val r = level.tilt(flatUp(0.0, 20.0))
        assertEquals("right side down", 20f, r.rollDeg, 0.01f)
        assertEquals(0f, r.pitchDeg, 0.01f)
        assertEquals(20f, r.totalDeg, 0.01f)
    }

    @Test
    fun uprightPhoneInAHolder() {
        // Portrait, screen facing the driver: up is the phone's +y, forward its back (-z).
        val level = LevelFrame.default(Vec3(0.1f, 9.8f, 0.3f))
        assertEquals(-1f, level.forward.z, 1e-6f)
        assertEquals(1f, level.right.x, 1e-6f)
        assertEquals(0f, level.right.y + level.right.z + level.forward.x + level.forward.y, 1e-6f)
        // Nose up 15°: the phone tips back, so up gains a component towards the back (-z).
        val up = Vec3(0f, cos(rad(15.0)).toFloat(), -sin(rad(15.0)).toFloat())
        assertEquals(15f, level.tilt(up).pitchDeg, 0.01f)
        // Right side down 25°: up gains a component towards the phone's left (-x).
        val rolled = Vec3(-sin(rad(25.0)).toFloat(), cos(rad(25.0)).toFloat(), 0f)
        assertEquals(25f, level.tilt(rolled).rollDeg, 0.01f)
    }

    @Test
    fun setAsLevelCancelsTheMountAngle() {
        // Holder tilted back 30° on a level road: reads as level after "set as level".
        val mount = Vec3(0f, cos(rad(30.0)).toFloat(), sin(rad(30.0)).toFloat()) * 9.81f
        val frame = LevelFrame(mount)
        assertEquals(0f, frame.tilt(mount).totalDeg, 0.01f)
        assertEquals(0f, frame.tilt(mount).pitchDeg, 0.01f)
    }

    @Test
    fun accelerationAndTurnRate() {
        val level = LevelFrame.default(Vec3(0f, 0f, 9.81f)) // flat, top forward
        val (fwd, right) = level.horizontal(Vec3(1f, 2f, 0f))
        assertEquals(2f, fwd, 1e-6f)
        assertEquals(1f, right, 1e-6f)
        // Counter-clockwise rotation about up (z) = turning left = positive.
        assertEquals(0.5f, level.yawRate(Vec3(0f, 0f, 0.5f)), 1e-6f)
        assertEquals(Vec3(1f, 2f, 3f), Vec3.parse(Vec3(1f, 2f, 3f).toString()))
    }

    @Test
    fun tripStatsFromGpsSpeed() {
        val trip = TripStats()
        var lon = 35.0
        val metresPerDegLon = 111_320 * cos(rad(31.0))
        // 60 s at 10 m/s eastwards, one fix a second.
        for (s in 0..60) {
            trip.add(TripStats.Fix(s * 1000L, 31.0, lon, 10.0, 5.0))
            lon += 10 / metresPerDegLon
        }
        // 30 s standing (GPS jitter: 0.2 m/s, position wobbling a metre).
        for (s in 61..90) trip.add(TripStats.Fix(s * 1000L, 31.0 + (s % 2) * 1e-5, lon, 0.2, 5.0))
        // A glitch with poor accuracy is ignored.
        trip.add(TripStats.Fix(91_000L, 31.5, 35.5, 90.0, 400.0))
        assertEquals(600.0, trip.distanceM, 3.0)
        assertEquals(60_000L, trip.movingMs)
        assertEquals(10.0, trip.maxMps, 1e-9)
        assertEquals(10.0, trip.averageMovingMps!!, 0.1)
        assertEquals(90_000L, trip.elapsedMs)
        trip.reset()
        assertNull(trip.averageMovingMps)
        assertEquals(0.0, trip.distanceM, 0.0)
    }

    @Test
    fun speedFromPositionsWhenTheGpsGivesNone() {
        val trip = TripStats()
        val metresPerDegLat = 110_860.0
        var speed: Double? = null
        for (s in 0..20) speed = trip.add(TripStats.Fix(s * 2000L, 31.0 + s * 8 / metresPerDegLat, 35.0, null, 10.0))
        assertEquals("8 m per 2 s", 4.0, speed!!, 0.05)
        assertEquals(160.0, trip.distanceM, 1.0)
        assertEquals(14.4, trip.maxMps * SpeedUnit.KMH.perMps, 0.2)
    }
}
