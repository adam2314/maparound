package app.maparound.raster

import app.maparound.i18n.T

// Plain Kotlin: reading TIFF images (GeoTIFF scanned maps and orthophotos). Unit-tested.
// Baseline TIFF 6.0 and BigTIFF, striped or tiled, chunky or planar; uncompressed, LZW, Deflate,
// PackBits, JPEG and WebP (the last two decoded by the platform).

class TiffException(message: String) : Exception(message)

/** ARGB pixels, row by row. */
class Pixels(val width: Int, val height: Int, val argb: IntArray)

/** What the platform decodes: zlib streams, and whole JPEG or WebP images. */
interface TiffCodecs {
    /** The zlib (Deflate) stream [data], [size] bytes once inflated (fewer when it's cut short). */
    fun inflate(data: ByteArray, size: Int): ByteArray

    /** A JPEG or WebP image's pixels, or null when it can't be decoded. */
    fun decode(data: ByteArray): Pixels?
}

/** A TIFF file: its images ([images]; in a GeoTIFF the first is the map, the next its overviews). */
class TiffFile(private val source: RandomRead, val sizeBytes: Long) {
    private val little: Boolean
    private val big: Boolean
    val images: List<TiffImage>

    init {
        val h = source.read(0, 16)
        if (h.size < 8) throw TiffException(T.offline.notTiffFile)
        little = when {
            h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
            h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
            else -> throw TiffException(T.offline.notTiffFile)
        }
        val magic = u16(h, 2)
        big = magic == 43
        if (magic != 42 && !big) throw TiffException(T.offline.notTiffFile)
        var next = if (big) s64(h, 8) else u32(h, 4)
        val out = ArrayList<TiffImage>()
        val seen = HashSet<Long>()
        while (next > 0 && next < sizeBytes && seen.add(next) && out.size < 64) {
            val (tags, after) = readIfd(next)
            out += TiffImage(this, tags)
            next = after
        }
        if (out.isEmpty()) throw TiffException(T.offline.notTiffFile)
        images = out
    }

    /** One tag: its type, count and where its values are (inline or at an offset). */
    internal class Entry(val type: Int, val count: Long, val inline: ByteArray?, val offset: Long)

    private fun readIfd(at: Long): Pair<Map<Int, Entry>, Long> {
        val countBytes = source.read(at, if (big) 8 else 2)
        val n = if (big) s64(countBytes, 0) else u16(countBytes, 0).toLong()
        if (n !in 1..4096) throw TiffException("Damaged TIFF directory")
        val entrySize = if (big) 20 else 12
        val body = source.read(at + countBytes.size, (n * entrySize + (if (big) 8 else 4)).toInt())
        val tags = HashMap<Int, Entry>()
        for (i in 0 until n.toInt()) {
            val p = i * entrySize
            val tag = u16(body, p)
            val type = u16(body, p + 2)
            val count = if (big) s64(body, p + 4) else u32(body, p + 4)
            val valueAt = p + if (big) 12 else 8
            val slot = if (big) 8 else 4
            val bytes = typeSize(type) * count
            tags[tag] = if (bytes in 0..slot.toLong()) {
                Entry(type, count, body.copyOfRange(valueAt, valueAt + slot), 0)
            } else {
                Entry(type, count, null, if (big) s64(body, valueAt) else u32(body, valueAt))
            }
        }
        val nextAt = (n * entrySize).toInt()
        return tags to (if (big) s64(body, nextAt) else u32(body, nextAt))
    }

    /** A tag's raw bytes. */
    internal fun bytes(e: Entry): ByteArray {
        val size = typeSize(e.type) * e.count
        if (size > MAX_TAG_BYTES) throw TiffException("TIFF tag too large")
        return e.inline?.copyOf(size.toInt()) ?: source.read(e.offset, size.toInt())
    }

    /** A tag's values as whole numbers. */
    internal fun longs(e: Entry): LongArray {
        val b = bytes(e)
        val n = e.count.toInt()
        return when (e.type) {
            1, 7 -> LongArray(n) { (b[it].toInt() and 0xFF).toLong() }
            6 -> LongArray(n) { b[it].toLong() }
            3 -> LongArray(n) { u16(b, it * 2).toLong() }
            8 -> LongArray(n) { u16(b, it * 2).toShort().toLong() }
            4, 13 -> LongArray(n) { u32(b, it * 4) }
            9 -> LongArray(n) { u32(b, it * 4).toInt().toLong() }
            16, 17, 18 -> LongArray(n) { s64(b, it * 8) }
            else -> doubles(e).let { d -> LongArray(n) { d[it].toLong() } }
        }
    }

