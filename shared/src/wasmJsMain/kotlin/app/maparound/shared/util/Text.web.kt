package app.maparound.shared.util

private fun jsNfd(s: String): String = js("s.normalize('NFD')")

private fun jsDecimalSeparator(): String = js("(1.5).toLocaleString().charAt(1)")

actual fun nfd(s: String): String = jsNfd(s)

actual fun localDecimalSeparator(): Char = jsDecimalSeparator().firstOrNull()?.takeIf { it == ',' || it == '.' } ?: '.'

private fun jsLanguage(): String = js("(navigator.language || 'en').split('-')[0]")

actual fun deviceLanguage(): String = jsLanguage().ifEmpty { "en" }

private fun jsRegion(): String = js("(function () { try { return new Intl.Locale(navigator.language || 'en').maximize().region || ''; } catch (e) { return ''; } })()")

actual fun deviceRegion(): String = jsRegion()

private fun js24h(): Boolean = js("(function () { try { var c = new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions(); return c.hourCycle ? (c.hourCycle === 'h23' || c.hourCycle === 'h24') : !c.hour12; } catch (e) { return true; } })()")

actual fun deviceUses24h(): Boolean = js24h()
