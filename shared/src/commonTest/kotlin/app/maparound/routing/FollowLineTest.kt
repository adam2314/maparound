package app.maparound.routing

import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import kotlin.math.cos
import kotlin.test.Test

class FollowLineTest {
    private val metresPerDegree = 111_195.0

    /** A point [north] and [east] metres from 47°N 9°E. */
    private fun at(north: Double, east: Double) =
        GeoPoint(47.0 + north / metresPerDegree, 9.0 + east / (metresPerDegree * cos(toRadians(47.0))))

    @Test
    fun aTracksBendsAreItsTurns() {
        // 1 km north, then 1 km east: a right turn at the corner (index 20).
        val l = (0..20).map { at(it * 50.0, 0.0) } + (1..20).map { at(1000.0, it * 50.0) }
        assertEquals(listOf(Maneuver(20, Turn.RIGHT)), Guidance.shapeTurns(l))
    }

    @Test
    fun gpsJitterIsNoTurn() {
        // Straight north, a point every 5 m wandering ±4 m sideways, as a recorded track does.
        val jitter = listOf(0.0, 3.0, -2.0, 4.0, -4.0, 1.0, -3.0, 2.0)
        val l = (0..200).map { at(it * 5.0, jitter[it % jitter.size]) }
        assertEquals(emptyList<Maneuver>(), Guidance.shapeTurns(l))
    }

    @Test
    fun switchbacksAreSaidButNotTooClose() {
        // Up a slope in 60 m legs, turning back each time (about 146°): a sharp turn at each bend.
        val l = ArrayList<GeoPoint>()
        for (leg in 0 until 4) {
            for (k in 0..12) l += at(leg * 20.0 + k * 1.5, if (leg % 2 == 0) k * 5.0 else 60.0 - k * 5.0)
        }
        assertEquals(listOf(Turn.SHARP_LEFT, Turn.SHARP_RIGHT, Turn.SHARP_LEFT), Guidance.shapeTurns(l).map { it.turn })
    }
}