    /** A tag's values as numbers with fractions. */
    internal fun doubles(e: Entry): DoubleArray {
        val b = bytes(e)
        val n = e.count.toInt()
        return when (e.type) {
            11 -> DoubleArray(n) { Float.fromBits(u32(b, it * 4).toInt()).toDouble() }
            12 -> DoubleArray(n) { Double.fromBits(s64(b, it * 8)) }
            5 -> DoubleArray(n) { u32(b, it * 8).toDouble() / u32(b, it * 8 + 4).toDouble() }
            10 -> DoubleArray(n) { u32(b, it * 8).toInt().toDouble() / u32(b, it * 8 + 4).toInt().toDouble() }
            else -> longs(e).let { l -> DoubleArray(n) { l[it].toDouble() } }
        }
    }

    internal fun text(e: Entry): String = bytes(e).decodeToString().trimEnd('\u0000')

    internal fun read(at: Long, length: Int): ByteArray {
        if (at < 0 || at >= sizeBytes || length < 0) throw TiffException("Damaged TIFF: data outside the file")
        return source.read(at, minOf(length.toLong(), sizeBytes - at).toInt())
    }

    private fun u16(b: ByteArray, p: Int): Int =
        if (little) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 4) {
            val x = (b[p + (if (little) i else 3 - i)].toLong() and 0xFF)
            v = v or (x shl (8 * i))
        }
        return v
    }

    private fun s64(b: ByteArray, p: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            val x = (b[p + (if (little) i else 7 - i)].toLong() and 0xFF)
            v = v or (x shl (8 * i))
        }
        return v
    }

    internal val littleEndian: Boolean get() = little

    private fun typeSize(type: Int): Long = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11, 13 -> 4
        5, 10, 12, 16, 17, 18 -> 8
        else -> 1
    }

    private companion object {
        const val MAX_TAG_BYTES = 256L shl 20
    }
}

/**
 * One image of a TIFF: its size, how it's cut into blocks (tiles or strips), and how its blocks
 * become pixels ([block]).
 */
class TiffImage internal constructor(private val file: TiffFile, private val tags: Map<Int, TiffFile.Entry>) {
    val width: Int = int(WIDTH) ?: throw TiffException("TIFF image without a width")
    val height: Int = int(HEIGHT) ?: throw TiffException("TIFF image without a height")
    val compression: Int = int(COMPRESSION) ?: 1
    val photometric: Int = int(PHOTOMETRIC) ?: if (compression == 7) 6 else 1
    val samples: Int = int(SAMPLES) ?: 1
    val bits: Int = (tags[BITS]?.let(file::longs)?.firstOrNull() ?: 1L).toInt()
    private val sampleFormat = int(SAMPLE_FORMAT) ?: 1
    private val planar = (int(PLANAR) ?: 1) == 2
    private val predictor = int(PREDICTOR) ?: 1
    private val extraSamples = tags[EXTRA_SAMPLES]?.let(file::longs) ?: LongArray(0)

    /** A reduced-resolution copy of another image (an overview), or a transparency mask. */
    val overview: Boolean = ((int(SUBFILE_TYPE) ?: 0) and 1) != 0
    val mask: Boolean = ((int(SUBFILE_TYPE) ?: 0) and 4) != 0 || photometric == 4

    val tiled: Boolean = tags.containsKey(TILE_WIDTH)

    /** The size of a block: a tile, or a strip of whole rows. */
    val blockWidth: Int = if (tiled) int(TILE_WIDTH)!! else width
    val blockHeight: Int = if (tiled) int(TILE_HEIGHT) ?: throw TiffException("TIFF tile without a height") else (int(ROWS_PER_STRIP) ?: height).coerceIn(1, height)
    val blocksAcross: Int = (width + blockWidth - 1) / blockWidth
    val blocksDown: Int = (height + blockHeight - 1) / blockHeight

    /** The value of pixels that aren't part of the map (GDAL's no-data), or null. */
    val noData: Double? = tags[GDAL_NODATA]?.let { file.text(it).trim().toDoubleOrNull() }

