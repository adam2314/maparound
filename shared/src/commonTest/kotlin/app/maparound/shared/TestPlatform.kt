package app.maparound.shared

import okio.FileHandle

/** Environment variable (on the iOS simulator, set as SIMCTL_CHILD_<name> by the caller). */
expect fun testEnv(name: String): String?

expect fun openTestFile(path: String): FileHandle
