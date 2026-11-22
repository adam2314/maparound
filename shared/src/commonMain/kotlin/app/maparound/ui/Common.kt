package app.maparound.ui

import app.maparound.i18n.DateOrder
import app.maparound.i18n.DirectionFormat
import app.maparound.i18n.DistanceUnit
import app.maparound.i18n.HeightUnit
import app.maparound.i18n.T
import app.maparound.i18n.TimeFormat
import app.maparound.i18n.Units
import app.maparound.i18n.ltr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.maparound.shared.util.fixed
import app.maparound.shared.util.localFixed
import app.maparound.shared.util.padded
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.TimeZone
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import app.maparound.shared.util.nowMillis
import kotlin.math.abs
import kotlin.math.roundToInt

/** The map's cards (a place, a country, the offline maps' offer) are no wider than this on a big screen. */
val CARD_MAX_WIDTH = 560.dp

/** Centered explanation card, e.g. "no barometer" or "permission needed". */
@Composable
fun MessageScreen(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        MessageCard(icon, title, body, action, onAction)
    }
}

@Composable
fun MessageCard(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f), textAlign = TextAlign.End)
    }
}

object Fmt {
    /** The compass point of a direction in degrees, in the app's language ("NNE"; Hebrew: the eight main ones). */
    fun cardinal(deg: Float): String = T.common.cardinal(deg)

    /**
     * A direction as the user chose to see directions: "NNE", "23°", "NNE 23°" or "409 mil"
     * (6400 to a circle).
     */
    fun heading(deg: Float, format: DirectionFormat = Units.current.direction): String {
        val d = (deg % 360f + 360f) % 360f
        val degrees = ltr("${d.roundToInt() % 360}°")
        return when (format) {
            DirectionFormat.COMPASS -> cardinal(d)
            DirectionFormat.DEGREES -> degrees
            DirectionFormat.BOTH -> "${cardinal(d)} $degrees"
            DirectionFormat.MILS -> ltr("${(d * 6400f / 360f).roundToInt() % 6400} ${T.common.unitMil}")
        }
    }

    /**
     * A bearing as the compass shows it, to follow it with one: "123°", or "2187 mil" when
     * directions are in mils (never compass points, which a compass can't be set to).
     */
    fun compass(deg: Float): String =
        heading(deg, if (Units.current.direction == DirectionFormat.MILS) DirectionFormat.MILS else DirectionFormat.DEGREES)

    fun latLon(lat: Double, lon: Double): String = "${fixed(lat, 5)}, ${fixed(lon, 5)}"

    /**
     * A point as the user chose to see coordinates (Settings → Units & formats: degrees, UTM, MGRS,
     * a national grid, a Plus Code…), in degrees where that format doesn't reach (another
     * country's grid).
     */
    fun point(lat: Double, lon: Double): String =
        app.maparound.tools.Coords.format(app.maparound.routing.GeoPoint(lat, lon), Units.current.coords) ?: latLon(lat, lon)

    fun dms(lat: Double, lon: Double): String = "${dmsPart(lat, "N", "S")}  ${dmsPart(lon, "E", "W")}"

    private fun dmsPart(value: Double, pos: String, neg: String): String {
        val a = abs(value)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return "$d°${padded(m, 2)}′${fixed(s, 1).padStart(4, PAD)}″${if (value >= 0) pos else neg}"
    }

    /**
     * A distance in the units chosen: "850 m", "4.2 km", "120 km"; "0.4 mi", "500 ft" (under a
     * tenth of a mile, in the height unit); "2.1 NM".
     */
    fun distance(meters: Float): String = when (Units.current.distance) {
        DistanceUnit.METRIC -> if (meters < 1000) "${meters.roundToInt()} ${T.common.unitM}" else long(meters / 1000.0, T.common.unitKm)
        DistanceUnit.IMPERIAL -> if (meters < MILE_M / 10) height(meters) else long(meters / MILE_M, T.common.unitMi)
        DistanceUnit.NAUTICAL -> if (meters < NAUTICAL_MILE_M / 10) height(meters) else long(meters / NAUTICAL_MILE_M, T.common.unitNm)
    }

    private fun long(v: Double, unit: String): String = (if (v < 100) localFixed(v, 1) else v.roundToInt().toString()) + " " + unit

    /** A height, or a length close by (how exact a position is): "812 m" or "2664 ft", as chosen. */
    fun height(meters: Number): String = "${heightNumber(meters.toDouble())} $heightUnit"

    /** A height in the unit chosen, rounded: 812 (m) or 2664 (ft). */
    fun heightNumber(meters: Double): Int = when (Units.current.height) {
        HeightUnit.M -> meters.roundToInt()
        HeightUnit.FT -> (meters / FOOT_M).roundToInt()
    }

    /** The height unit's symbol ("m", "ft"). */
    val heightUnit: String get() = if (Units.current.height == HeightUnit.FT) T.common.unitFt else T.common.unitM

