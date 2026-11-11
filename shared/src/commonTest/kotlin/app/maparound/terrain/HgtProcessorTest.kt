package app.maparound.terrain

import app.maparound.shared.fileSystem
import app.maparound.shared.tempDir
import app.maparound.shared.util.Bits
import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

class HgtProcessorTest {
    private val dir: Path = (tempDir.toPath() / "hgt-test-${Random.nextLong().toULong()}").also { fileSystem.createDirectories(it) }

    @AfterTest
    fun cleanUp() {
        // Windows keeps memory-mapped files locked until they are garbage collected.
        runCatching { fileSystem.deleteRecursively(dir) }
    }

    private fun hgtBytes(size: Int, height: (row: Int, col: Int) -> Int): Buffer {
        val w = ByteWriter(size * size * 2) // big-endian, like .hgt
        for (r in 0 until size) for (c in 0 until size) w.short(height(r, c))
        return Buffer().write(w.toByteArray())
    }

    private fun file(name: String) = (dir / name).toString()
    private fun length(path: String) = fileSystem.metadata(path.toPath()).size!!

    private fun read(path: String, size: Int, row: Int, col: Int): Int =
        ByteReader(fileSystem.read(path.toPath()) { readByteArray() }, (row * size + col) * 2).short().toInt()

    @Test
    fun seaFloorIsFlattenedButVoidsAreKept() {
        val src = hgtBytes(1201) { r, c -> if (c < 10) -50 else if (c == 10) HgtTile.VOID else r }
        val out = file("N54W005.hgt")
        HgtProcessor.convert(src, 1201, out, highDetail = true, keepNegative = false)
        assertEquals(1201L * 1201 * 2, length(out))
        assertEquals(0, read(out, 1201, 5, 3))              // -50 m sea floor -> 0
        assertEquals(HgtTile.VOID, read(out, 1201, 5, 10))  // voids stay voids
        assertEquals(700, read(out, 1201, 700, 600))        // land unchanged
    }

    @Test
    fun belowSeaLevelBasinsKeepNegativeHeights() {
        assertTrue(HgtProcessor.keepsNegativeHeights(31, 35))  // Dead Sea
        assertFalse(HgtProcessor.keepsNegativeHeights(32, 34)) // Mediterranean coast
        val src = hgtBytes(1201) { _, _ -> -420 }
        val out = file("N31E035.hgt")
        HgtProcessor.convert(src, 1201, out, highDetail = true, keepNegative = true)
        assertEquals(-420, read(out, 1201, 600, 600))
    }

    @Test
    fun standardDetailTakesEveryThirdSample() {
        val src = hgtBytes(3601) { r, c -> (r % 100) * 100 + (c % 100) }
        val out = file("N46E007.hgt")
        HgtProcessor.convert(src, 3601, out, highDetail = false, keepNegative = false)
        assertEquals(HgtProcessor.SIZE_3_ARCSEC, HgtProcessor.sizeForLength(length(out)))
        // output (r, c) = input (3r, 3c)
        assertEquals((300 % 100) * 100 + (900 % 100), read(out, 1201, 100, 300))
        assertEquals((3600 % 100) * 100 + (3600 % 100), read(out, 1201, 1200, 1200))
    }

    @Test
    fun coastMaskZeroesSeaPixelsOnly() {
        val src = hgtBytes(1201) { _, _ -> 25 } // blocky "land" spilling into the sea
        val mask = Bits(1201 * 1201).apply { set(0, 1201 * 600, true) } // northern half is sea
        val out = file("N54W004.hgt")
        HgtProcessor.convert(src, 1201, out, highDetail = true, keepNegative = false, seaMask = mask)
        assertEquals(0, read(out, 1201, 10, 10))
        assertEquals(25, read(out, 1201, 900, 10))
    }

    @Test
    fun hgtNamesRoundTrip() {
        assertEquals(46 to 7, DemIndex.parseName("N46E007.hgt"))
        assertEquals(-5 to -78, DemIndex.parseName("S05W078.hgt"))
        assertEquals("S05W078", DemIndex.nameFor(-5, -78))
        assertEquals("N00E000", DemIndex.nameFor(0, 0))
        assertNull(DemIndex.parseName("N46E007.hgt.tmp"))
    }

    @Test
    fun demIndexReadsMappedTiles() {
        // Heights rise 1 m per row going south and 2 m per column going east.
        HgtProcessor.convert(hgtBytes(1201) { r, c -> 1000 + r + 2 * c }, 1201, file("N46E007.hgt"), highDetail = true, keepNegative = true)
        HgtProcessor.convert(hgtBytes(1201) { _, _ -> HgtTile.VOID }, 1201, file("S05W078.hgt"), highDetail = true, keepNegative = true)
        fileSystem.write(dir / "notes.txt") { writeUtf8("not a tile") }
        val dem = DemIndex(dir.toString())
        assertEquals(2, dem.tileCount)
        assertTrue(dem.covers(46, 7))
        assertTrue(dem.hasDataIn(46.2, 7.2, 46.3, 7.3))
        assertFalse(dem.hasDataIn(10.0, 10.0, 11.0, 11.0))
        assertEquals(GeoBounds(-5.0, -78.0, 47.0, 8.0), dem.bounds())
        assertEquals(1.0 / 1200, dem.finestStep, 1e-12)
        // North-west corner, the middle, and an interpolated point.
        assertEquals(1000f, dem.elevation(46.99999, 7.0), 0.02f) // 47.0 itself is the next tile north
        assertEquals(1000f + 600 + 1200, dem.elevation(46.5, 7.5), 0.01f)
        assertEquals(1000f + 0.25f * 1200 + 2 * 0.75f * 1200, dem.elevation(46.75, 7.75), 0.01f)
        assertTrue(dem.elevation(-4.5, -77.5).isNaN()) // all void
        assertTrue(dem.elevation(10.0, 10.0).isNaN())  // no tile
        assertTrue(dem.fingerprint.startsWith("N46E007.hgt:2884802|S05W078.hgt:2884802"))
    }
}