    init {
        if (width <= 0 || height <= 0 || blockWidth <= 0 || blockHeight <= 0) throw TiffException("Damaged TIFF: no size")
        if (sampleFormat == 3 || (sampleFormat == 2 && bits > 8)) throw TiffException(T.offline.tiffNotAnImage)
        if (bits !in setOf(1, 2, 4, 8, 16)) throw TiffException(T.offline.tiffUnsupported("$bits bits per sample"))
        if (samples !in 1..8) throw TiffException(T.offline.tiffUnsupported("$samples samples per pixel"))
        when (compression) {
            1, 5, 7, 8, 32946, 32773, 50001 -> Unit
            6 -> throw TiffException(T.offline.tiffUnsupported("old-style JPEG"))
            else -> throw TiffException(T.offline.tiffUnsupported(COMPRESSIONS[compression] ?: "compression $compression"))
        }
        when (photometric) {
            0, 1, 2, 3, 5 -> Unit
            4 -> Unit // a mask: left out of the map
            6 -> if (compression != 7 && compression != 50001) throw TiffException(T.offline.tiffUnsupported("YCbCr without JPEG"))
            else -> throw TiffException(T.offline.tiffUnsupported("colour model $photometric"))
        }
    }

    // The geo tags, for the GeoTIFF reader.
    internal fun doubles(tag: Int): DoubleArray? = tags[tag]?.let(file::doubles)
    internal fun longs(tag: Int): LongArray? = tags[tag]?.let(file::longs)
    internal fun text(tag: Int): String? = tags[tag]?.let(file::text)

    private val offsets: LongArray by lazy {
        tags[if (tiled) TILE_OFFSETS else STRIP_OFFSETS]?.let(file::longs) ?: throw TiffException("TIFF image without data")
    }
    private val counts: LongArray by lazy {
        tags[if (tiled) TILE_COUNTS else STRIP_COUNTS]?.let(file::longs) ?: LongArray(offsets.size) { -1 }
    }
    private val colorMap: LongArray? by lazy { tags[COLOR_MAP]?.let(file::longs) }
    private val jpegTables: ByteArray? by lazy { tags[JPEG_TABLES]?.let(file::bytes) }

    /** The colour samples (gray: 1; RGB, palette index…) and whether one more is alpha. */
    private val colorSamples = when (photometric) {
        2, 6 -> 3
        5 -> 4
        else -> 1
    }
    private val alphaAt: Int = when {
        extraSamples.isNotEmpty() && (extraSamples[0] == 1L || extraSamples[0] == 2L) && samples > colorSamples -> colorSamples
        else -> -1
    }

    /**
     * The pixels of block [col], [row] (tiles: always whole, edge tiles padded; strips: only the
     * image's rows), with no-data and masked pixels transparent. Null where the file has none.
     */
    fun block(col: Int, row: Int, codecs: TiffCodecs): Pixels? {
        if (col !in 0 until blocksAcross || row !in 0 until blocksDown) return null
        val rows = if (tiled) blockHeight else minOf(blockHeight, height - row * blockHeight)
        // An image stored as a few huge blocks (one strip for the whole map) doesn't fit in memory.
        if (blockWidth.toLong() * rows > MAX_BLOCK_PIXELS) throw TiffException(T.offline.tiffUnsupported(T.offline.tiffHugeBlocks((blockWidth.toLong() * rows / 1_000_000).toInt())))
        val index = row * blocksAcross + col
        if (compression == 7 || compression == 50001) {
            val data = data(index) ?: return null
            val image = if (compression == 7) jpeg(data) else data
            val px = codecs.decode(image) ?: throw TiffException(T.offline.tiffUnsupported("an image it can't decode"))
            return if (px.height > rows) Pixels(px.width, rows, px.argb.copyOf(px.width * rows)) else px
        }
        val planes = if (planar) samples else 1
        val perPlane = blocksAcross * blocksDown
        val planeBytes = Array(planes) { p ->
            val data = data(index + p * perPlane) ?: return null
            val spp = if (planar) 1 else samples
            val rowBytes = (blockWidth * spp * bits + 7) / 8
            val raw = decompress(data, rowBytes * rows, codecs)
            if (predictor == 2) undoPredictor(raw, rowBytes, rows, spp)
            raw
        }
        return toArgb(planeBytes, blockWidth, rows)
    }

    private fun data(index: Int): ByteArray? {
        if (index >= offsets.size) return null
        val at = offsets[index]
        val n = counts.getOrElse(index) { -1L }
        if (at <= 0 || n == 0L) return null
        val length = if (n > 0) n else minOf(file.sizeBytes - at, MAX_BLOCK.toLong())
        if (length > MAX_BLOCK) throw TiffException("TIFF block too large")
        return file.read(at, length.toInt())
    }

