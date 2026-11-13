package app.maparound.shared.util

/** The decimal separator of the phone's language ("." or ","), for numbers shown to people. */
expect fun localDecimalSeparator(): Char

/** The user's language, as a code ("en", "he"), for answers from online services. */
expect fun deviceLanguage(): String

/** [fixed] with the phone's decimal separator, like `String.format(Locale.getDefault(), "%.nf", v)`. */
fun localFixed(v: Double, decimals: Int): String {
    val s = fixed(v, decimals)
    val sep = localDecimalSeparator()
    return if (sep == '.') s else s.replace('.', sep)
}

/** [localFixed] with a sign always shown, like `"%+.nf"`. */
fun localSigned(v: Double, decimals: Int): String = (if (v >= 0) "+" else "") + localFixed(v, decimals)
