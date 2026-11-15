package app.maparound.raster

import app.maparound.i18n.T
import app.maparound.shared.fileSystem
import okio.FileHandle
import okio.Path.Companion.toPath

// Plain Kotlin: reduced copies of a big TIFF that has none of its own. Unit-tested.

/**
 * Reduced copies of a big image that has none of its own (many scanned maps), made once ([build])
 * and kept in a file of their own: each level half the size of the one before, cut into
 * [TILE]-pixel tiles the platform encodes (JPEG, or PNG where there's transparency). Zoomed out,
 * the map draws from these instead of decoding the whole image.
 *
 * The file: "MAOV", version, level count, the image's width and height; per level its width,
 * height, factor (image pixels per level pixel), tiles across and down and where its index is;
 * then the tiles; then per level the index (offset and length of each tile).
 */
class TiffOverviews private constructor(private val source: RandomRead, val levels: List<Level>) {
    class Level(val width: Int, val height: Int, val factor: Int, val across: Int, val down: Int, internal val offsets: LongArray, internal val lengths: IntArray)

    /** The encoded tile [col], [row] of [level], or null where it has none. */
    fun tile(level: Int, col: Int, row: Int): ByteArray? {
        val l = levels.getOrNull(level) ?: return null
        if (col !in 0 until l.across || row !in 0 until l.down) return null
        val i = row * l.across + col
        val n = l.lengths[i]
        return if (n <= 0) null else source.read(l.offsets[i], n)
    }

