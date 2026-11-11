package app.maparound.terrain

import app.maparound.shared.testEnv
import app.maparound.shared.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeaMaskTest {
    @Test
    fun ringsFillTheirInside() {
        val size = 11
        val mask = Bits(size * size)
        // A square from 47.25 to 47.75 N, 9.25 to 9.75 E on the 1° cell N47E009 (row 0 = north),
        // so its edges fall between grid rows and columns.
        val ring = doubleArrayOf(47.25, 9.25, 47.75, 9.25, 47.75, 9.75, 47.25, 9.75, 47.25, 9.25)
        val n = size - 1
        SeaMask.fillRing(mask, size, ring, { lat -> (48 - lat) * n }, { lon -> (lon - 9) * n }, true)
        assertTrue(mask[5 * size + 5]) // the middle
        assertFalse(mask[0]) // the north-west corner
        assertFalse(mask[5 * size + 0]) // west edge
        // Rows and columns 3..7 are inside.
        var count = 0
        for (i in 0 until size * size) if (mask[i]) count++
        assertEquals(5 * 5, count)
        assertTrue(mask[3 * size + 3] && mask[7 * size + 7])
        assertFalse(mask[2 * size + 5] || mask[8 * size + 5])
    }

    @Test
    fun landlockedMapHasNoSea() {
        val path = testEnv("MAP_FILE") ?: return
        val masks = MapCoastMasks(listOf(path))
        try {
            assertNull(masks.build(47, 9, 1201))
        } finally {
            masks.close()
        }
    }
}
