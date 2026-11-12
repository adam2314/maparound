package app.maparound.map.maplibre

import app.maparound.data.Prefs
import app.maparound.map.BaseMap
import app.maparound.map.MapThemes
import app.maparound.shared.fileSystem
import app.maparound.shared.tempDir
import app.maparound.terrain.DemIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.Inflater
import okio.InflaterSource
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainTilesTest {
    private val dir = "${tempDir.trimEnd('/', '\\')}/maparound-terrain-test-${kotlin.random.Random.nextInt(1_000_000)}"

    /** N47E009 as a 3" tile rising 2 m per sample eastwards: 0 m at 9°E, 2400 m at 10°E. */
    private fun writeSlope() {
        fileSystem.createDirectories(dir.toPath())
        val n = 1201
        fileSystem.write("$dir/N47E009.hgt".toPath()) {
            for (row in 0 until n) for (col in 0 until n) writeShort(col * 2)
        }
    }

    @AfterTest
    fun cleanUp() {
        runCatching { fileSystem.deleteRecursively(dir.toPath()) }
    }

    /** MapLibre tile numbers around a point. */
    private fun tileOf(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lon + 180) / 360 * n).toInt()
        val r = lat * kotlin.math.PI / 180
        val y = ((1 - kotlin.math.ln(kotlin.math.tan(r) + 1 / kotlin.math.cos(r)) / kotlin.math.PI) / 2 * n).toInt()
        return x to y
    }

    @Test
    fun terrariumEncodesTheHeights() {
        writeSlope()
        val tiles = TerrainTiles(DemIndex(dir))
        val (x, y) = tileOf(47.5, 9.5, 10)
        val png = assertNotNull(tiles.terrarium(10, x, y))
        val pixels = decodeRgb(png)
        assertEquals(256 * 256 * 3, pixels.size)
        // Check a column of pixels against the slope (height = 2400 m × degrees east of 9°E).
        val n = (1 shl 10).toDouble()
        for (px in listOf(0, 100, 255)) {
            val lon = (x + (px + 0.5) / 256) / n * 360 - 180
            val i = (128 * 256 + px) * 3
            val h = (pixels[i].toInt() and 0xFF) * 256 + (pixels[i + 1].toInt() and 0xFF) + (pixels[i + 2].toInt() and 0xFF) / 256.0 - 32768
            assertTrue(abs(h - (lon - 9) * 2400) < 3, "pixel $px: $h m at $lon")
        }
        // No elevation data at all: no tile.
        val (sx, sy) = tileOf(30.0, -40.0, 10)
        assertNull(tiles.terrarium(10, sx, sy))
    }

    @Test
    fun contoursFollowTheSlope() {
        writeSlope()
        val tiles = TerrainTiles(DemIndex(dir))
        val (x, y) = tileOf(47.5, 9.5, 12)
        val mvt = tiles.contours(12, x, y)
        assertTrue(mvt.size > 100, "${mvt.size} bytes")
        // The layer name and the tags the theme styles.
        val text = mvt.decodeToString()
        assertTrue("contours" in text && "major" in text && "minor" in text && "ele" in text)
        // Too far out, none.
        assertTrue(tiles.contours(8, x shr 4, y shr 4).size < 10)
    }

    @Test
    fun styleGetsContoursAndHillShadingWithTerrain() {
        writeSlope()
        val service = TileService(dir).apply { baseUrl = "http://127.0.0.1:9" }
        assertNotNull(service.refreshTerrain())
        val (x, y) = tileOf(47.5, 9.5, 11)
        assertEquals("image/png", service.respond("/dem/${service.terrainVersion}/11/$x/$y.png")?.contentType)
        assertNotNull(service.respond("/contours/${service.terrainVersion}/11/$x/$y.pbf"))

        val theme = ThemeStyle.Hillshade(index = 1, minZoom = 8, maxZoom = 18, magnitude = 55)
        val style = MapStyle(BasicLayers.layers, BasicLayers.GLYPHS, theme)
        fun build(contours: Boolean, hillshade: Boolean) = Json.parseToJsonElement(
            style.build(
                BaseMap.Offline(
                    maps = emptyList(), theme = MapThemes.TOPO, categories = null, contours = contours, hillshade = hillshade, terrainVersion = 1, trails = false,
                    trailsVersion = 0, background = Prefs.BACKGROUND_NONE, vectorOnTop = true, rasterMaps = emptyList(),
                ),
                service,
            ),
        ).jsonObject
        val both = build(contours = true, hillshade = true)
        assertTrue("contours" in both["sources"]!!.jsonObject && "dem" in both["sources"]!!.jsonObject)
        val ids = both["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        // Hill shading over the land cover, under roads.
        assertTrue(ids.indexOf("hillshade") in 1 until ids.indexOf("roads"), ids.toString())
        val none = build(contours = false, hillshade = false)
        assertTrue("contours" !in none["sources"]!!.jsonObject && "dem" !in none["sources"]!!.jsonObject)
    }

    @Test
    fun pngChecksums() {
        // CRC-32 of "IEND" is AE426082 (every PNG ends with it).
        assertEquals(0xAE426082L.toInt(), Png.crc32(0xFFFFFFFFL.toInt(), "IEND".encodeToByteArray()).inv())
    }

    /** The pixels of an 8-bit RGB PNG with unfiltered rows (what [Png] writes). */
    private fun decodeRgb(png: ByteArray): ByteArray {
        val b = Buffer().write(png)
        b.skip(8)
        val idat = Buffer()
        var width = 0
        while (!b.exhausted()) {
            val len = b.readInt()
            val type = b.readUtf8(4)
            val data = b.readByteArray(len.toLong())
            b.readInt() // crc
            when (type) {
                "IHDR" -> width = Buffer().write(data).readInt()
                "IDAT" -> idat.write(data)
            }
        }
        val raw = InflaterSource(idat, Inflater()).buffer().readByteArray()
        val stride = 1 + width * 3
        val out = Buffer()
        for (row in 0 until raw.size / stride) out.write(raw, row * stride + 1, width * 3)
        return out.readByteArray()
    }
}
