package app.maparound.shared

import okio.FileSystem

/** The device's file system (Okio's system file system on Android and iOS). */
expect val fileSystem: FileSystem

/** The platform's folder for temporary files. */
expect val tempDir: String

/**
 * A file mapped read-only into memory: no heap copy, and safe to read from several threads.
 * Elevation tiles are up to 26 MB each, so they are read this way.
 */
interface MappedFile {
    val size: Long

    /** The big-endian 16-bit value at byte offset [pos]. */
    fun shortBE(pos: Long): Short
}

/** @throws Exception when the file can't be opened or mapped. */
expect fun mapFile(path: String): MappedFile
