package app.maparound.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIView
import platform.UIKit.UIViewController

/**
 * The shared map screen for iOS. Swift passes its MapLibre map: [map] to steer it, [events]
 * it reports to, and [makeMapView] for the view itself, which Compose hosts under its controls.
 * [makeTerrainView], if given, makes the Metal 3D view the screen can switch to.
 */
fun MainViewController(
    map: MapSurface,
    events: MapEvents,
    subtitle: String,
    demo: Boolean,
    start3d: Boolean,
    makeMapView: () -> UIView,
    makeTerrainView: (() -> UIView)?,
): UIViewController = ComposeUIViewController {
    val interactive = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true)
    SharedMapScreen(
        map, events, subtitle, demo, start3d,
        threeD = makeTerrainView?.let { make ->
            { modifier ->
                val factory = remember { make }
                UIKitView(factory = factory, modifier = modifier, update = {}, properties = interactive)
            }
        },
    ) { modifier ->
        val factory = remember { makeMapView }
        UIKitView(factory = factory, modifier = modifier, update = {}, properties = interactive)
    }
}