    /** A speed in the unit chosen: "12 km/h". */
    fun speed(mps: Double): String {
        val u = Units.current.speed
        return "${(mps * u.perMps).roundToInt()} ${u.label}"
    }

    const val MILE_M = 1609.344
    const val NAUTICAL_MILE_M = 1852.0
    const val FOOT_M = 0.3048

    fun oneDecimal(value: Number): String = localFixed(value.toDouble(), 1)

    /** 754 s -> "13 min", 15000 s -> "4 h 10 min". */
    fun duration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        val t = T.common
        return if (minutes < 60) "$minutes ${t.unitMin}" else "${minutes / 60} ${t.unitH} ${minutes % 60} ${t.unitMin}"
    }

    /** "830 B", "4.2 kB", "530 MB", "1.2 GB": decimal units, like Android's short file sizes. */
    fun fileSize(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        val units = listOf("kB", "MB", "GB", "TB")
        var v = bytes / 1000.0
        var u = 0
        while (v >= 999.5 && u < units.lastIndex) {
            v /= 1000.0
            u++
        }
        return (if (v < 10) localFixed(v, 1) else localFixed(v, 0)) + " " + units[u]
    }

    /** "Just now", "5 min ago", "3 hours ago", "Yesterday", "4 days ago", "3 Nov", "3 Nov 2024" (local time). */
    fun relativeTime(timeMs: Long, nowMs: Long = nowMillis()): String {
        val ago = nowMs - timeMs
        val zone = TimeZone.currentSystemDefault()
        val then = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(zone)
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val days = then.date.daysUntil(today)
        val t = T.common
        return when {
            ago < 0 -> day(then.date, withYear = true)
            ago < 60_000 -> t.justNow
            ago < 3_600_000 -> t.minutesAgo(ago / 60_000)
            days == 0 -> t.hoursAgo(ago / 3_600_000)
            days == 1 -> t.yesterday
            days < 7 -> t.daysAgo(days)
            else -> day(then.date, withYear = then.year != today.year)
        }
    }

    /** "17 Nov" this year, "17 Nov 2025" otherwise (local time; in the date order chosen). */
    fun date(timeMs: Long, nowMs: Long = nowMillis()): String {
        val zone = TimeZone.currentSystemDefault()
        val d = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(zone).date
        val thisYear = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).year
        return day(d, withYear = d.year != thisYear)
    }

    /** "11 Nov" (local time), as route names use. */
    fun dayMonth(timeMs: Long): String =
        day(Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.currentSystemDefault()).date, withYear = false)

    /** "11 Nov 14:05" (local time). */
    fun dayMonthTime(timeMs: Long): String {
        val d = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return day(d.date, withYear = false) + " " + clock(d.hour, d.minute, null)
    }

    /**
     * When something happened: "14:05" today ("14:05:09" with [seconds]), "11 Nov 14:05" another
     * day (local time).
     */
    fun moment(timeMs: Long, seconds: Boolean = false, nowMs: Long = nowMillis()): String {
        val zone = TimeZone.currentSystemDefault()
        val d = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(zone)
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        return if (d.date == today) clock(d.hour, d.minute, d.second.takeIf { seconds }) else dayMonthTime(timeMs)
    }

    /** "14:05" or "2:05 PM" (local time). */
    fun timeOfDay(timeMs: Long): String {
        val d = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(TimeZone.currentSystemDefault())
        return clock(d.hour, d.minute, null)
    }

    /** A time of day as chosen: "14:05", "14:05:09", "2:05 PM". */
    fun clock(hour: Int, minute: Int, second: Int?, format: TimeFormat = Units.current.time): String {
        val rest = ":${padded(minute, 2)}" + (second?.let { ":${padded(it, 2)}" } ?: "")
        return when (format) {
            TimeFormat.H24 -> "${padded(hour, 2)}$rest"
            TimeFormat.H12 -> T.common.timeAmPm("${(hour + 11) % 12 + 1}$rest", am = hour < 12)
        }
    }

    /** A day in the order chosen: "17 Nov (2026)", "Nov 17(, 2026)" or "2026-11-17". */
    fun day(d: LocalDate, withYear: Boolean, order: DateOrder = Units.current.date): String {
        val t = T.common
        val month = t.months[d.monthNumber - 1]
        return when (order) {
            DateOrder.ISO -> ltr("${d.year}-${padded(d.monthNumber, 2)}-${padded(d.dayOfMonth, 2)}")
            DateOrder.MONTH_DAY -> if (withYear) t.monthDayYear(month, d.dayOfMonth, d.year) else t.monthDay(month, d.dayOfMonth)
            DateOrder.DAY_MONTH -> if (withYear) t.dayMonthYear(d.dayOfMonth, month, d.year) else t.dayMonth(d.dayOfMonth, month)
        }
    }

    /** Running clock for recordings: "1:05:09". */
    fun clock(seconds: Long): String = "${seconds / 3600}:${padded((seconds / 60) % 60, 2)}:${padded(seconds % 60, 2)}"

    private const val PAD = '0'
}