    companion object {
        const val TILE = 256
        private const val MAGIC = "MAOV"
        private const val VERSION = 1

        /** Whether [image] needs them: big, with no reduced copies in its file. */
        fun needed(image: TiffImage, internal: List<TiffImage>): Boolean =
            internal.isEmpty() && maxOf(image.width, image.height) > 2 * TILE * 4

        /**
         * The levels' factors: the first so its rows stay short (memory), then halving until the
         * whole image fits in a tile.
         */
        fun factors(width: Int, height: Int): List<Int> {
            var f = 2
            while (width / f > 8192 && f < 16) f *= 2
            val out = ArrayList<Int>()
            while (true) {
                out += f
                if (maxOf(ceil(width, f), ceil(height, f)) <= TILE || f >= 1 shl 20) return out
                f *= 2
            }
        }

        /** The overviews in [source] if they're those of [image] (else null: made again). */
        fun read(source: RandomRead, image: TiffImage): TiffOverviews? = runCatching {
            val h = source.read(0, 14)
            if (h.size < 14 || h.decodeToString(0, 4) != MAGIC || h[4].toInt() != VERSION) return null
            val count = h[5].toInt()
            if (int(h, 6) != image.width || int(h, 10) != image.height || count !in 1..30) return null
            val head = source.read(14, count * 28)
            val levels = List(count) { i ->
                val p = i * 28
                val w = int(head, p)
                val ht = int(head, p + 4)
                val f = int(head, p + 8)
                val across = int(head, p + 12)
                val down = int(head, p + 16)
                val at = long(head, p + 20)
                if (across <= 0 || down <= 0 || across.toLong() * down > 4_000_000) return null
                val index = source.read(at, across * down * 12)
                if (index.size < across * down * 12) return null
                Level(w, ht, f, across, down, LongArray(across * down) { long(index, it * 12) }, IntArray(across * down) { int(index, it * 12 + 8) })
            }
            TiffOverviews(source, levels)
        }.getOrNull()

        /**
         * Reads [image] once, block row by block row, and writes its reduced copies to [path].
         * [encode] makes a tile's file bytes; [progress] goes from 0 to 1; stops (and deletes what
         * it wrote) once [isActive] turns false.
         */
        fun build(
            image: TiffImage,
            codecs: TiffCodecs,
            encode: (Pixels) -> ByteArray,
            path: String,
            progress: (Double) -> Unit = {},
            isActive: () -> Boolean = { true },
        ) {
            val factors = factors(image.width, image.height)
            val file = path.toPath()
            fileSystem.delete(file, mustExist = false)
            val handle = fileSystem.openReadWrite(file, mustCreate = true)
            var ok = false
            try {
                Writer(handle, image, factors, encode).run {
                    val first = factors[0]
                    val w1 = ceil(image.width, first)
                    // Sums of first×first image pixels (premultiplied colour and alpha, 16 bits each),
                    // per level-0 row being filled.
                    val sums = HashMap<Int, LongArray>()
                    for (by in 0 until image.blocksDown) {
                        if (!isActive()) throw TiffException("Stopped")
                        for (bx in 0 until image.blocksAcross) {
                            val px = image.block(bx, by, codecs) ?: continue
                            val x0 = bx * image.blockWidth
                            val y0 = by * image.blockHeight
                            for (y in 0 until px.height) {
                                val iy = y0 + y
                                if (iy >= image.height) break
                                val acc = sums.getOrPut(iy / first) { LongArray(w1) }
                                val row = y * px.width
                                for (x in 0 until minOf(px.width, image.width - x0)) {
                                    val c = px.argb[row + x]
                                    val a = c ushr 24
                                    if (a == 0) continue
                                    val r = ((c shr 16) and 0xFF) * a / 255
                                    val g = ((c shr 8) and 0xFF) * a / 255
                                    val b = (c and 0xFF) * a / 255
                                    acc[(x0 + x) / first] += (r.toLong() shl 48) or (g.toLong() shl 32) or (b.toLong() shl 16) or a.toLong()
                                }
                            }
                        }
                        // Level-0 rows whose image rows are all read (empty ones too: where the file has no blocks).
                        val readTo = minOf(image.height, (by + 1) * image.blockHeight)
                        for (r in (by * image.blockHeight) / first..(readTo - 1) / first) sums.getOrPut(r) { LongArray(w1) }
                        for (r in sums.keys.sorted()) {
                            if ((r + 1) * first > readTo && readTo < image.height) break
                            row(0, finish(sums.remove(r)!!, r, first, image.width, image.height))
                        }
                        progress((by + 1).toDouble() / image.blocksDown)
                    }
                    for (r in sums.keys.sorted()) row(0, finish(sums.remove(r)!!, r, first, image.width, image.height))
                    close()
                }
                ok = true
            } finally {
                runCatching { handle.close() }
                if (!ok) fileSystem.delete(file, mustExist = false)
            }
        }

        /** A level-0 row from its sums: the average of the image pixels each covers. */
        private fun finish(sums: LongArray, row: Int, f: Int, width: Int, height: Int): IntArray {
            val rows = minOf(height, (row + 1) * f) - row * f
            return IntArray(sums.size) { c ->
                val s = sums[c]
                val a = (s and 0xFFFF).toInt()
                if (a == 0) return@IntArray 0
                val n = (minOf(width, (c + 1) * f) - c * f) * rows
                val r = ((s ushr 48) and 0xFFFF).toInt() * 255 / a
                val g = ((s ushr 32) and 0xFFFF).toInt() * 255 / a
                val b = ((s ushr 16) and 0xFFFF).toInt() * 255 / a
                ((a / n).coerceIn(1, 255) shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
            }
        }

        private fun ceil(a: Int, b: Int) = (a + b - 1) / b

        private fun int(b: ByteArray, p: Int): Int =
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

        private fun long(b: ByteArray, p: Int): Long = (int(b, p).toLong() and 0xFFFFFFFFL) or (int(b, p + 4).toLong() shl 32)
    }

    /** Takes each level's rows in turn, writes its tiles a band of [TILE] rows at a time, and halves them for the next. */
    private class Writer(private val out: FileHandle, image: TiffImage, factors: List<Int>, private val encode: (Pixels) -> ByteArray) {
        private inner class Lv(val width: Int, val height: Int, val factor: Int) {
            val across = ceil(width, TILE)
            val down = ceil(height, TILE)
            val offsets = LongArray(across * down)
            val lengths = IntArray(across * down)
            val band = IntArray(TILE * width)
            var bandRows = 0
            var bandIndex = 0
            var rowsIn = 0
            var pending: IntArray? = null
        }

        private val imageWidth = image.width
        private val imageHeight = image.height
        private val levels = factors.map { f -> Lv(ceil(image.width, f), ceil(image.height, f), f) }
        private val headerSize = 14 + levels.size * 28
        private var at = headerSize.toLong()

        fun row(level: Int, pixels: IntArray) {
            val l = levels[level]
            if (l.rowsIn >= l.height) return
            pixels.copyInto(l.band, l.bandRows * l.width, 0, l.width)
            l.bandRows++
            l.rowsIn++
            if (l.bandRows == TILE || l.rowsIn == l.height) flush(l)
            // Two rows of this level make one of the next.
            if (level + 1 < levels.size) {
                val p = l.pending
                if (p == null && l.rowsIn < l.height) {
                    l.pending = pixels.copyOf(l.width)
                } else {
                    l.pending = null
                    row(level + 1, halve(p ?: pixels, if (p == null) null else pixels, l.width))
                }
            }
        }

        private fun flush(l: Lv) {
            for (c in 0 until l.across) {
                val w = minOf(TILE, l.width - c * TILE)
                val px = IntArray(w * l.bandRows)
                for (y in 0 until l.bandRows) l.band.copyInto(px, y * w, y * l.width + c * TILE, y * l.width + c * TILE + w)
                val i = l.bandIndex * l.across + c
                if (px.any { it != 0 }) {
                    val bytes = encode(Pixels(w, l.bandRows, px))
                    out.write(at, bytes, 0, bytes.size)
                    l.offsets[i] = at
                    l.lengths[i] = bytes.size
                    at += bytes.size
                }
            }
            l.bandRows = 0
            l.bandIndex++
        }

        /** The index and the header, once every row is in. */
        fun close() {
            val indexAt = LongArray(levels.size)
            for ((k, l) in levels.withIndex()) {
                if (l.bandRows > 0) flush(l)
                indexAt[k] = at
                val b = ByteArray(l.offsets.size * 12)
                for (i in l.offsets.indices) {
                    putLong(b, i * 12, l.offsets[i])
                    putInt(b, i * 12 + 8, l.lengths[i])
                }
                out.write(at, b, 0, b.size)
                at += b.size
            }
            val h = ByteArray(headerSize)
            MAGIC.encodeToByteArray().copyInto(h)
            h[4] = VERSION.toByte()
            h[5] = levels.size.toByte()
            for ((k, l) in levels.withIndex()) {
                val p = 14 + k * 28
                putInt(h, p, l.width)
                putInt(h, p + 4, l.height)
                putInt(h, p + 8, l.factor)
                putInt(h, p + 12, l.across)
                putInt(h, p + 16, l.down)
                putLong(h, p + 20, indexAt[k])
            }
            putInt(h, 6, imageWidth)
            putInt(h, 10, imageHeight)
            out.write(0, h, 0, h.size)
            out.flush()
        }

        /** Two rows (the second may be missing: the last) to one of half the width, 2×2 averaged. */
        private fun halve(a: IntArray, b: IntArray?, width: Int): IntArray {
            val w = ceil(width, 2)
            return IntArray(w) { c ->
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                fun add(p: Int) {
                    val alpha = p ushr 24
                    sa += alpha
                    sr += ((p shr 16) and 0xFF) * alpha
                    sg += ((p shr 8) and 0xFF) * alpha
                    sb += (p and 0xFF) * alpha
                    n++
                }
                for (x in 2 * c until minOf(width, 2 * c + 2)) {
                    add(a[x])
                    if (b != null) add(b[x])
                }
                if (sa == 0) 0 else ((sa / n).coerceIn(1, 255) shl 24) or ((sr / sa) shl 16) or ((sg / sa) shl 8) or (sb / sa)
            }
        }

        private fun putInt(b: ByteArray, p: Int, v: Int) {
            for (i in 0 until 4) b[p + i] = (v shr (8 * i)).toByte()
        }

        private fun putLong(b: ByteArray, p: Int, v: Long) {
            for (i in 0 until 8) b[p + i] = (v shr (8 * i)).toByte()
        }

        private fun ceil(a: Int, b: Int) = (a + b - 1) / b
    }
}
