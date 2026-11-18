package app.maparound.routing

import app.maparound.sensors.GeoFix
import app.maparound.shared.util.nowMillis
import app.maparound.shared.util.toRadians
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.cos
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every instruction is announced (for a watch's turn alerts), with the voice on or off. */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationAnnouncementsTest {
    private val metresPerDegree = 111_195.0

    private fun at(north: Double, east: Double) =
        GeoPoint(47.0 + north / metresPerDegree, 9.0 + east / (metresPerDegree * cos(toRadians(47.0))))

    /** 1 km north, then 500 m east; a point every 50 m, the corner at index 20. */
    private val points = (0..20).map { at(it * 50.0, 0.0) } + (1..10).map { at(1000.0, it * 50.0) }
    private val route = RouteResult(points, TrackStats.of(points), 900, RouteResult.Source.OFFLINE, listOf(Maneuver(20, Turn.RIGHT)))

    private val spoken = mutableListOf<String>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        NavigationSession.speaker = object : NavigationSession.Speaker {
            override fun speak(text: String) {
                spoken += text
            }

            override fun stop() = Unit
        }
    }

    @AfterTest fun tearDown() {
        NavigationSession.stop()
        NavigationSession.speaker = null
        NavigationSession.voiceOn = { true }
        Dispatchers.resetMain()
    }

    /** Walks north along the route to the corner, a fix every 10 m at 1.4 m/s. */
    private fun walkToTheCorner() {
        val start = nowMillis()
        for (i in 0..98) {
            val p = at(i * 10.0, 0.0)
            NavigationSession.onFix(
                GeoFix(
                    lat = p.lat, lon = p.lon, altitudeM = null, accuracyM = 5f, speedMps = 1.4f, speedAccuracyMps = null,
                    bearingDeg = 0f, timeMs = start + i * 7_000L, elapsedMs = i * 7_000L, satellite = true,
                ),
            )
        }
    }

    @Test fun announcedWithTheVoiceOff() = runTest {
        NavigationSession.voiceOn = { false }
        val heard = mutableListOf<String>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) { NavigationSession.announcements.collect { heard += it } }
        NavigationSession.start(route, listOf(points.first(), points.last()), RouteProfile.HIKE)
        walkToTheCorner()
        listening.cancel()
        // The turn, ahead and then now; nothing said aloud.
        assertTrue(heard.size >= 2, heard.toString())
        assertTrue(heard.any { "right" in it.lowercase() }, heard.toString())
        assertEquals(emptyList(), spoken)
    }

    @Test fun announcedAndSpokenWithTheVoiceOn() = runTest {
        val heard = mutableListOf<String>()
        val listening = launch(UnconfinedTestDispatcher(testScheduler)) { NavigationSession.announcements.collect { heard += it } }
        NavigationSession.start(route, listOf(points.first(), points.last()), RouteProfile.HIKE)
        walkToTheCorner()
        listening.cancel()
        assertTrue(heard.isNotEmpty())
        assertEquals(heard, spoken)
    }
}
