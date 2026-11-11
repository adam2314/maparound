package app.maparound.ui

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
import app.maparound.shared.util.nowMillis
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private val compass16 = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    fun cardinal(deg: Float): String = compass16[(((deg % 360f + 360f) % 360f) / 22.5f + 0.5f).toInt() % 16]

    fun latLon(lat: Double, lon: Double): String = "${fixed(lat, 5)}, ${fixed(lon, 5)}"

    fun dms(lat: Double, lon: Double): String = "${dmsPart(lat, "N", "S")}  ${dmsPart(lon, "E", "W")}"

    private fun dmsPart(value: Double, pos: String, neg: String): String {
        val a = abs(value)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return "$d°${padded(m, 2)}′${fixed(s, 1).padStart(4, PAD)}″${if (value >= 0) pos else neg}"
    }

    fun distance(meters: Float): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        meters < 100_000 -> "${localFixed((meters / 1000).toDouble(), 1)} km"
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun oneDecimal(value: Number): String = localFixed(value.toDouble(), 1)

    /** 754 s -> "13 min", 15000 s -> "4 h 10 min". */
    fun duration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
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

    /** "Just now", "5 min ago", "3 hours ago", "Yesterday", "4 days ago", "Nov 3", "Nov 3, 2024" (local time). */
    fun relativeTime(timeMs: Long, nowMs: Long = nowMillis()): String {
        val ago = nowMs - timeMs
        val zone = TimeZone.currentSystemDefault()
        val then = Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(zone)
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val days = then.date.daysUntil(today)
        return when {
            ago < 0 -> "${MONTHS[then.monthNumber - 1]} ${then.dayOfMonth}, ${then.year}"
            ago < 60_000 -> "Just now"
            ago < 3_600_000 -> "${ago / 60_000} min ago"
            days == 0 -> (ago / 3_600_000).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
            days == 1 -> "Yesterday"
            days < 7 -> "$days days ago"
            then.year == today.year -> "${MONTHS[then.monthNumber - 1]} ${then.dayOfMonth}"
            else -> "${MONTHS[then.monthNumber - 1]} ${then.dayOfMonth}, ${then.year}"
        }
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Running clock for recordings: "1:05:09". */
    fun clock(seconds: Long): String = "${seconds / 3600}:${padded((seconds / 60) % 60, 2)}:${padded(seconds % 60, 2)}"

    private const val PAD = '0'
}
