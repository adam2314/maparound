package app.maparound.raster

import app.maparound.routing.GeoPoint
import app.maparound.shared.testEnv
import app.maparound.tools.Itm
import app.maparound.tools.Utm
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GeoTIFFs written here in each of their variants, read back. */
class GeoTiffTest {
    /** The JVM's decoders (the app has Android's). */
    private val codecs = object : TiffCodecs {
        override fun inflate(data: ByteArray, size: Int): ByteArray {
            val inf = java.util.zip.Inflater()
            inf.setInput(data)
            val out = ByteArray(size)
            var n = 0
            while (n < size && !inf.finished()) {
                val k = inf.inflate(out, n, size - n)
                if (k == 0 && inf.needsInput()) break
                n += k
            }
            inf.end()
            return out
        }

        override fun decode(data: ByteArray): Pixels? {
            val img = ImageIO.read(data.inputStream()) ?: return null
            return Pixels(img.width, img.height, IntArray(img.width * img.height) { img.getRGB(it % img.width, it / img.width) })
        }
    }

    // ---- a small TIFF writer ----------------------------------------------------------------------

    private class Tag(val tag: Int, val type: Int, val values: Any)

    /**
     * One image (and maybe more after it): its blocks already encoded, and its tags. Writes header,
     * blocks, then each directory with its long values after it.
     */
    private class Writer(val little: Boolean = true, val big: Boolean = false) {
        private val out = ByteArrayOutputStream()
        private fun pad() { while (out.size() % 2 != 0) out.write(0) }
        private fun num(v: Long, bytes: Int) {
            for (i in 0 until bytes) out.write((v shr (8 * (if (little) i else bytes - 1 - i))).toInt() and 0xFF)
        }

        fun write(images: List<Pair<List<ByteArray>, List<Tag>>>, offsetsTag: Int, countsTag: Int): ByteArray {
            out.write(if (little) 'I'.code else 'M'.code); out.write(if (little) 'I'.code else 'M'.code)
            num(if (big) 43 else 42, 2)
            if (big) { num(8, 2); num(0, 2) }
            val firstIfdAt = out.size()
            num(0, if (big) 8 else 4)
            var patchAt = firstIfdAt
            val bytes = out.toByteArray().toMutableList()
            for ((blocks, tags) in images) {
                pad()
                val offsets = blocks.map { b -> out.size().toLong().also { out.write(b) } }
                pad()
                val all = tags + Tag(offsetsTag, if (big) 16 else 4, offsets.toLongArray()) + Tag(countsTag, if (big) 16 else 4, blocks.map { it.size.toLong() }.toLongArray())
                val sorted = all.sortedBy { it.tag }
                val ifdAt = out.size().toLong()
                patch(patchAt, ifdAt)
                val entrySize = if (big) 20 else 12
                val slot = if (big) 8 else 4
                val dataAt = ifdAt + (if (big) 8 else 2) + sorted.size * entrySize + slot
                var extra = dataAt
                val longValues = ByteArrayOutputStream()
                num(sorted.size.toLong(), if (big) 8 else 2)
                for (t in sorted) {
                    val raw = encode(t)
                    val count = when (val v = t.values) {
                        is LongArray -> v.size
                        is DoubleArray -> v.size
                        is String -> raw.size
                        is ByteArray -> v.size
                        else -> error("?")
                    }
                    num(t.tag.toLong(), 2); num(t.type.toLong(), 2); num(count.toLong(), if (big) 8 else 4)
                    if (raw.size <= slot) {
                        out.write(raw); repeat(slot - raw.size) { out.write(0) }
                    } else {
                        num(extra, slot)
                        longValues.write(raw)
                        if (raw.size % 2 != 0) longValues.write(0)
                        extra = dataAt + longValues.size()
                    }
                }
                patchAt = out.size()
                num(0, slot)
                out.write(longValues.toByteArray())
            }
            return out.toByteArray().also { it.toString() }.let { result -> patches.forEach { (at, v) -> put(result, at, v) }; result }
        }

