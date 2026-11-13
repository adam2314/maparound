package app.maparound.shared

import okio.Path

/**
 * A zlib stream of stored (uncompressed) blocks: valid for PNG, just bigger. The browser's own
 * compression is asynchronous only; these tiles are made in memory and never sent anywhere.
 */
actual fun zlibDeflate(data: ByteArray): ByteArray {
    val blocks = maxOf(1, (data.size + 65534) / 65535)
    val out = ByteArray(2 + data.size + blocks * 5 + 4)
    var o = 0
    out[o++] = 0x78
    out[o++] = 0x01
    var pos = 0
    for (b in 0 until blocks) {
        val n = minOf(65535, data.size - pos)
        out[o++] = if (b == blocks - 1) 1 else 0 // BFINAL, BTYPE 00 (stored)
        out[o++] = (n and 0xFF).toByte()
        out[o++] = (n shr 8).toByte()
        out[o++] = (n.inv() and 0xFF).toByte()
        out[o++] = ((n.inv() shr 8) and 0xFF).toByte()
        data.copyInto(out, o, pos, pos + n)
        o += n
        pos += n
    }
    // Adler-32 of the data.
    var a = 1L
    var s = 0L
    for (byte in data) {
        a = (a + (byte.toInt() and 0xFF)) % 65521
        s = (s + a) % 65521
    }
    val adler = (s shl 16) or a
    out[o++] = (adler shr 24).toByte()
    out[o++] = (adler shr 16).toByte()
    out[o++] = (adler shr 8).toByte()
    out[o] = adler.toByte()
    return out
}

/** Terrain downloads come to the web app in a later step. */
actual fun gunzipFile(gz: Path, out: Path): Unit =
    throw UnsupportedOperationException("Unpacking terrain comes to the web app in a later version")
