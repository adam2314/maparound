package app.maparound.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView
import platform.UIKit.UIViewController

/**
 * The shared map screen for iOS. Swift passes its MapLibre map: [map] to steer it, [events]
 * it reports to, and [makeMapView] for the view itself, which Compose hosts under its controls.
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(
    map: MapSurface,
    events: MapEvents,
    subtitle: String,
    demo: Boolean,
    makeMapView: () -> UIView,
): UIViewController = ComposeUIViewController {
    SharedMapScreen(map, events, subtitle, demo) { modifier ->
        val factory = remember { makeMapView }
        UIKitView(
            factory = factory,
            modifier = modifier,
            update = {},
            properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
        )
    }
}
