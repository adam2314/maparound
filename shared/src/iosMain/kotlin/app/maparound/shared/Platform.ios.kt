@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.shared

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import okio.FileSystem
import platform.Foundation.NSTemporaryDirectory
import platform.posix.MAP_PRIVATE
import platform.posix.O_RDONLY
import platform.posix.PROT_READ
import platform.posix.close
import platform.posix.fstat
import platform.posix.mmap
import platform.posix.open
import platform.posix.stat

actual val fileSystem: FileSystem = FileSystem.SYSTEM

actual val tempDir: String = NSTemporaryDirectory()

/** Stays mapped for the life of the app, like a memory-mapped buffer on Android. */
private class PosixMappedFile(private val base: CPointer<ByteVar>, override val size: Long) : MappedFile {
    override fun shortBE(pos: Long): Short {
        require(pos >= 0 && pos + 1 < size) { "Offset $pos outside the file" }
        val hi = base[pos].toInt() and 0xFF
        val lo = base[pos + 1].toInt() and 0xFF
        return ((hi shl 8) or lo).toShort()
    }
}

actual fun mapFile(path: String): MappedFile {
    val fd = open(path, O_RDONLY)
    check(fd >= 0) { "Cannot open $path" }
    try {
        val size = memScoped {
            val st = alloc<stat>()
            check(fstat(fd, st.ptr) == 0) { "Cannot read the size of $path" }
            st.st_size
        }
        require(size > 0) { "Empty file: $path" }
        val p = mmap(null, size.convert(), PROT_READ, MAP_PRIVATE, fd, 0)
        check(p != null && p.toLong() != -1L) { "Cannot map $path" } // MAP_FAILED is (void *) -1
        return PosixMappedFile(p.reinterpret(), size)
    } finally {
        close(fd)
    }
}
