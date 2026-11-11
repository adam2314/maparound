@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.shared.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

// CommonCrypto has HMAC and AES in its public API, but GCM only in private headers, so GCM is
// built on AES blocks in common code (Gcm), checked against NIST test vectors.

/** Pinning needs at least one element; lengths are passed separately. */
private fun ByteArray.pinnable() = if (isEmpty()) ByteArray(1) else this

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(32)
    key.pinnable().usePinned { k ->
        data.pinnable().usePinned { d ->
            out.usePinned { o ->
                CCHmac(kCCHmacAlgSHA256.convert(), k.addressOf(0), key.size.convert(), d.addressOf(0), data.size.convert(), o.addressOf(0))
            }
        }
    }
    return out
}

actual fun randomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
    check(status == 0) { "No secure random bytes ($status)" }
    return out
}

actual fun aesEncryptBlocks(key: ByteArray, blocks: ByteArray): ByteArray {
    require(blocks.size % 16 == 0) { "AES blocks must be 16 bytes" }
    if (blocks.isEmpty()) return ByteArray(0)
    val out = ByteArray(blocks.size)
    memScoped {
        val moved = alloc<size_tVar>()
        val status = key.usePinned { k ->
            blocks.usePinned { b ->
                out.usePinned { o ->
                    CCCrypt(
                        kCCEncrypt.convert(), kCCAlgorithmAES.convert(), kCCOptionECBMode.convert(),
                        k.addressOf(0), key.size.convert(), null,
                        b.addressOf(0), blocks.size.convert(), o.addressOf(0), out.size.convert(), moved.ptr,
                    )
                }
            }
        }
        check(status.toInt() == kCCSuccess.toInt()) { "AES failed ($status)" }
    }
    return out
}

actual fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
    Gcm.seal(key, nonce, aad, plain)

actual fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
    Gcm.open(key, nonce, aad, sealed)
