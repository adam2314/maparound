package app.maparound.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Compass, speed, motion (tilt, accelerometer, gyroscope), barometer and, where the platform
 * reports it ([satellites], Android only), satellite reception, as tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(hasLocation: Boolean, requestLocation: () -> Unit, satellites: (@Composable () -> Unit)? = null) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOfNotNull("Compass", "Speed", "Motion", "Barometer", if (satellites != null) "Satellites" else null)
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            titles.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> CompassScreen(hasLocation, requestLocation)
                1 -> SpeedScreen(hasLocation, requestLocation)
                2 -> MotionScreen()
                3 -> BarometerScreen(hasLocation, requestLocation)
                else -> satellites?.invoke()
            }
        }
    }
}
