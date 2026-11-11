package app.maparound.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.util.BitSet

class HgtProcessorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun hgtBytes(size: Int, height: (row: Int, col: Int) -> Int): ByteArray {
        val buf = ByteBuffer.allocate(size * size * 2) // big-endian, like .hgt
        for (r in 0 until size) for (c in 0 until size) buf.putShort(height(r, c).toShort())
        return buf.array()
    }

    private fun read(file: File, size: Int, row: Int, col: Int): Int =
        ByteBuffer.wrap(file.readBytes()).getShort((row * size + col) * 2).toInt()

    @Test
    fun seaFloorIsFlattenedButVoidsAreKept() {
        val src = hgtBytes(1201) { r, c -> if (c < 10) -50 else if (c == 10) HgtTile.VOID else r }
        val out = tmp.newFile("N54W005.hgt")
        HgtProcessor.convert(src.inputStream(), 1201, out, highDetail = true, keepNegative = false)
        assertEquals(1201L * 1201 * 2, out.length())
        assertEquals(0, read(out, 1201, 5, 3))              // -50 m sea floor -> 0
        assertEquals(HgtTile.VOID, read(out, 1201, 5, 10))  // voids stay voids
        assertEquals(700, read(out, 1201, 700, 600))        // land unchanged
    }

    @Test
    fun belowSeaLevelBasinsKeepNegativeHeights() {
        assertTrue(HgtProcessor.keepsNegativeHeights(31, 35))  // Dead Sea
        assertFalse(HgtProcessor.keepsNegativeHeights(32, 34)) // Mediterranean coast
        val src = hgtBytes(1201) { _, _ -> -420 }
        val out = tmp.newFile("N31E035.hgt")
        HgtProcessor.convert(src.inputStream(), 1201, out, highDetail = true, keepNegative = true)
        assertEquals(-420, read(out, 1201, 600, 600))
    }

    @Test
    fun standardDetailTakesEveryThirdSample() {
        val src = hgtBytes(3601) { r, c -> (r % 100) * 100 + (c % 100) }
        val out = tmp.newFile("N46E007.hgt")
        HgtProcessor.convert(src.inputStream(), 3601, out, highDetail = false, keepNegative = false)
        assertEquals(HgtProcessor.SIZE_3_ARCSEC, HgtProcessor.sizeForLength(out.length()))
        // output (r, c) = input (3r, 3c)
        assertEquals((300 % 100) * 100 + (900 % 100), read(out, 1201, 100, 300))
        assertEquals((3600 % 100) * 100 + (3600 % 100), read(out, 1201, 1200, 1200))
    }

    @Test
    fun coastMaskZeroesSeaPixelsOnly() {
        val src = hgtBytes(1201) { _, _ -> 25 } // blocky "land" spilling into the sea
        val mask = BitSet(1201 * 1201).apply { set(0, 1201 * 600) } // northern half is sea
        val out = tmp.newFile("N54W004.hgt")
        HgtProcessor.convert(src.inputStream(), 1201, out, highDetail = true, keepNegative = false, seaMask = mask)
        assertEquals(0, read(out, 1201, 10, 10))
        assertEquals(25, read(out, 1201, 900, 10))
    }
}
