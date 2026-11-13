package app.maparound.shared.mvt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BuildingReliefTest {
    @Test
    fun howTheGroundVariesUnderABuilding() {
        // A square on ground rising 100 m per 0.001° to the east: 50 m from the middle each way.
        val ring = doubleArrayOf(47.0, 9.0, 47.0, 9.001, 47.001, 9.001, 47.001, 9.0)
        val slope = { _: Double, lon: Double -> ((lon - 9.0) * 100_000).toFloat() }
        val r = MapsforgeToMvt.relief(ring, slope)!!
        assertEquals(50.0, r[MapsforgeToMvt.RELIEF_UP])
        assertEquals(50.0, r[MapsforgeToMvt.RELIEF_DOWN])
        // Flat ground: nothing to add; no data: nothing.
        assertNull(MapsforgeToMvt.relief(ring) { _, _ -> 120f })
        assertNull(MapsforgeToMvt.relief(ring) { _, _ -> Float.NaN })
    }
}
