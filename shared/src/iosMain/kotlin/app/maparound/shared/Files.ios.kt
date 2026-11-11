@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.shared

import app.maparound.map.maplibre.TileService
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.posix.memcpy

actual fun openFile(path: String): FileHandle = FileSystem.SYSTEM.openReadOnly(path.toPath())

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** A tile server answer for Swift. */
class TileData(val data: NSData, val contentType: String)

/** [TileService.respond] with the bytes as NSData (for TileServer.swift). */
fun TileService.respondData(path: String): TileData? = respond(path)?.let { TileData(it.bytes.toNSData(), it.contentType) }
