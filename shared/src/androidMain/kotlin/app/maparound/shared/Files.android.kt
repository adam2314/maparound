package app.maparound.shared

import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toPath

actual fun openFile(path: String): FileHandle = FileSystem.SYSTEM.openReadOnly(path.toPath())
