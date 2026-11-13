package app.maparound.shared.util

private fun jsNfd(s: String): String = js("s.normalize('NFD')")

private fun jsDecimalSeparator(): String = js("(1.5).toLocaleString().charAt(1)")

actual fun nfd(s: String): String = jsNfd(s)

actual fun localDecimalSeparator(): Char = jsDecimalSeparator().firstOrNull()?.takeIf { it == ',' || it == '.' } ?: '.'

private fun jsLanguage(): String = js("(navigator.language || 'en').split('-')[0]")

actual fun deviceLanguage(): String = jsLanguage().ifEmpty { "en" }
