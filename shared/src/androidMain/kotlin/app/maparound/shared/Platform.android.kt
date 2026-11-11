package app.maparound.shared

import okio.FileSystem
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

actual val fileSystem: FileSystem = FileSystem.SYSTEM

actual val tempDir: String = System.getProperty("java.io.tmpdir") ?: "/tmp"

private class JvmMappedFile(private val buf: MappedByteBuffer, override val size: Long) : MappedFile {
    // Absolute reads only, so concurrent readers never share a position.
    override fun shortBE(pos: Long): Short = buf.getShort(pos.toInt())
}

actual fun mapFile(path: String): MappedFile = RandomAccessFile(path, "r").use { raf ->
    val size = raf.length()
    require(size <= Int.MAX_VALUE) { "File too large to map: $path" }
    JvmMappedFile(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, size), size)
}