        private val patches = ArrayList<Pair<Int, Long>>()
        private fun patch(at: Int, v: Long) { patches += at to v }
        private fun put(b: ByteArray, at: Int, v: Long) {
            val n = if (big) 8 else 4
            for (i in 0 until n) b[at + i] = (v shr (8 * (if (little) i else n - 1 - i))).toByte()
        }

        private fun encode(t: Tag): ByteArray {
            val o = ByteArrayOutputStream()
            fun n(v: Long, bytes: Int) { for (i in 0 until bytes) o.write((v shr (8 * (if (little) i else bytes - 1 - i))).toInt() and 0xFF) }
            when (val v = t.values) {
                is LongArray -> v.forEach { n(it, when (t.type) { 1, 7 -> 1; 3 -> 2; 16 -> 8; else -> 4 }) }
                is DoubleArray -> v.forEach { n(it.toRawBits(), 8) }
                is String -> { o.write(v.toByteArray()); o.write(0) }
                is ByteArray -> o.write(v)
            }
            return o.toByteArray()
        }
    }

    private fun short(tag: Int, vararg v: Long) = Tag(tag, 3, v)
    private fun long(tag: Int, vararg v: Long) = Tag(tag, 4, v)
    private fun doubles(tag: Int, vararg v: Double) = Tag(tag, 12, v)

    /** GeoKeyDirectory: (key, value) pairs of short values. */
    private fun geoKeys(vararg keys: Pair<Int, Int>) = Tag(34735, 3, longArrayOf(1, 1, 0, keys.size.toLong()) + keys.flatMap { (k, v) -> listOf(k.toLong(), 0L, 1L, v.toLong()) })

    /** A test image: colour from x and y, so every pixel is different. */
    private fun color(x: Int, y: Int) = 0xFF000000.toInt() or ((x * 7 and 0xFF) shl 16) or ((y * 11 and 0xFF) shl 8) or ((x + y) and 0xFF)

