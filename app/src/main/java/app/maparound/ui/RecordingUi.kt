package app.maparound.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.maparound.tracks.TrackRecorder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Returns an action that starts GPS recording, asking for the needed permissions first. */
@Composable
fun rememberRecordingStarter(hasLocation: Boolean, requestLocation: () -> Unit): () -> Unit {
    val context = LocalContext.current
    // The recording notification needs this on Android 13+; recording works either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TrackRecorder.start(context)
    }
    return {
        when {
            !hasLocation -> requestLocation()
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> TrackRecorder.start(context)
        }
    }
}

/** "● 0:42:10 · 3.2 km · ↑ 210 m   Stop" */
@Composable
fun RecordingBar(state: TrackRecorder.State, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    val elapsed = state.startedAt?.let { (now - it) / 1000 } ?: 0
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(10.dp).background(Color(0xFFD50000), CircleShape))
            Text(Fmt.clock(elapsed), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            if (state.points.isEmpty()) {
                Text(
                    state.accuracyM?.let { "GPS ±${it.roundToInt()} m…" } ?: "Waiting for GPS…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(Fmt.distance(state.stats.distanceM.toFloat()), style = MaterialTheme.typography.bodyMedium)
                state.stats.ascentM?.let { Text("↑ ${it.roundToInt()} m", style = MaterialTheme.typography.bodyMedium) }
            }
            TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}
