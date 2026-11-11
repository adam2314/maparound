package app.maparound.shared.util

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator

actual fun localDecimalSeparator(): Char = NSLocale.currentLocale.decimalSeparator.firstOrNull() ?: '.'
