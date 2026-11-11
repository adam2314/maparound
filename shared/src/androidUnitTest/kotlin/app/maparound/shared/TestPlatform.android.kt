package app.maparound.shared

import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toPath

actual fun testEnv(name: String): String? = System.getenv(name)

actual fun openTestFile(path: String): FileHandle = FileSystem.SYSTEM.openReadOnly(path.toPath())
