package app.maparound.shared.util

// URL form encoding with UTF-8, the same as java.net.URLEncoder / URLDecoder.

private const val HEX = "0123456789ABCDEF"

/** Letters, digits and `.-*_` stay; space becomes `+`; everything else `%XX` of its UTF-8 bytes. */
fun urlEncode(s: String): String = buildString {
    for (b in s.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> append(c.toChar())
            c == ' '.code -> append('+')
            else -> append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }
}

/**
 * `+` becomes a space and `%XX` sequences are UTF-8 bytes.
 * @throws IllegalArgumentException on a broken `%` sequence.
 */
fun urlDecode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when (c) {
            '+' -> {
                out.append(' ')
                i++
            }
            '%' -> {
                val bytes = ArrayList<Byte>()
                while (i < s.length && s[i] == '%') {
                    require(i + 2 < s.length) { "Incomplete escape in URL" }
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: throw IllegalArgumentException("Bad escape in URL")
                    bytes += v.toByte()
                    i += 3
                }
                out.append(bytes.toByteArray().decodeToString())
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}
