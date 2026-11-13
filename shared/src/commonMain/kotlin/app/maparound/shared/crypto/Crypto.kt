package app.maparound.shared.crypto

// Cryptography from the platform: javax.crypto on Android, CommonCrypto and Security on iOS;
// plain Kotlin in the browser (PureCrypto).

/** HMAC-SHA-256 of [data] with [key] (32 bytes). */
expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/** [n] bytes from the platform's secure random generator. */
expect fun randomBytes(n: Int): ByteArray

/** Whole 16-byte blocks encrypted with AES, one by one (ECB): the building block of [Gcm]. */
expect fun aesEncryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray

/**
 * AES-GCM with a 12-byte [nonce]: the ciphertext followed by the 16-byte tag, the same bytes as
 * javax.crypto's "AES/GCM/NoPadding".
 */
expect fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray

/** The plain text, or null when the tag doesn't match (wrong key, damaged or forged data). */
expect fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?

/**
 * AES-GCM (NIST SP 800-38D) on top of [aesEncryptBlocks], for platforms whose public API has AES
 * but not GCM (iOS CommonCrypto). 12-byte nonces and 16-byte tags only.
 */
object Gcm {
    fun seal(
        key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray,
        aes: (ByteArray, ByteArray) -> ByteArray = ::aesEncryptBlocks,
    ): ByteArray {
        val k = keystream(key, nonce, plain.size, aes)
        val c = ByteArray(plain.size) { (plain[it].toInt() xor k[32 + it].toInt()).toByte() }
        return c + tag(k, aad, c)
    }

    fun open(
        key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray,
        aes: (ByteArray, ByteArray) -> ByteArray = ::aesEncryptBlocks,
    ): ByteArray? {
        if (sealed.size < 16) return null
        val c = sealed.copyOf(sealed.size - 16)
        val k = keystream(key, nonce, c.size, aes)
        val expected = tag(k, aad, c)
        var diff = 0
        for (i in 0 until 16) diff = diff or (expected[i].toInt() xor sealed[c.size + i].toInt())
        if (diff != 0) return null
        return ByteArray(c.size) { (c[it].toInt() xor k[32 + it].toInt()).toByte() }
    }

    /** One AES call for H = E(0), E(J0) and the counter blocks: H | E(J0) | keystream. */
    private fun keystream(key: ByteArray, nonce: ByteArray, length: Int, aes: (ByteArray, ByteArray) -> ByteArray): ByteArray {
        require(nonce.size == 12) { "GCM nonce must be 12 bytes" }
        val blocks = (length + 15) / 16
        val input = ByteArray(32 + blocks * 16)
        // Block 1: J0 = nonce | 00000001; counter blocks: nonce | 2, 3, …
        for (b in 0..blocks) {
            val at = 16 + b * 16
            nonce.copyInto(input, at)
            val ctr = 1 + b
            input[at + 12] = (ctr ushr 24).toByte()
            input[at + 13] = (ctr ushr 16).toByte()
            input[at + 14] = (ctr ushr 8).toByte()
            input[at + 15] = ctr.toByte()
        }
        return aes(key, input)
    }

    private fun tag(k: ByteArray, aad: ByteArray, c: ByteArray): ByteArray {
        val hh = long(k, 0)
        val hl = long(k, 8)
        var yh = 0L
        var yl = 0L
        fun absorb(data: ByteArray) {
            var i = 0
            while (i < data.size) {
                val block = ByteArray(16)
                data.copyInto(block, 0, i, minOf(i + 16, data.size))
                yh = yh xor long(block, 0)
                yl = yl xor long(block, 8)
                val r = mul(yh, yl, hh, hl)
                yh = r[0]
                yl = r[1]
                i += 16
            }
        }
        absorb(aad)
        absorb(c)
        yh = yh xor (aad.size.toLong() * 8)
        yl = yl xor (c.size.toLong() * 8)
        val r = mul(yh, yl, hh, hl)
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = ((r[0] ushr (56 - 8 * i)).toInt() xor k[16 + i].toInt()).toByte()
            out[8 + i] = ((r[1] ushr (56 - 8 * i)).toInt() xor k[24 + i].toInt()).toByte()
        }
        return out
    }

    /** Multiplication in GF(2^128) with GCM's bit order, without branches on secret data. */
    private fun mul(xh: Long, xl: Long, yh: Long, yl: Long): LongArray {
        var zh = 0L
        var zl = 0L
        var vh = yh
        var vl = yl
        for (i in 0 until 128) {
            val bit = if (i < 64) (xh ushr (63 - i)) and 1L else (xl ushr (127 - i)) and 1L
            val m = -bit
            zh = zh xor (vh and m)
            zl = zl xor (vl and m)
            val lsb = vl and 1L
            vl = (vl ushr 1) or (vh shl 63)
            vh = (vh ushr 1) xor (-lsb and (0xE1L shl 56))
        }
        return longArrayOf(zh, zl)
    }

    private fun long(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }
}
