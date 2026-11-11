package app.maparound.shared.util

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCanonicalMapping

actual fun nfd(s: String): String = NSString.create(string = s).decomposedStringWithCanonicalMapping
