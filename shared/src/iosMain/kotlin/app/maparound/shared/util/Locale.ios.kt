package app.maparound.shared.util

import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.decimalSeparator
import platform.Foundation.preferredLanguages

actual fun localDecimalSeparator(): Char = NSLocale.currentLocale.decimalSeparator.firstOrNull() ?: '.'

actual fun deviceLanguage(): String =
    (NSLocale.preferredLanguages.firstOrNull() as? String)?.substringBefore('-')?.ifEmpty { null } ?: "en"

actual fun deviceRegion(): String = NSLocale.currentLocale.countryCode.orEmpty()

/** The short time's pattern for the device's settings has "a" (AM/PM) when it's in 12 hours. */
actual fun deviceUses24h(): Boolean =
    NSDateFormatter.dateFormatFromTemplate("j", 0uL, NSLocale.currentLocale)?.contains('a') != true
