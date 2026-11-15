package app.maparound.map

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.maparound.AppGraph
import app.maparound.data.MapPos
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.routing.NavigationSession
import app.maparound.sensors.LocationSource
import app.maparound.sensors.hasLocationPermission
import app.maparound.sensors.toGeoFix
import app.maparound.ui.SecondScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The map on a second screen: a monitor or TV by cable (USB-C, HDMI) or a wireless display, any
 * display Android offers for presentations. A Presentation there holds the same MapLibre map as
 * the car screen's, fed by the same live data ([LiveMapFeed]), following the position: closer
 * while navigating. The phone keeps its own screen and controls. Switched on in the map layers;
 * off again when the screen goes away or the app closes.
 */
class AndroidSecondScreen(private val activity: ComponentActivity) : SecondScreen, DefaultLifecycleObserver, DisplayManager.DisplayListener {
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private val _display = MutableStateFlow<String?>(null)
    override val display: StateFlow<String?> = _display.asStateFlow()
    private val _showing = MutableStateFlow(false)
    override val showing: StateFlow<Boolean> = _showing.asStateFlow()

    private var presentation: Presentation? = null
    private var map: AndroidMapSurface? = null
    private var scope: CoroutineScope? = null

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        displays.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        refresh()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        displays.unregisterDisplayListener(this)
        release()
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()

    /** The screen to show on: the first one Android offers for presentations. */
    private fun target(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()

    private fun refresh() {
        val d = target()
        _display.value = d?.name
        val shownOn = presentation?.display?.displayId
        if (shownOn != null && shownOn != d?.displayId) release() // that screen went away
    }

    override fun show(on: Boolean) {
        if (!on) return release()
        val d = target() ?: return
        if (presentation?.display?.displayId == d.displayId) return
        open(d)
    }

    private fun open(d: Display) {
        release()
        try {
            val p = Presentation(activity, d)
            // Read from further away than the phone: text and symbols a bit larger, as in the car.
            val m = AndroidMapSurface(p.context, textScale = 1.25f)
            p.setContentView(m.native.view)
            p.setOnDismissListener { if (presentation === p) release() }
            p.show()
            m.onResume()
            m.moveTo(AppGraph.prefs.lastPosition()?.let { it.copy(zoom = it.zoom.coerceAtLeast(12)) } ?: MapPos(30.0, 10.0, 3))
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            LiveMapFeed(m, s).start()
            s.launch {
                // Navigating: close enough to see the turns.
                NavigationSession.state.map { it.active }.distinctUntilChanged().collect { active ->
                    if (active) {
                        val zoom = NAV_ZOOM - MapLibreSurface.ZOOM_OFFSET
                        val cam = m.native.camera()
                        if (cam.zoom < zoom) m.native.moveCamera(cam.lat, cam.lon, zoom.toDouble(), animated = true)
                    }
                }
            }
            if (activity.hasLocationPermission()) {
                s.launch {
                    LocationSource.updates(activity).collect { loc ->
                        m.updateLocation(loc.toGeoFix())
                        m.native.moveCamera(loc.latitude, loc.longitude, m.native.camera().zoom, animated = true)
                    }
                }
            }
            presentation = p
            map = m
            scope = s
            _showing.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Could not show the map on ${d.name}", e)
            release()
        }
    }

    private fun release() {
        scope?.cancel()
        scope = null
        map?.let {
            it.onPause()
            runCatching { it.destroy() }
        }
        map = null
        val p = presentation
        presentation = null
        runCatching { p?.dismiss() }
        _showing.value = false
    }

    private companion object {
        const val TAG = "SecondScreen"

        /** Zoom (mapsforge's levels) to see the turns while navigating, as on the car screen. */
        const val NAV_ZOOM = 16
    }
}
