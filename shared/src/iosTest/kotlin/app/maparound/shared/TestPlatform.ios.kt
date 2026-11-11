package app.maparound.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.FileHandle
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun testEnv(name: String): String? = getenv(name)?.toKString()

actual fun openTestFile(path: String): FileHandle = FileSystem.SYSTEM.openReadOnly(path.toPath())
