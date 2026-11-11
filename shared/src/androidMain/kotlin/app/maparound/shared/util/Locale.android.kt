package app.maparound.shared.util

import java.text.DecimalFormatSymbols

actual fun localDecimalSeparator(): Char = DecimalFormatSymbols.getInstance().decimalSeparator
