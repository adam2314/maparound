package app.maparound.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContoursTest {

    /** A cone 1000 m high at the grid centre, dropping 10 m per cell. */
    private fun cone(n: Int): FloatArray {
        val c = (n - 1) / 2f
        return FloatArray(n * n) { k ->
            val r = k / n
            val col = k % n
            1000f - 10f * hypot(col - c, r - c)
        }
    }

    @Test
    fun coneGivesOneClosedRingPerLevelAtTheRightRadius() {
        val n = 121
        val lines = Contours.trace(cone(n), n, n, interval = 100)
        // Levels 500..1000 fit fully inside the grid (radius <= 50 cells); lower ones are clipped.
        for (level in listOf(500, 600, 700, 800, 900)) {
            val rings = lines.filter { it.level == level }
            assertEquals("one line at $level m", 1, rings.size)
            val ring = rings.single()
            assertTrue("$level m ring is closed", ring.closed)
            val expectedRadius = (1000 - level) / 10f
            for (p in 0 until ring.pointCount) {
                val r = hypot(ring.xy[2 * p] - 60f, ring.xy[2 * p + 1] - 60f)
                assertEquals("point on $level m ring", expectedRadius, r, 0.6f)
            }
        }
    }

    @Test
    fun clippedLevelsAreOpenLinesNotFragments() {
        val n = 121
        val lines = Contours.trace(cone(n), n, n, interval = 100)
        // 300 m is at radius 70: it is cut by the grid border into 4 arcs (one per corner).
        val arcs = lines.filter { it.level == 300 }
        assertEquals(4, arcs.size)
        assertTrue(arcs.none { it.closed })
        assertTrue("arcs are long, not per-cell fragments", arcs.all { it.pointCount > 20 })
    }

    @Test
    fun voidsAreSkippedAndFlatGridHasNoLines() {
        val flat = FloatArray(16 * 16) { 120f }
        assertTrue(Contours.trace(flat, 16, 16, 20).isEmpty())
        val holes = cone(41).also { g -> for (k in g.indices step 7) g[k] = Float.NaN }
        Contours.trace(holes, 41, 41, 50) // must not throw
    }

    @Test
    fun hgtNamesRoundTrip() {
        assertEquals(46 to 7, DemIndex.parseName("N46E007.hgt"))
        assertEquals(-5 to -78, DemIndex.parseName("S05W078.hgt"))
        assertEquals("S05W078", DemIndex.nameFor(-5, -78))
        assertEquals("N00E000", DemIndex.nameFor(0, 0))
    }
}
