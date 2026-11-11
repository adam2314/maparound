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
import java.util.Locale
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

    fun latLon(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

    fun dms(lat: Double, lon: Double): String = "${dmsPart(lat, "N", "S")}  ${dmsPart(lon, "E", "W")}"

    private fun dmsPart(value: Double, pos: String, neg: String): String {
        val a = abs(value)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return String.format(Locale.US, "%d°%02d′%04.1f″%s", d, m, s, if (value >= 0) pos else neg)
    }

    fun distance(meters: Float): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        meters < 100_000 -> String.format(Locale.getDefault(), "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }

    fun oneDecimal(value: Number): String = String.format(Locale.getDefault(), "%.1f", value.toDouble())

    /** 754 s -> "13 min", 15000 s -> "4 h 10 min". */
    fun duration(seconds: Long): String {
        val minutes = (seconds + 30) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** Running clock for recordings: "1:05:09". */
    fun clock(seconds: Long): String =
        String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
