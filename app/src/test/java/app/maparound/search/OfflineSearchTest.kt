package app.maparound.search

import app.maparound.routing.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Searching a real mapsforge map. Runs only with MAP_FILE=<path to europe__liechtenstein.map>. */
class OfflineSearchTest {
    @Test
    fun findsNamesAroundTheMap() {
        val f = System.getenv("MAP_FILE")?.let(::File)
        assumeTrue(f != null && f.isFile)
        val vaduz = GeoPoint(47.141, 9.521)
        for (q in listOf("Vaduz", "rhein", "Malbun", "Hotel")) {
            val t0 = System.nanoTime()
            val r = SearchEngine.offline(q, vaduz, 25.0, listOf(f!!))
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("'$q': ${r.size} results in $ms ms: " + r.take(5).joinToString { "${it.name} [${it.detail}] s${it.score}" })
            assertTrue("found $q", r.isNotEmpty())
        }
    }
}
