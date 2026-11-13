package app.maparound.shared.crypto

import app.maparound.shared.toByteArray

// The party's encryption needs AES and HMAC as blocking calls; the browser's Web Crypto only has
// asynchronous ones, so they're plain Kotlin here (PureCrypto, checked against the platforms'
// in the tests). Random bytes come from the browser's secure generator.

private fun jsRandomBytes(n: Int): JsAny = js("crypto.getRandomValues(new Uint8Array(n))")

actual fun randomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    var done = 0
    while (done < n) {
        // getRandomValues gives at most 64 KiB at a time.
        val chunk = jsRandomBytes(minOf(65536, n - done)).toByteArray()
        chunk.copyInto(out, done)
        done += chunk.size
    }
    return out
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = PureCrypto.hmacSha256(key, data)

actual fun aesEncryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray = PureCrypto.aesEncryptBlocks(key, blocks)

actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray = Gcm.seal(key, nonce, aad, plain)

actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = Gcm.open(key, nonce, aad, sealed)
