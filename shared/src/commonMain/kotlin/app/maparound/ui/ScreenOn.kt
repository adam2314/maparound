package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import app.maparound.data.LocalPrefs
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How long the screen stays on (Settings → Screen), with the app open and while navigating: the
 * phone's own setting, some minutes after the last touch, or always. It never goes off sooner than
 * the phone's own setting (an app can only keep it on).
 */
enum class ScreenOn(val minutes: Int?) {
    SYSTEM(null),
    M2(2),
    M5(5),
    M10(10),
    M30(30),
    ALWAYS(null),
    ;

    /** How much longer to keep the screen on, [touchedAt] the last touch: 0 none, [Long.MAX_VALUE] for good. */
    fun keepLeft(touchedAt: Long, now: Long): Long = when (this) {
        SYSTEM -> 0
        ALWAYS -> Long.MAX_VALUE
        else -> (touchedAt + minutes!! * 60_000L - now).coerceAtLeast(0)
    }
}

/** When the user last touched the app (or a key): what "minutes after the last touch" counts from. */
object UserActivity {
    private val _lastTouch = MutableStateFlow(nowMillis())
    val lastTouch: StateFlow<Long> = _lastTouch.asStateFlow()

    /** A touch, a key. Counted every few seconds at most (a pan is many). */
    fun touched() {
        val now = nowMillis()
        if (now - _lastTouch.value >= 5_000) _lastTouch.value = now
    }
}

/** Every touch on what this is put on counts as the user's activity ([UserActivity]); none is taken. */
fun Modifier.watchTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            UserActivity.touched()
        }
    }
}

/** Keeps the screen on as chosen: [navigating]'s choice or the app's, always or for a while after the last touch. */
@Composable
fun ScreenKeeper(navigating: Boolean) {
    val prefs = LocalPrefs.current
    val open by prefs.screenOnOpen.collectAsState()
    val nav by prefs.screenOnNav.collectAsState()
    val mode = if (navigating) nav else open
    val touchedAt by UserActivity.lastTouch.collectAsState()
    var keep by remember { mutableStateOf(false) }
    LaunchedEffect(mode, touchedAt) {
        val left = mode.keepLeft(touchedAt, nowMillis())
        keep = left > 0
        if (keep && left != Long.MAX_VALUE) {
            delay(left)
            keep = false
        }
    }
    if (keep) KeepScreenOn()
}
