package app.maparound.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import app.maparound.IosApp
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.ios.IosPlatformActions
import app.maparound.ios.PendingParty
import app.maparound.ios.PendingPartyPlatform
import app.maparound.ios.PendingRasters
import app.maparound.ios.PendingRecording
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.map.maplibre.NativeMap
import app.maparound.party.LocalParty
import app.maparound.party.LocalPartyPlatform
import app.maparound.places.LocalPlaces
import app.maparound.raster.LocalRasters
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.tracks.LocalTracks
import app.maparound.ui.theme.MapAroundTheme
import kotlinx.coroutines.flow.merge
import platform.UIKit.UIView
import platform.UIKit.UIViewController

/** A MapLibre map view made by Swift (MapLibreMap.swift). */
interface NativeMapView : NativeMap {
    val view: UIView
}

/** The whole app for iOS: the shared Compose screens with iOS's services; [newMap] makes a map view. */
fun MainViewController(newMap: () -> NativeMapView): UIViewController = ComposeUIViewController {
    val app = IosApp
    val hasLocation by app.sensors.permission.collectAsState()
    // Ask once at the first start, like the Android app.
    LaunchedEffect(Unit) { if (app.sensors.canAsk) app.sensors.requestPermission() }
    CompositionLocalProvider(
        LocalPrefs provides app.prefs,
        LocalDeviceSensors provides app.sensors,
        LocalPlaces provides app.places,
        LocalPlatform provides IosPlatformActions,
        LocalSearcher provides app.searcher,
        LocalTracks provides app.tracks,
        LocalRecordingControl provides PendingRecording,
        LocalRasters provides PendingRasters,
        app.maparound.map.LocalMapThemes provides app.themes,
        LocalParty provides PendingParty,
        LocalPartyPlatform provides PendingPartyPlatform,
        LocalOfflineData provides app.offline,
    ) {
        MapAroundTheme {
            MapAroundRoot(
                hasLocation = hasLocation,
                requestLocation = app.sensors::requestPermission,
                messages = remember { merge(app.downloads.events, app.terrain.events) },
                onStart = {},
                createMap = { MapLibreSurface(newMap(), app.tiles, app.style) },
                mapView = { map, modifier ->
                    val view = remember(map) { ((map as MapLibreSurface).native as NativeMapView).view }
                    UIKitView(factory = { view }, modifier = modifier, update = {}, properties = interactive)
                },
                view3d = { args ->
                    Box(args.modifier, contentAlignment = Alignment.Center) {
                        Text("The 3D view comes to iPhone in a later version", style = MaterialTheme.typography.bodyLarge)
                    }
                },
                satellites = null,
                // The party comes in phase 5.
                tabs = Tab.entries - Tab.Party,
                initialTab = Tab.entries.firstOrNull { it.name == app.startTab } ?: Tab.Map,
            )
        }
    }
}

private val interactive = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true)
