package app.maparound.shared.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The web app's crypto (plain Kotlin) against the standards' test vectors and the platform's own. */
class PureCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha256Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", PureCrypto.sha256(ByteArray(0)).hex())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PureCrypto.sha256("abc".encodeToByteArray()).hex())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            PureCrypto.sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            PureCrypto.sha256(ByteArray(1_000_000) { 'a'.code.toByte() }).hex(),
        )
    }

    @Test
    fun hmacVectors() {
        // RFC 4231, test cases 1, 2 and 6 (a key longer than a block).
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            PureCrypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).hex(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            PureCrypto.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex(),
        )
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            PureCrypto.hmacSha256(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun hmacMatchesThePlatform() {
        for (len in listOf(0, 1, 31, 32, 55, 56, 63, 64, 65, 119, 120, 200)) {
            val key = randomBytes(if (len % 2 == 0) 32 else 100)
            val data = randomBytes(len)
            assertContentEquals(hmacSha256(key, data), PureCrypto.hmacSha256(key, data), "length $len")
        }
    }

    @Test
    fun aesVectors() {
        // FIPS-197, appendix C: AES-128, AES-192, AES-256.
        val plain = hex("00112233445566778899aabbccddeeff")
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", PureCrypto.aesEncryptBlocks(hex("000102030405060708090a0b0c0d0e0f"), plain).hex())
        assertEquals(
            "dda97ca4864cdfe06eaf70a0ec0d7191",
            PureCrypto.aesEncryptBlocks(hex("000102030405060708090a0b0c0d0e0f1011121314151617"), plain).hex(),
        )
        assertEquals(
            "8ea2b7ca516745bfeafc49904b496089",
            PureCrypto.aesEncryptBlocks(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), plain).hex(),
        )
    }

    @Test
    fun aesAndGcmMatchThePlatform() {
        for (keySize in listOf(16, 32)) {
            val key = randomBytes(keySize)
            val blocks = randomBytes(16 * 7)
            assertContentEquals(aesEncryptBlocks(key, blocks), PureCrypto.aesEncryptBlocks(key, blocks))
        }
        // The party's packets: AES-256-GCM, sealed by the platform, opened with the plain Kotlin AES.
        val key = randomBytes(32)
        for (len in listOf(0, 1, 15, 16, 17, 60, 200)) {
            val nonce = randomBytes(12)
            val aad = randomBytes(18)
            val plain = randomBytes(len)
            val sealed = aesGcmSeal(key, nonce, aad, plain)
            assertContentEquals(sealed, Gcm.seal(key, nonce, aad, plain, PureCrypto::aesEncryptBlocks))
            assertContentEquals(plain, Gcm.open(key, nonce, aad, sealed, PureCrypto::aesEncryptBlocks))
        }
    }
}
