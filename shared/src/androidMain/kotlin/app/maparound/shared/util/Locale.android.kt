package app.maparound.shared.util

import java.text.DecimalFormatSymbols

actual fun localDecimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator

// The language tag has today's codes ("he"); Locale.language still says "iw" on Android.
actual fun deviceLanguage(): String =
    java.util.Locale.getDefault().toLanguageTag().substringBefore('-').takeIf { it.isNotEmpty() && it != "und" } ?: "en"