    private fun decompress(data: ByteArray, size: Int, codecs: TiffCodecs): ByteArray {
        val out = when (compression) {
            1 -> data
            5 -> lzw(data, size)
            8, 32946 -> codecs.inflate(data, size)
            32773 -> packBits(data, size)
            else -> throw TiffException(T.offline.tiffUnsupported("compression $compression"))
        }
        return if (out.size >= size) out else out.copyOf(size)
    }

    /** A JPEG tile with the tables kept apart (JPEGTables) put back in front of it. */
    private fun jpeg(data: ByteArray): ByteArray {
        val tables = jpegTables ?: return data
        if (tables.size < 4 || data.size < 2) return data
        // tables: SOI … EOI; data: SOI …: SOI + tables' segments + data's segments.
        return tables.copyOfRange(0, tables.size - 2) + data.copyOfRange(2, data.size)
    }

    /** Horizontal differencing undone, row by row. */
    private fun undoPredictor(b: ByteArray, rowBytes: Int, rows: Int, spp: Int) {
        if (bits == 8) {
            for (r in 0 until rows) {
                val start = r * rowBytes
                for (i in start + spp until start + blockWidth * spp) b[i] = (b[i] + b[i - spp]).toByte()
            }
        } else if (bits == 16) {
            val little = file.littleEndian
            fun get(i: Int) = if (little) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            fun put(i: Int, v: Int) {
                if (little) { b[i] = v.toByte(); b[i + 1] = (v shr 8).toByte() } else { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
            }
            for (r in 0 until rows) {
                val start = r * rowBytes
                for (s in spp until blockWidth * spp) put(start + s * 2, get(start + s * 2) + get(start + (s - spp) * 2))
            }
        }
    }

    /** Samples to ARGB: gray, RGB, palette, CMYK; 1 to 16 bits; chunky or planar. */
    private fun toArgb(planes: Array<ByteArray>, w: Int, h: Int): Pixels {
        val out = IntArray(w * h)
        val maxValue = (1 shl bits) - 1
        val little = file.littleEndian
        val chunkyRow = (w * samples * bits + 7) / 8
        val planeRow = (w * bits + 7) / 8
        // Sample s of pixel x in row y, as read (0..maxValue).
        fun sample(y: Int, x: Int, s: Int): Int {
            val b: ByteArray
            val bitAt: Int
            if (planar) {
                b = planes[s]
                bitAt = y * planeRow * 8 + x * bits
            } else {
                b = planes[0]
                bitAt = y * chunkyRow * 8 + (x * samples + s) * bits
            }
            val i = bitAt / 8
            return when (bits) {
                8 -> b[i].toInt() and 0xFF
                16 -> if (little) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) else ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
                else -> ((b[i].toInt() and 0xFF) shr (8 - bits - bitAt % 8)) and maxValue
            }
        }
        fun to8(v: Int): Int = when (bits) {
            8 -> v
            16 -> v shr 8
            else -> v * 255 / maxValue
        }
        val noData = noData?.let { nd -> if (nd == kotlin.math.floor(nd) && nd >= 0 && nd <= maxValue) nd.toInt() else null }
        val palette = colorMap
        val paletteSize = 1 shl bits
        if (photometric == 3 && (palette == null || palette.size < paletteSize * 3)) throw TiffException("TIFF palette missing")
        for (y in 0 until h) for (x in 0 until w) {
            val first = sample(y, x, 0)
            val argb = when (photometric) {
                0, 1 -> {
                    val g = to8(first).let { if (photometric == 0) 255 - it else it }
                    0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
                }
                3 -> {
                    val p = palette!!
                    val r = (p[first].toInt() shr 8) and 0xFF
                    val g = (p[paletteSize + first].toInt() shr 8) and 0xFF
                    val bl = (p[2 * paletteSize + first].toInt() shr 8) and 0xFF
                    0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
                }
                5 -> {
                    val c = to8(first)
                    val m = to8(sample(y, x, 1))
                    val ye = to8(sample(y, x, 2))
                    val k = to8(sample(y, x, 3))
                    val r = (255 - c) * (255 - k) / 255
                    val g = (255 - m) * (255 - k) / 255
                    val bl = (255 - ye) * (255 - k) / 255
                    0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
                }
                else -> {
                    if (samples < 3) {
                        val g = to8(first)
                        0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
                    } else {
                        0xFF000000.toInt() or (to8(first) shl 16) or (to8(sample(y, x, 1)) shl 8) or to8(sample(y, x, 2))
                    }
                }
            }
            var a = if (alphaAt >= 0) to8(sample(y, x, alphaAt)) else 255
            if (noData != null && (0 until minOf(colorSamples, samples)).all { s -> (if (s == 0) first else sample(y, x, s)) == noData }) a = 0
            out[y * w + x] = if (a == 255) argb else if (a == 0) 0 else (argb and 0x00FFFFFF) or (a shl 24)
        }
        return Pixels(w, h, out)
    }

