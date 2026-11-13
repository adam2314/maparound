package app.maparound.shared.crypto

// SHA-256, HMAC-SHA-256 and the AES block cipher in plain Kotlin, for the web app: the browser's
// Web Crypto only has asynchronous calls, and the party's code needs blocking ones. Android and
// iOS use their platform's (Crypto.kt); the tests check that both give the same bytes. (Table
// lookups make AES here not constant-time; the web app only decrypts what it receives.)

object PureCrypto {

    // ---- SHA-256 (FIPS 180-4) --------------------------------------------------------------------

    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).let { k -> IntArray(k.size) { k[it].toInt() } }

    private val H0 = longArrayOf(0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19)
        .let { h -> IntArray(h.size) { h[it].toInt() } }

    private fun rotr(x: Int, n: Int) = (x ushr n) or (x shl (32 - n))

    fun sha256(data: ByteArray): ByteArray {
        val h = H0.copyOf()
        // The message, a 1 bit, zeros, and its length in bits (64-bit big-endian), in 64-byte blocks.
        val padded = ByteArray((data.size + 9 + 63) / 64 * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        val bits = data.size.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (o in padded.indices step 64) {
            for (t in 0 until 16) w[t] = int(padded, o + 4 * t)
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val t1 = hh + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + ((e and f) xor (e.inv() and g)) + K[t] + w[t]
                val t2 = (rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + ((a and b) xor (a and c) xor (b and c))
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[4 * i + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    /** HMAC (RFC 2104) with SHA-256. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val k = (if (key.size > 64) sha256(key) else key).copyOf(64)
        val inner = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        return sha256(outer + sha256(inner + data))
    }

    // ---- AES (FIPS-197), encryption only: GCM ([Gcm]) needs no more ---------------------------------

    private fun xtime(a: Int) = ((a shl 1) xor (if (a and 0x80 != 0) 0x1b else 0)) and 0xff

    /** The S-box, from its definition: the inverse in GF(2⁸), then the affine map. */
    private val SBOX = IntArray(256).also { s ->
        val exp = IntArray(255)
        val log = IntArray(256)
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x xor xtime(x) // × 3, a generator
        }
        for (i in 0 until 256) {
            val inv = if (i == 0) 0 else exp[(255 - log[i]) % 255]
            var r = inv
            var b = inv
            repeat(4) {
                b = ((b shl 1) or (b ushr 7)) and 0xff
                r = r xor b
            }
            s[i] = r xor 0x63
        }
    }

    private fun subWord(w: Int) =
        (SBOX[w ushr 24] shl 24) or (SBOX[(w ushr 16) and 0xff] shl 16) or (SBOX[(w ushr 8) and 0xff] shl 8) or SBOX[w and 0xff]

    /** The round keys of a 16-, 24- or 32-byte key. */
    private fun expandKey(key: ByteArray): IntArray {
        val nk = key.size / 4
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES keys are 16, 24 or 32 bytes" }
        val w = IntArray(4 * (nk + 7))
        for (i in 0 until nk) w[i] = int(key, 4 * i)
        var rcon = 1
        for (i in nk until w.size) {
            var t = w[i - 1]
            if (i % nk == 0) {
                t = subWord((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                t = subWord(t)
            }
            w[i] = w[i - nk] xor t
        }
        return w
    }

    /** Whole 16-byte blocks, each encrypted on its own (ECB), as [aesEncryptBlocks]. */
    fun aesEncryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray {
        require(blocks.size % 16 == 0) { "AES works on whole 16-byte blocks" }
        val w = expandKey(key)
        val rounds = w.size / 4 - 1
        val out = ByteArray(blocks.size)
        val s = IntArray(16)
        val t = IntArray(16)
        for (o in blocks.indices step 16) {
            // The state column by column, as the bytes come.
            for (i in 0 until 16) s[i] = blocks[o + i].toInt() and 0xff
            addRoundKey(s, w, 0)
            for (round in 1..rounds) {
                // SubBytes and ShiftRows: row r moves r columns left.
                for (c in 0 until 4) for (r in 0 until 4) t[4 * c + r] = SBOX[s[4 * ((c + r) % 4) + r]]
                if (round < rounds) {
                    // MixColumns.
                    for (c in 0 until 4) {
                        val a0 = t[4 * c]; val a1 = t[4 * c + 1]; val a2 = t[4 * c + 2]; val a3 = t[4 * c + 3]
                        s[4 * c] = xtime(a0) xor (xtime(a1) xor a1) xor a2 xor a3
                        s[4 * c + 1] = a0 xor xtime(a1) xor (xtime(a2) xor a2) xor a3
                        s[4 * c + 2] = a0 xor a1 xor xtime(a2) xor (xtime(a3) xor a3)
                        s[4 * c + 3] = (xtime(a0) xor a0) xor a1 xor a2 xor xtime(a3)
                    }
                } else {
                    t.copyInto(s)
                }
                addRoundKey(s, w, round)
            }
            for (i in 0 until 16) out[o + i] = s[i].toByte()
        }
        return out
    }

    private fun addRoundKey(s: IntArray, w: IntArray, round: Int) {
        for (c in 0 until 4) {
            val k = w[4 * round + c]
            s[4 * c] = s[4 * c] xor (k ushr 24)
            s[4 * c + 1] = s[4 * c + 1] xor ((k ushr 16) and 0xff)
            s[4 * c + 2] = s[4 * c + 2] xor ((k ushr 8) and 0xff)
            s[4 * c + 3] = s[4 * c + 3] xor (k and 0xff)
        }
    }

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
}
