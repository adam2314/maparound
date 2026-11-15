package app.maparound.shared.stream

import app.maparound.shared.fileSystem
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.mvt.MapsforgeToMvt
import app.maparound.shared.tempDir
import okio.Path.Companion.toPath
import okio.use
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MapStreamTest {
    private val url = "https://example.org/maps/somewhere.map"
    private val dir = "$tempDir/stream-test-${Random.nextLong().toULong()}"
    private var server = file(300_000, date = 1)
    private var online = true
    private val asked = ArrayList<Pair<Long, Int>>()

    /** A file that starts like a mapsforge map (magic, header size, version, size, date). */
    private fun file(size: Int, date: Long): ByteArray {
        val b = Random(date).nextBytes(size)
        "mapsforge binary OSM".encodeToByteArray().copyInto(b)
        fun long(at: Int, v: Long) { for (i in 0 until 8) b[at + i] = (v shr (56 - 8 * i)).toByte() }
        long(28, size.toLong())
        long(36, date)
        return b
    }

    @BeforeTest
    fun setUp() {
        MapStream.reset()
        MapStream.cacheDir = dir
        MapStream.fetch = { u, from, length ->
            assertEquals(url, u)
            if (!online) throw okio.IOException("offline")
            asked += from to length
            server.copyOfRange(from.toInt(), from.toInt() + length)
        }
    }

    @AfterTest
    fun tearDown() {
        MapStream.reset()
        MapStream.cacheDir = null
        runCatching { fileSystem.deleteRecursively(dir.toPath()) }
    }

    private fun read(from: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        MapStream.open(url).use { h ->
            var done = 0
            while (done < n) {
                val k = h.read(from + done, out, done, n - done)
                if (k <= 0) break
                done += k
            }
        }
        return out
    }

    @Test
    fun readsWhatsAskedAcrossPiecesAndKeepsThem() {
        assertContentEquals(server.copyOfRange(60_000, 200_000), read(60_000, 140_000))
        // The header piece, then the rest in one request: pieces 1 to 3 (0 was there).
        assertEquals(listOf(0L to MapStream.CHUNK, MapStream.CHUNK.toLong() to 3 * MapStream.CHUNK), asked)
        asked.clear()
        assertContentEquals(server.copyOfRange(100_000, 120_000), read(100_000, 20_000))
        assertTrue(asked.isEmpty(), "kept: $asked")
        // The last piece is shorter: only up to the end.
        assertContentEquals(server.copyOfRange(299_000, 300_000), read(299_000, 1_000))
        assertEquals(listOf(4L * MapStream.CHUNK to 300_000 - 4 * MapStream.CHUNK), asked)
        MapStream.open(url).use { assertEquals(300_000L, it.size()) }
    }

    @Test
    fun keptOnDiskForTheNextRunAndOfflineToo() {
        read(0, 200_000)
        MapStream.reset() // a new run: memory gone
        asked.clear()
        assertContentEquals(server.copyOfRange(150_000, 160_000), read(150_000, 10_000))
        assertEquals(listOf(0L to MapStream.CHUNK), asked, "only the check of the header")
        MapStream.reset()
        online = false
        assertContentEquals(server.copyOfRange(10, 190_000), read(10, 189_990))
        // Not kept, not reachable: later.
        assertFailsWith<StreamUnavailable> { read(280_000, 10) }
    }

    @Test
    fun aMapReplacedOnTheServerDropsWhatWasKept() {
        read(0, 200_000)
        server = file(250_000, date = 2)
        MapStream.reset()
        assertContentEquals(server.copyOfRange(100_000, 101_000), read(100_000, 1_000))
        MapStream.open(url).use { assertEquals(250_000L, it.size()) }
    }

    @Test
    fun withoutANetworkItWaitsBeforeTryingAgain() {
        online = false
        assertFailsWith<StreamUnavailable> { read(0, 10) }
        online = true
        // Within the pause: not tried.
        assertFailsWith<StreamUnavailable> { read(0, 10) }
        assertTrue(asked.isEmpty())
    }

    /** Runs only with MAP_FILE=<europe__liechtenstein.map> (download.mapsforge.org), like MapsforgeReaderTest. */
    @Test
    fun aRealMapMakesTheSameTilesStreamed() {
        val path = app.maparound.shared.testEnv("MAP_FILE") ?: return
        server = fileSystem.read(path.toPath()) { readByteArray() }
        val local = MapsforgeFile(app.maparound.shared.openTestFile(path))
        val streamed = MapsforgeFile(MapStream.open(url), wholeIndex = false)
        for (z in listOf(8, 10, 12, 13, 14, 15)) {
            val x = MapsforgeFile.lonToTileX(9.5209, z)
            val y = MapsforgeFile.latToTileY(47.1410, z)
            assertContentEquals(MapsforgeToMvt.tile(listOf(local), z, x, y), MapsforgeToMvt.tile(listOf(streamed), z, x, y), "tile $z/$x/$y")
        }
        val fetched = asked.sumOf { it.second.toLong() }
        println("streamed ${asked.size} requests, $fetched of ${server.size} bytes")
        assertTrue(fetched < server.size, "not the whole map")
    }

    @Test
    fun theCacheStaysUnderItsLimit() {
        MapStream.maxCacheBytes = 3L * MapStream.CHUNK
        try {
            server = file(20 * MapStream.CHUNK, date = 3)
            read(0, server.size)
            val kept = fileSystem.listRecursively(dir.toPath()).mapNotNull { fileSystem.metadataOrNull(it) }.filter { it.isRegularFile }.sumOf { it.size ?: 0L }
            assertTrue(kept <= 3L * MapStream.CHUNK, "$kept bytes kept")
        } finally {
            MapStream.maxCacheBytes = 512L shl 20
        }
    }
}
