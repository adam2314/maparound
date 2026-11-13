package app.maparound.shared.util

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator
import platform.Foundation.preferredLanguages

actual fun localDecimalSeparator(): Char = NSLocale.currentLocale.decimalSeparator.firstOrNull() ?: '.'

actual fun deviceLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-')?.ifEmpty { null } ?: "en"
