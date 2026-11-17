package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

/** How many keep the screen on: it may go off when the last one lets go. */
private var holders = 0

@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        holders++
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose {
            holders--
            if (holders <= 0) {
                holders = 0
                UIApplication.sharedApplication.idleTimerDisabled = false
            }
        }
    }
}
