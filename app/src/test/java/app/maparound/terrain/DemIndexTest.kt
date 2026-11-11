package app.maparound.terrain

import org.junit.Assert.assertEquals
import org.junit.Test

class DemIndexTest {
    @Test
    fun hgtNamesRoundTrip() {
        assertEquals(46 to 7, DemIndex.parseName("N46E007.hgt"))
        assertEquals(-5 to -78, DemIndex.parseName("S05W078.hgt"))
        assertEquals("S05W078", DemIndex.nameFor(-5, -78))
        assertEquals("N00E000", DemIndex.nameFor(0, 0))
    }
}
