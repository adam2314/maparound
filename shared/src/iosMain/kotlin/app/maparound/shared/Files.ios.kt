package app.maparound.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes

actual fun openFile(path: String): FileHandle = FileSystem.SYSTEM.openReadOnly(path.toPath())

/** A vector tile as NSData, for Swift. */
@OptIn(ExperimentalForeignApi::class)
fun MapTiles.tileData(z: Int, x: Int, y: Int): NSData {
    val bytes = tile(z, x, y)
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
}
