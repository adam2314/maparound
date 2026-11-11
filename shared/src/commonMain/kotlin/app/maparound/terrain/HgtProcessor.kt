package app.maparound.terrain

import app.maparound.shared.fileSystem
import app.maparound.shared.util.Bits
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.use

/**
 * Prepares downloaded elevation tiles for on-device use:
 *  - optional downsampling from 1 arc-second (3601², ~26 MB) to 3 arc-second (1201², ~2.9 MB);
 *  - flattening the sea floor to 0 m. The source data includes ocean depths, which would
 *    otherwise be hill-shaded and contoured in the sea (mapsforge maps paint sea below land,
 *    so the theme cannot mask it). Known land basins below sea level keep their real heights.
 */
object HgtProcessor {
    const val SIZE_1_ARCSEC = 3601
    const val SIZE_3_ARCSEC = 1201

    /** 1° cells where negative heights are dry land (or inland lakes), not sea floor. */
    private val BELOW_SEA_LEVEL_LAND = setOf(
        "N30E035", "N31E035", "N32E035",                       // Dead Sea, Jordan Valley, Sea of Galilee
        "N35W117", "N36W117", "N36W118",                       // Death Valley
        "N32W116", "N33W116", "N33W117",                       // Salton Sea, Imperial Valley
        "N29E026", "N29E027", "N29E028", "N30E026", "N30E027", // Qattara Depression
        "N13E040", "N13E041", "N14E040", "N14E041",            // Danakil Depression
        "N42E088", "N42E089",                                  // Turpan Depression
    )

    fun keepsNegativeHeights(lat: Int, lon: Int): Boolean = DemIndex.nameFor(lat, lon) in BELOW_SEA_LEVEL_LAND

    /** Samples per side the converted tile will have. */
    fun outputSize(srcSize: Int, highDetail: Boolean): Int =
        if (!highDetail && srcSize == SIZE_1_ARCSEC) SIZE_3_ARCSEC else srcSize

    /**
     * Converts a raw (uncompressed) big-endian .hgt stream into the file at [out].
     * @param srcSize samples per side of the input (3601 or 1201)
     * @param highDetail keep 1" resolution; otherwise a 3601 input is reduced to 1201.
     * @param seaMask optional sea mask over the *output* grid; masked samples become 0 m.
     */
    fun convert(
        input: Source, srcSize: Int, out: String, highDetail: Boolean, keepNegative: Boolean,
        seaMask: Bits? = null,
    ) {
        require(srcSize == SIZE_1_ARCSEC || srcSize == SIZE_3_ARCSEC) { "Unexpected tile size $srcSize" }
        val step = if (!highDetail && srcSize == SIZE_1_ARCSEC) 3 else 1
        val outSize = outputSize(srcSize, highDetail)
        val row = ByteArray(srcSize * 2)
        val outRow = ByteArray(((srcSize - 1) / step + 1) * 2)
        val data = input.buffer()
        fileSystem.sink(out.toPath()).buffer().use { sink ->
            for (r in 0 until srcSize) {
                data.readFully(row)
                if (r % step != 0) continue
                val maskRow = (r / step) * outSize
                var o = 0
                var c = 0
                while (c < srcSize) {
                    var hi = row[2 * c].toInt()
                    var lo = row[2 * c + 1].toInt()
                    val v = (hi shl 8) or (lo and 0xFF)
                    val isSea = seaMask != null && seaMask[maskRow + c / step]
                    if (isSea || (!keepNegative && v < 0 && v != HgtTile.VOID)) {
                        hi = 0
                        lo = 0
                    }
                    outRow[o++] = hi.toByte()
                    outRow[o++] = lo.toByte()
                    c += step
                }
                sink.write(outRow, 0, o)
            }
        }
    }

    /** Samples per side for a raw .hgt of [bytes] length, or null if it isn't one. */
    fun sizeForLength(bytes: Long): Int? = when (bytes) {
        SIZE_1_ARCSEC.toLong() * SIZE_1_ARCSEC * 2 -> SIZE_1_ARCSEC
        SIZE_3_ARCSEC.toLong() * SIZE_3_ARCSEC * 2 -> SIZE_3_ARCSEC
        else -> null
    }
}
