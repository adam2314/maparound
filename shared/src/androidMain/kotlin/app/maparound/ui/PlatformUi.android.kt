package app.maparound.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/** How many keep each view's screen on: it goes off when the last one lets go. */
private val holders = WeakHashMap<View, Int>()

@Composable
actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        holders[view] = (holders[view] ?: 0) + 1
        view.keepScreenOn = true
        onDispose {
            val left = (holders[view] ?: 1) - 1
            if (left <= 0) {
                holders.remove(view)
                view.keepScreenOn = false
            } else {
                holders[view] = left
            }
        }
    }
}
