package app.maparound.routing

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** BRouter runs on Android only (until phase 6), so this test stays with the app. */
class OfflineRouterTest {

    /** Runs only when BRouter data is available: set BROUTER_SEGMENTS to a folder with E35_N30.rd5. */
    @Test
    fun offlineRouteInTheJerusalemHills() {
        val segments = System.getenv("BROUTER_SEGMENTS")?.let(::File)
        assumeTrue(segments != null && File(segments, "E35_N30.rd5").isFile)
        val router = OfflineRouter(segments!!, File("src/main/assets/brouter/profiles2"))
        val start = GeoPoint(31.7681, 35.1584) // Ein Karem
        val end = GeoPoint(31.7731, 35.1251)   // Sataf
        for (profile in listOf(RouteProfile.HIKE, RouteProfile.BIKE, RouteProfile.CAR)) {
            val r = router.route(listOf(start, end), profile)
            println("$profile: ${r.points.size} pts, ${r.stats.distanceM.toInt()} m, +${r.stats.ascentM?.toInt()} m, ${r.durationS} s")
            assertTrue("$profile route is longer than the straight line", r.stats.distanceM > Geo.distance(start, end))
            assertTrue("$profile route has elevation", r.points.any { it.ele != null })
            assertNotNull(r.durationS)
        }
    }
}
