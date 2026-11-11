@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package app.maparound.shared.util

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

actual fun nfd(s: String): String = NSString.create(string = s).decomposedStringWithCanonicalMapping
