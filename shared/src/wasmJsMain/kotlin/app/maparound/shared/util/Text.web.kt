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

/**
 * The zone's offset in seconds by the browser's Intl ("GMT+05:30"), its formatters kept; NaN when
 * the browser doesn't know the zone. A null zone: the browser's own.
 */
private fun jsZoneOffset(zone: String?, ms: Double): Double = js("""(function () {
    var d = new Date(ms);
    if (zone == null) return -d.getTimezoneOffset() * 60;
    try {
        var cache = globalThis.__maZones || (globalThis.__maZones = {});
        var f = cache[zone] || (cache[zone] = new Intl.DateTimeFormat('en-US', { timeZone: zone, timeZoneName: 'longOffset' }));
        var parts = f.formatToParts(d), name = '';
        for (var i = 0; i < parts.length; i++) if (parts[i].type === 'timeZoneName') name = parts[i].value;
        var m = /^GMT(?:([+−-])(\d{1,2})(?::?(\d{2}))?(?::?(\d{2}))?)?$/.exec(name);
        if (!m) return NaN;
        if (!m[1]) return 0;
        var s = (+m[2]) * 3600 + (+(m[3] || 0)) * 60 + (+(m[4] || 0));
        return m[1] === '+' ? s : -s;
    } catch (e) { return NaN; }
})()""")

/**
 * [zone]'s offset from UTC at [epochMillis], in seconds ([zone]: an IANA name such as
 * "Asia/Jerusalem"; null: the browser's own), by the browser's time-zone rules; null when it
 * doesn't know the zone. For the data view's time zones (kotlinx-datetime has no zones here).
 */
fun browserZoneOffsetSeconds(zone: String?, epochMillis: Long): Int? =
    jsZoneOffset(zone, epochMillis.toDouble()).takeUnless { it.isNaN() }?.toInt()
