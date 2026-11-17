package app.maparound.shared.util

import java.text.DecimalFormatSymbols

actual fun localDecimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator

// The language tag has today's codes ("he"); Locale.language still says "iw" on Android.
actual fun deviceLanguage(): String =
    java.util.Locale.getDefault().toLanguageTag().substringBefore('-').takeIf { it.isNotEmpty() && it != "und" } ?: "en"

actual fun deviceRegion(): String = java.util.Locale.getDefault().country.orEmpty()

/**
 * The phone's own 12/24-hour setting, when the app has said how to read it (it needs a Context:
 * `android.text.format.DateFormat.is24HourFormat`); else what the language's short time has.
 */
var deviceClock24h: (() -> Boolean)? = null

actual fun deviceUses24h(): Boolean = deviceClock24h?.invoke()
    ?: ((java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) as? java.text.SimpleDateFormat)?.toPattern()?.contains('a') != true)
