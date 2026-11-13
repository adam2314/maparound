package app.maparound.shared.util

import java.text.DecimalFormatSymbols

actual fun localDecimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator

actual fun deviceLanguage(): String = java.util.Locale.getDefault().language.ifEmpty { "en" }
