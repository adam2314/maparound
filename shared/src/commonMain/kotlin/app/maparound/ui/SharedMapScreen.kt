package app.maparound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.maparound.shared.util.fixed
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val Green = Color(0xFF2E7D32)

/**
 * Phase 0 spikes (b) and (c): a shared Compose screen around the platform's views. The map view
 * is put in by the platform ([mapContent]); the screen moves it through [map] and shows what
 * [events] report. [threeD], if given, is a 3D terrain view the screen can switch to. With
 * [demo], the screen moves the map on its own (for CI screenshots); [start3d] opens in 3D.
 */
@Composable
fun SharedMapScreen(
    map: SpikeMapSurface,
    events: MapEvents,
    subtitle: String,
    demo: Boolean,
    start3d: Boolean = false,
    threeD: (@Composable (Modifier) -> Unit)? = null,
    mapContent: @Composable (Modifier) -> Unit,
) {
    val camera by events.camera.collectAsState()
    var show3d by remember { mutableStateOf(start3d && threeD != null) }
    LaunchedEffect(demo) {
        if (!demo) return@LaunchedEffect
        delay(6_000)
        map.moveTo(47.1030, 9.6110, 14.0) // Malbun
        delay(5_000)
        map.zoomBy(1.0)
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Green, primaryContainer = Color(0xFFC8E6C9))) {
        Box(Modifier.fillMaxSize()) {
            mapContent(Modifier.fillMaxSize())
            if (show3d && threeD != null) threeD(Modifier.fillMaxSize())
            Surface(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("MapAround", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Green)
                    if (show3d) {
                        Text("3D: one terrain tile, shared mesh drawn with Metal", style = MaterialTheme.typography.bodyMedium)
                        Text("Draped with a MapLibre picture of the same map", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(
                            "${fixed(camera.lat, 4)}, ${fixed(camera.lon, 4)} · zoom ${fixed(camera.zoom, 1)} · ${camera.bearing.roundToInt()}°",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (threeD != null) {
                    SmallFloatingActionButton(onClick = { show3d = !show3d }) {
                        Text(if (show3d) "2D" else "3D", fontWeight = FontWeight.Bold)
                    }
                }
                if (!show3d) {
                    SmallFloatingActionButton(onClick = { map.resetNorth() }) { Text("N", fontWeight = FontWeight.Bold) }
                    SmallFloatingActionButton(onClick = { map.zoomBy(1.0) }) { Icon(Icons.Filled.Add, contentDescription = "Zoom in") }
                    SmallFloatingActionButton(onClick = { map.zoomBy(-1.0) }) { Text("−", fontSize = 22.sp) }
                    FloatingActionButton(onClick = { map.moveTo(47.141, 9.521, 13.5) }) { Icon(Icons.Filled.Home, contentDescription = "Vaduz") }
                }
            }
        }
    }
}