    private fun rgbRows(w: Int, x0: Int, y0: Int, bw: Int, bh: Int, alpha: ((Int, Int) -> Int)? = null): ByteArray {
        val o = ByteArrayOutputStream()
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) {
            val c = color(x, y)
            o.write((c shr 16) and 0xFF); o.write((c shr 8) and 0xFF); o.write(c and 0xFF)
            if (alpha != null) o.write(alpha(x, y))
        }
        return o.toByteArray()
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(b); d.finish()
        val out = ByteArray(b.size + 1024)
        val n = d.deflate(out)
        d.end()
        return out.copyOf(n)
    }

    /** TIFF's LZW, as libtiff writes it (early change). */
    private fun lzwEncode(data: ByteArray): ByteArray {
        val bits = ArrayList<Boolean>()
        var width = 9
        fun emit(code: Int) { for (k in width - 1 downTo 0) bits += (code shr k) and 1 == 1 }
        val dict = HashMap<Int, Int>()
        var next = 258
        emit(256)
        var w = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 0xFF
            val key = w * 256 + c
            val found = dict[key]
            if (found != null) {
                w = found
                continue
            }
            emit(w)
            dict[key] = next++
            if (next == 4094) {
                emit(256); dict.clear(); next = 258; width = 9
            } else if (next > (1 shl width) - 1) width++
            w = c
        }
        emit(w)
        next++
        if (next > (1 shl width) - 1) width++
        emit(257)
        val out = ByteArray((bits.size + 7) / 8)
        bits.forEachIndexed { i, b -> if (b) out[i / 8] = (out[i / 8].toInt() or (0x80 shr (i % 8))).toByte() }
        return out
    }

    private fun packBitsEncode(b: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var i = 0
        while (i < b.size) {
            var run = 1
            while (i + run < b.size && run < 128 && b[i + run] == b[i]) run++
            if (run > 1) {
                o.write(1 - run); o.write(b[i].toInt()); i += run
            } else {
                var lit = 1
                while (i + lit < b.size && lit < 128 && (i + lit + 1 >= b.size || b[i + lit] != b[i + lit + 1])) lit++
                o.write(lit - 1); o.write(b, i, lit); i += lit
            }
        }
        return o.toByteArray()
    }

    private fun open(bytes: ByteArray) = TiffFile({ pos, len -> bytes.copyOfRange(pos.toInt(), minOf(bytes.size, pos.toInt() + len)) }, bytes.size.toLong())

    /** Every pixel of [img] as the image's own colours ([color]), block by block. */
    private fun assertImage(img: TiffImage, w: Int, h: Int, alpha: ((Int, Int) -> Int)? = null) {
        for (by in 0 until img.blocksDown) for (bx in 0 until img.blocksAcross) {
            val px = img.block(bx, by, codecs)!!
            for (y in 0 until px.height) for (x in 0 until px.width) {
                val ix = bx * img.blockWidth + x
                val iy = by * img.blockHeight + y
                if (ix >= w || iy >= h) continue
                val a = alpha?.invoke(ix, iy) ?: 255
                val want = if (a == 0) 0 else (color(ix, iy) and 0xFFFFFF) or (a shl 24)
                assertEquals(want, px.argb[y * px.width + x], "pixel $ix,$iy")
            }
        }
    }

    // ---- images ----------------------------------------------------------------------------------

    @Test
    fun stripsUncompressedWithItsPlaceInUtm() {
        val w = 30
        val h = 20
        val strips = (0 until h step 8).map { y -> rgbRows(w, 0, y, w, minOf(8, h - y)) }
        val e0 = 700_000.0
        val n0 = 3_480_000.0
        val tags = listOf(
            short(256, w.toLong()), short(257, h.toLong()), short(258, 8, 8, 8), short(259, 1), short(262, 2), short(277, 3), short(278, 8),
            doubles(33550, 10.0, 10.0, 0.0), doubles(33922, 0.0, 0.0, 0.0, e0, n0, 0.0),
            geoKeys(1024 to 1, 1025 to 1, 3072 to 32636),
        )
        val tiff = GeoTiff(open(Writer().write(listOf(strips to tags), 273, 279)))
        assertImage(tiff.image, w, h)
        assertEquals("UTM 36N", tiff.calibration.projection.name)
        val p = tiff.calibration.pixelToLatLon(10.0, 5.0)
        val want = Utm.tm(36, true).inverse(e0 + 100, n0 - 50)
        assertTrue(abs(p.lat - want.lat) < 1e-7 && abs(p.lon - want.lon) < 1e-7, "$p $want")
        val back = tiff.calibration.latLonToPixel(want.lat, want.lon)
        assertTrue(abs(back[0] - 10) < 1e-4 && abs(back[1] - 5) < 1e-4, back.toList().toString())
    }

    @Test
    fun tilesLzwWithPredictorBigEndian() {
        val w = 40
        val h = 30
        val t = 16
        val tiles = ArrayList<ByteArray>()
        for (ty in 0 until 2) for (tx in 0 until 3) {
            val raw = rgbRows(w, tx * t, ty * t, t, t)
            // Horizontal differencing, per row.
            for (r in 0 until t) for (i in t * 3 - 1 downTo 3) raw[r * t * 3 + i] = (raw[r * t * 3 + i] - raw[r * t * 3 + i - 3]).toByte()
            tiles += lzwEncode(raw)
        }
        val tags = listOf(
            short(256, w.toLong()), short(257, h.toLong()), short(258, 8, 8, 8), short(259, 5), short(262, 2), short(277, 3),
            short(317, 2), short(322, t.toLong()), short(323, t.toLong()),
            doubles(33550, 1.0, 1.0, 0.0), doubles(33922, 0.0, 0.0, 0.0, 200_000.0, 600_000.0, 0.0), geoKeys(1024 to 1, 3072 to 2039),
        )
        val tiff = GeoTiff(open(Writer(little = false).write(listOf(tiles to tags), 324, 325)))
        assertEquals(3, tiff.image.blocksAcross)
        assertEquals(2, tiff.image.blocksDown)
        assertImage(tiff.image, w, h)
        // The Israeli grid.
        val p = tiff.calibration.pixelToLatLon(0.0, 0.0)
        val want = Itm.toPoint(200_000.0, 600_000.0)
        assertTrue(abs(p.lat - want.lat) < 1e-8 && abs(p.lon - want.lon) < 1e-8)
    }

    @Test
    fun lzwLongImagesResetTheirTable() {
        // Enough different bytes to fill the 4096-entry table and start again.
        val data = ByteArray(200_000) { ((it * 31) xor (it shr 5)).toByte() }
        assertContentEquals(data, lzw(lzwEncode(data), data.size))
        val same = ByteArray(50_000) { 7 }
        assertContentEquals(same, lzw(lzwEncode(same), same.size))
    }

    @Test
    fun deflatePlanarWithAlphaAndPixelIsPoint() {
        val w = 12
        val h = 9
        val alpha = { x: Int, y: Int -> if (x < 3) 0 else if (y == 0) 128 else 255 }
        val planes = (0 until 4).map { s ->
            val o = ByteArrayOutputStream()
            for (y in 0 until h) for (x in 0 until w) {
                val c = color(x, y)
                o.write(if (s == 3) alpha(x, y) else (c shr (16 - 8 * s)) and 0xFF)
            }
            deflate(o.toByteArray())
        }
        val tags = listOf(
            short(256, w.toLong()), short(257, h.toLong()), short(258, 8, 8, 8, 8), short(259, 8), short(262, 2), short(277, 4),
            short(278, h.toLong()), short(284, 2), short(338, 2),
            doubles(33550, 0.001, 0.001, 0.0), doubles(33922, 0.0, 0.0, 0.0, 35.0, 32.0, 0.0), geoKeys(1024 to 2, 1025 to 2, 2048 to 4326),
        )
        val tiff = GeoTiff(open(Writer().write(listOf(planes to tags), 273, 279)))
        assertImage(tiff.image, w, h, alpha)
        // Pixel centres: the corner is half a pixel up and left.
        val corner = tiff.calibration.pixelToLatLon(0.0, 0.0)
        assertEquals(32.0005, corner.lat, 1e-9)
        assertEquals(34.9995, corner.lon, 1e-9)
    }

    @Test
    fun palettePackBitsAndOneBitGray() {
        val w = 20
        val h = 6
        val idx = ByteArray(w * h) { ((it % w) / 5).toByte() }
        val cmap = LongArray(3 * 256) { i -> val c = i % 256; when (i / 256) { 0 -> c * 4000L; 1 -> 0L; else -> 65535L - c * 4000L } }
        val tags = listOf(
            short(256, w.toLong()), short(257, h.toLong()), short(258, 8), short(259, 32773), short(262, 3), short(277, 1), short(278, h.toLong()),
            Tag(320, 3, cmap), doubles(33550, 1.0, 1.0, 0.0), doubles(33922, 0.0, 0.0, 0.0, 500_000.0, 0.0, 0.0), geoKeys(1024 to 1, 3072 to 32736),
        )
        val tiff = GeoTiff(open(Writer().write(listOf(listOf(packBitsEncode(idx)) to tags), 273, 279)))
        val px = tiff.image.block(0, 0, codecs)!!
        assertEquals(0xFF0000FF.toInt(), px.argb[0])
        assertEquals(0xFF0F00F0.toInt(), px.argb[5]) // index 1: 4000 >> 8 = 15
        assertEquals("UTM 36S", tiff.calibration.projection.name)

        // 1 bit, white is zero: a row of 10 pixels in 2 bytes.
        val bits = byteArrayOf(0b10100000.toByte(), 0b01000000)
        val t2 = listOf(short(256, 10), short(257, 1), short(258, 1), short(259, 1), short(262, 0), short(277, 1), short(278, 1))
        val img = open(Writer().write(listOf(listOf(bits) to t2), 273, 279)).images[0]
        val one = img.block(0, 0, codecs)!!.argb
        assertEquals(listOf(0, 255, 0, 255, 255, 255, 255, 255, 255, 0), one.map { it and 0xFF })
    }

    @Test
    fun bigTiffSixteenBitsWithNoData() {
        val w = 5
        val h = 2
        val o = ByteArrayOutputStream()
        for (i in 0 until w * h) { val v = if (i == 3) 0 else 1000 * i; o.write(v and 0xFF); o.write(v shr 8) }
        val tags = listOf(
            short(256, w.toLong()), short(257, h.toLong()), short(258, 16), short(259, 1), short(262, 1), short(277, 1), short(278, h.toLong()),
            Tag(42113, 2, "0"),
        )
        val img = open(Writer(big = true).write(listOf(listOf(o.toByteArray()) to tags), 273, 279)).images[0]
        val px = img.block(0, 0, codecs)!!.argb
        assertEquals(0, px[3], "no data: transparent")
        assertEquals(0xFF000000.toInt() or (3 shl 16) or (3 shl 8) or 3, px[1]) // 1000 >> 8
        assertEquals(0xFF000000.toInt() or (35 shl 16) or (35 shl 8) or 35, px[9]) // 9000 >> 8
    }

    @Test
    fun jpegTilesAndOverviewsInTheFile() {
        val w = 32
        val h = 16
        fun jpeg(x0: Int): ByteArray {
            val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 16) for (x in 0 until 16) img.setRGB(x, y, if (x0 == 0) 0xFF0000 else 0x0000FF)
            return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        }
        val base = listOf(short(256, w.toLong()), short(257, h.toLong()), short(258, 8, 8, 8), short(259, 7), short(262, 2), short(277, 3),
            short(322, 16), short(323, 16), doubles(33550, 1.0, 1.0, 0.0), doubles(33922, 0.0, 0.0, 0.0, 500_000.0, 0.0, 0.0), geoKeys(1024 to 1, 3072 to 32633))
        val ov = listOf(long(254, 1), short(256, 16), short(257, 8), short(258, 8, 8, 8), short(259, 7), short(262, 2), short(277, 3), short(322, 16), short(323, 16))
        val tiff = GeoTiff(open(Writer().write(listOf(listOf(jpeg(0), jpeg(16)) to base, listOf(jpeg(0)) to ov), 324, 325)))
        assertEquals(1, tiff.overviews.size)
        assertEquals(16, tiff.overviews[0].width)
        val left = tiff.image.block(0, 0, codecs)!!.argb[8 * 16 + 8]
        val right = tiff.image.block(1, 0, codecs)!!.argb[8 * 16 + 8]
        assertTrue((left shr 16 and 0xFF) > 200 && (left and 0xFF) < 60, Integer.toHexString(left))
        assertTrue((right and 0xFF) > 200 && (right shr 16 and 0xFF) < 60, Integer.toHexString(right))
    }

    @Test
    fun whatItCantReadSaysSo() {
        val float = listOf(short(256, 2), short(257, 2), short(258, 32), short(259, 1), short(262, 1), short(277, 1), short(339, 3))
        assertFailsWith<TiffException> { open(Writer().write(listOf(listOf(ByteArray(16)) to float), 273, 279)) }
        val zstd = listOf(short(256, 2), short(257, 2), short(258, 8), short(259, 50000), short(262, 1), short(277, 1))
        assertTrue(assertFailsWith<TiffException> { open(Writer().write(listOf(listOf(ByteArray(4)) to zstd), 273, 279)) }.message!!.contains("ZSTD"))
        // No place: a TIFF, not a GeoTIFF.
        val plain = listOf(short(256, 2), short(257, 2), short(258, 8), short(259, 1), short(262, 1), short(277, 1))
        assertFailsWith<TiffException> { GeoTiff(open(Writer().write(listOf(listOf(ByteArray(4)) to plain), 273, 279))) }
        assertFailsWith<TiffException> { open("not a tiff at all".toByteArray()) }
    }

    // ---- coordinate systems ------------------------------------------------------------------------

    private fun roundTrip(p: MapProjection, lat: Double, lon: Double) {
        val xy = p.forward(lat, lon)
        val back = p.inverse(xy[0], xy[1])
        assertTrue(abs(back.lat - lat) < 1e-7 && abs(back.lon - lon) < 1e-7, "${p.name}: $lat,$lon -> ${xy.toList()} -> $back")
    }

    @Test
    fun coordinateSystems() {
        // Lambert-93's origin.
        val l93 = GeoTiff.epsg(2154)!!
        val o = l93.forward(46.5, 3.0)
        assertEquals(700_000.0, o[0], 0.01)
        assertEquals(6_600_000.0, o[1], 0.01)
        roundTrip(l93, 48.8566, 2.3522)
        // Greek grid: a datum a couple of hundred metres from WGS 84, both ways.
        roundTrip(GeoTiff.epsg(2100)!!, 37.9715, 23.7257)
        roundTrip(GeoTiff.epsg(27700)!!, 51.5007, -0.1246)
        roundTrip(GeoTiff.epsg(31468)!!, 48.137, 11.575)
        roundTrip(GeoTiff.epsg(3857)!!, 31.77, 35.21)
        roundTrip(GeoTiff.epsg(31370)!!, 50.8467, 4.3525)
        // Web Mercator as the online maps: 20037508.34 m at 180°.
        assertEquals(20_037_508.34, GeoTiff.epsg(3857)!!.forward(0.0, 180.0)[0], 0.01)
        // British National Grid: Big Ben is about 530 269 E, 179 640 N.
        val bng = GeoTiff.epsg(27700)!!.forward(51.50073, -0.12463)
        assertEquals(530_269.0, bng[0], 15.0)
        assertEquals(179_640.0, bng[1], 15.0)
        assertNull(GeoTiff.epsg(28992))
    }

    @Test
    fun userDefinedTransverseMercatorAndFeet() {
        // UTM 36N described by its parameters, in feet.
        val ft = 0.3048
        val tags = listOf(
            short(256, 4), short(257, 4), short(258, 8), short(259, 1), short(262, 1), short(277, 1),
            doubles(33550, 10.0, 10.0, 0.0), doubles(33922, 0.0, 0.0, 0.0, 700_000.0 / ft, 3_480_000.0 / ft, 0.0),
            Tag(34735, 3, longArrayOf(1, 1, 0, 8,
                1024, 0, 1, 1, 3072, 0, 1, 32767, 3075, 0, 1, 1, 3076, 0, 1, 9002,
                3080, 34736, 1, 0, 3082, 34736, 1, 1, 3083, 34736, 1, 2, 3092, 34736, 1, 3)),
            doubles(34736, 33.0, 500_000.0 / ft, 0.0, 0.9996),
        )
        val tiff = GeoTiff(open(Writer().write(listOf(listOf(ByteArray(16)) to tags), 273, 279)))
        val p = tiff.calibration.pixelToLatLon(0.0, 0.0)
        val want = Utm.tm(36, true).inverse(700_000.0, 3_480_000.0)
        assertTrue(abs(p.lat - want.lat) < 1e-7 && abs(p.lon - want.lon) < 1e-7, "$p $want")
    }

    // ---- overviews MapAround makes ----------------------------------------------------------------

    @Test
    fun overviewsOfABigImage() {
        val w = 1300
        val h = 700
        val half = { x: Int, _: Int -> if (x < 650) 255 else 0 }
        // Strips of 3 rows (not a multiple of the factor), a red left half and transparent right half.
        val strips = (0 until h step 3).map { y ->
            val o = ByteArrayOutputStream()
            for (yy in y until minOf(h, y + 3)) for (x in 0 until w) { o.write(200); o.write(30); o.write(10); o.write(half(x, yy)) }
            o.toByteArray()
        }
        val tags = listOf(short(256, w.toLong()), short(257, h.toLong()), short(258, 8, 8, 8, 8), short(259, 1), short(262, 2), short(277, 4), short(278, 3), short(338, 2))
        val img = open(Writer().write(listOf(strips to tags), 273, 279)).images[0]
        val file = File.createTempFile("ovr", ".ovr")
        val encoded = HashMap<Int, Pixels>()
        // "Encoding" here keeps the pixels and writes a key to them.
        val encode = { p: Pixels -> val k = encoded.size; encoded[k] = p; byteArrayOf(k.toByte(), (k shr 8).toByte()) }
        var last = 0.0
        TiffOverviews.build(img, codecs, encode, file.path, progress = { last = it })
        assertEquals(1.0, last)
        val bytes = file.readBytes()
        val ovr = assertNotNull(TiffOverviews.read({ pos, len -> bytes.copyOfRange(pos.toInt(), minOf(bytes.size, pos.toInt() + len)) }, img))
        assertEquals(listOf(2, 4, 8), ovr.levels.map { it.factor })
        assertEquals(listOf(650, 325, 163), ovr.levels.map { it.width })
        assertEquals(listOf(350, 175, 88), ovr.levels.map { it.height })
        fun tile(level: Int, c: Int, r: Int) = ovr.tile(level, c, r)?.let { encoded[(it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8)] }
        val t = assertNotNull(tile(0, 0, 0))
        assertEquals(256, t.width)
        assertEquals(256, t.height)
        assertEquals(0xFFC81E0A.toInt(), t.argb[0])
        // Level 0's columns 0..324 opaque, from 325 transparent (the right half): tile 1 has both.
        val t1 = assertNotNull(tile(0, 1, 0))
        assertEquals(0xFFC81E0A.toInt(), t1.argb[324 - 256])
        assertEquals(0, t1.argb[325 - 256])
        // The last level's last row: 700/8 = 87.5 → a row made of half the image rows, still red.
        val l2 = assertNotNull(tile(2, 0, 0))
        assertEquals(88, l2.height)
        assertEquals(0xFFC81E0A.toInt(), l2.argb[87 * l2.width])
        // The right half: tiles with nothing in them aren't written.
        assertNull(tile(0, 2, 0))
        // Another image: not these overviews.
        val other = open(Writer().write(listOf(listOf(ByteArray(4)) to listOf(short(256, 2), short(257, 2), short(258, 8), short(259, 1), short(262, 1), short(277, 1))), 273, 279)).images[0]
        assertNull(TiffOverviews.read({ pos, len -> bytes.copyOfRange(pos.toInt(), minOf(bytes.size, pos.toInt() + len)) }, other))
        file.delete()
    }

    /** A real GeoTIFF: rasterio's RGB.byte.tif (Landsat over the Bahamas, UTM 18N, LZW), with RASTERIO_RGB=<path>. */
    @Test
    fun aRealGeoTiff() {
        val path = testEnv("RASTERIO_RGB") ?: return
        val bytes = File(path).readBytes()
        val tiff = GeoTiff(open(bytes))
        assertEquals(791, tiff.image.width)
        assertEquals(718, tiff.image.height)
        assertEquals("UTM 18N", tiff.calibration.projection.name)
        val corner = tiff.calibration.pixelToMap(0.0, 0.0)
        assertEquals(101_985.0, corner[0], 0.01)
        assertEquals(2_826_915.0, corner[1], 0.01)
        var opaque = 0
        var clear = 0
        for (by in 0 until tiff.image.blocksDown) for (bx in 0 until tiff.image.blocksAcross) {
            tiff.image.block(bx, by, codecs)?.argb?.forEach { if (it == 0) clear++ else opaque++ }
        }
        println("RGB.byte.tif: $opaque opaque, $clear no-data pixels; corner ${tiff.calibration.pixelToLatLon(0.0, 0.0)}")
        assertTrue(opaque > 300_000 && clear > 100_000)
    }
}
