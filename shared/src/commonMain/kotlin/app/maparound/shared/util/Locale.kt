package app.maparound.shared.util

/** The decimal separator of the phone's language ("." or ","), for numbers shown to people. */
expect fun localDecimalSeparator(): Char

/** [fixed] with the phone's decimal separator, like `String.format(Locale.getDefault(), "%.nf", v)`. */
fun localFixed(v: Double, decimals: Int): String {
    val s = fixed(v, decimals)
    val sep = localDecimalSeparator()
    return if (sep == '.') s else s.replace('.', sep)
}