    private fun int(tag: Int): Int? = tags[tag]?.let(file::longs)?.firstOrNull()?.toInt()

    internal companion object {
        const val SUBFILE_TYPE = 254
        const val WIDTH = 256
        const val HEIGHT = 257
        const val BITS = 258
        const val COMPRESSION = 259
        const val PHOTOMETRIC = 262
        const val STRIP_OFFSETS = 273
        const val SAMPLES = 277
        const val ROWS_PER_STRIP = 278
        const val STRIP_COUNTS = 279
        const val PLANAR = 284
        const val PREDICTOR = 317
        const val COLOR_MAP = 320
        const val TILE_WIDTH = 322
        const val TILE_HEIGHT = 323
        const val TILE_OFFSETS = 324
        const val TILE_COUNTS = 325
        const val EXTRA_SAMPLES = 338
        const val SAMPLE_FORMAT = 339
        const val JPEG_TABLES = 347
        const val GDAL_NODATA = 42113
        const val MAX_BLOCK = 64 shl 20
        const val MAX_BLOCK_PIXELS = 16_000_000L

        val COMPRESSIONS = mapOf(2 to "CCITT", 3 to "CCITT fax 3", 4 to "CCITT fax 4", 34887 to "LERC", 50000 to "ZSTD", 34925 to "LZMA", 34712 to "JPEG 2000")
    }
}

/** TIFF's LZW (codes from 9 to 12 bits, most significant bit first, "early change"). */
internal fun lzw(src: ByteArray, size: Int): ByteArray {
    val out = ByteArray(size)
    var op = 0
    val prefix = IntArray(4096)
    val suffix = ByteArray(4096)
    val length = IntArray(4096)
    val first = ByteArray(4096)
    for (i in 0 until 256) {
        prefix[i] = -1
        suffix[i] = i.toByte()
        length[i] = 1
        first[i] = i.toByte()
    }
    var next = 258
    var width = 9
    var bit = 0L
    val total = src.size.toLong() * 8
    fun code(): Int {
        if (bit + width > total) return 257
        var v = 0
        for (k in 0 until width) {
            val b = bit + k
            v = (v shl 1) or ((src[(b ushr 3).toInt()].toInt() shr (7 - (b and 7).toInt())) and 1)
        }
        bit += width
        return v
    }
    fun write(c: Int) {
        val n = length[c]
        var end = op + n
        var k = c
        while (k >= 0) {
            end--
            if (end < size) out[end] = suffix[k]
            k = prefix[k]
        }
        op += n
    }
    fun add(p: Int, c: Byte) {
        if (next >= 4096) return
        prefix[next] = p
        suffix[next] = c
        length[next] = length[p] + 1
        first[next] = first[p]
        next++
        if (next >= (1 shl width) - 1 && width < 12) width++
    }
    var old = -1
    while (op < size) {
        var c = code()
        if (c == 257) break
        if (c == 256) {
            next = 258
            width = 9
            c = code()
            if (c == 257) break
            if (c > 255) throw TiffException("Damaged LZW data")
            write(c)
            old = c
            continue
        }
        if (old < 0) {
            if (c > 255) throw TiffException("Damaged LZW data")
            write(c)
        } else if (c < next) {
            write(c)
            add(old, first[c])
        } else if (c == next) {
            add(old, first[old])
            write(c)
        } else {
            throw TiffException("Damaged LZW data")
        }
        old = c
    }
    return out
}

/** PackBits run-length decoding. */
internal fun packBits(src: ByteArray, size: Int): ByteArray {
    val out = ByteArray(size)
    var i = 0
    var o = 0
    while (i < src.size && o < size) {
        val n = src[i++].toInt()
        when {
            n >= 0 -> for (k in 0..n) { if (i < src.size && o < size) out[o++] = src[i++] }
            n != -128 -> {
                val v = if (i < src.size) src[i++] else 0
                for (k in 0..-n) if (o < size) out[o++] = v
            }
        }
    }
    return out
}
