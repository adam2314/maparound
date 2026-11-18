package app.maparound.wear

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.routing.Turn
import app.maparound.ui.TurnIcon
import app.maparound.watch.WatchCommand
import app.maparound.watch.WatchState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The watch app's one screen: the phone's navigation (the next turn, big), else its recording (or
 * a way to start one), else how to get going. Launched with `--es demo nav|nav-he|rec`, it shows a
 * sample (for trying it on an emulator without a paired phone).
 */
class WearActivity : ComponentActivity() {
    private val link by lazy { PhoneLink(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val demo = Demo.of(intent?.getStringExtra("demo"))
        // A sample goes where the phone's state goes, so the tile and the watch face show it too.
        if (intent?.hasExtra("demo") == true) WatchStore.update(this, demo)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        WatchStore.current(this)
        setContent {
            val state by WatchStore.state.collectAsState()
            WearApp(state) { command ->
                if (demo != null) return@WearApp
                lifecycleScope.launch {
                    if (!link.send(command)) Toast.makeText(this@WearActivity, T.watch.phoneNotReachable, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Shown again on the watch face (it may have been refused before notifications were allowed).
        WatchOngoing.show(this, WatchStore.current(this))
        lifecycleScope.launch { link.listen() }
    }

    override fun onPause() {
        link.stop()
        super.onPause()
    }
}

@Composable
fun WearApp(state: WatchState?, onCommand: (WatchCommand) -> Unit) {
    // The phone's language (and its direction): the words come from the phone app's texts.
    remember(state?.language) { I18n.apply(state?.language.orEmpty()) }
    CompositionLocalProvider(LocalLayoutDirection provides if (I18n.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MaterialTheme {
            val list = rememberScalingLazyListState()
            Scaffold(
                timeText = { TimeText() },
                positionIndicator = { PositionIndicator(scalingLazyListState = list) },
            ) {
                ScalingLazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val nav = state?.nav
                    val rec = state?.recording
                    when {
                        nav != null -> {
                            item { NavHeader(nav) }
                            item {
                                Text(
                                    nav.title,
                                    style = MaterialTheme.typography.title3,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                )
                            }
                            val more = listOfNotNull(nav.left, nav.arrival).joinToString(" · ")
                            if (more.isNotEmpty()) item {
                                Text(more, style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                            if (rec != null) item { RecordingLine(rec) }
                            item { Action(T.nav.stopNavigation, Icons.Filled.Stop) { onCommand(WatchCommand.STOP_NAVIGATION) } }
                        }
                        rec != null -> {
                            item { Text(T.watch.recording, style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.error) }
                            item { Elapsed(rec.startedAt) }
                            item { RecordingLine(rec) }
                            item { Action(T.library.stopAndSave, Icons.Filled.Stop) { onCommand(WatchCommand.STOP_RECORDING) } }
                        }
                        else -> {
                            item { Spacer(Modifier.height(12.dp)) }
                            if (state == null) {
                                item { Text(T.watch.openOnPhone, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
                                item {
                                    Text(
                                        T.watch.openOnPhoneNote,
                                        style = MaterialTheme.typography.caption2,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colors.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                    )
                                }
                            }
                            item { Action(T.map.recordTrack, Icons.Filled.FiberManualRecord, red = true) { onCommand(WatchCommand.START_RECORDING) } }
                        }
                    }
                }
            }
        }
    }
}

/** The next turn's arrow and how far it is; or what's going on instead (recalculating, arrived…). */
@Composable
private fun NavHeader(nav: WatchState.Nav) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 16.dp)) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            when (nav.status) {
                WatchState.NavStatus.REROUTING, WatchState.NavStatus.WAITING -> CircularProgressIndicator(Modifier.size(44.dp))
                WatchState.NavStatus.ARRIVED -> TurnIcon(Turn.ARRIVE, 0, Modifier.size(56.dp), color = Color.White)
                else -> nav.turn?.let { TurnIcon(it, nav.exit, Modifier.size(56.dp), color = Color.White) }
            }
        }
        nav.distance?.let { Text(app.maparound.i18n.ltr(it), style = MaterialTheme.typography.display3, fontWeight = FontWeight.Bold) }
    }
}

/** How long it's been recording, counted on the watch ("1:23:45"). */
@Composable
private fun Elapsed(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L - now % 1_000L)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    val text = if (s >= 3600) "${s / 3600}:${(s / 60 % 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}"
    else "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    Text(app.maparound.i18n.ltr(text), style = MaterialTheme.typography.display2)
}

@Composable
private fun RecordingLine(rec: WatchState.Recording) {
    Text(
        listOf(rec.distance, rec.climb).filter { it.isNotEmpty() }.joinToString(" · "),
        style = MaterialTheme.typography.body2,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, red: Boolean = false, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null, tint = if (red) Color(0xFFE53935) else Color.Unspecified) },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** Samples, for trying the app without a phone (`adb shell am start … --es demo nav`). */
private object Demo {
    fun of(name: String?): WatchState? = when (name) {
        "nav" -> WatchState(
            "en",
            WatchState.Nav("Turn left onto HaYarkon", "180 m", Turn.LEFT, left = "4.6 km left", arrival = "arrival 12:43"),
        )
        "nav-he" -> WatchState(
            "he",
            WatchState.Nav("פנו שמאלה לרחוב הירקון", "180 מ'", Turn.LEFT, left = "נותרו 4.6 ק\"מ", arrival = "הגעה 12:43"),
        )
        "rec" -> WatchState("en", recording = WatchState.Recording(System.currentTimeMillis() - 25 * 60_000L, "3.2 km", "↑ 120 m"))
        "idle" -> WatchState("en")
        else -> null
    }
}
