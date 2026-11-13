package app.maparound.shared.crypto

import app.maparound.shared.toByteArray

// The party's encryption needs AES and HMAC as blocking calls; the browser's Web Crypto only has
// asynchronous ones. The party isn't in the web app yet (its tab is hidden), so these say so.

private const val LATER = "The party comes to the web app in a later version"

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

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = throw UnsupportedOperationException(LATER)

actual fun aesEncryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray = throw UnsupportedOperationException(LATER)

actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
    throw UnsupportedOperationException(LATER)

actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
    throw UnsupportedOperationException(LATER)
