package app.maparound.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.maparound.AppGraph
import app.maparound.i18n.T
import app.maparound.party.PartySession
import app.maparound.routing.NavigationSession
import app.maparound.share.GeoRequest

/**
 * Android Auto entry point (navigation category): shows the MapAround map on the car screen with
 * your position, the planned route, shown tracks, the recording and the party; answers Assistant's
 * "navigate to X", "find X" and commands ([GeoRequest]).
 */
class MapAroundCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR // debug builds: also the desktop head unit
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    @Deprecated("Deprecated in the library; still the simplest entry point")
    override fun onCreateSession(): Session = CarMapSession()
}

class CarMapSession : Session() {
    private var renderer: CarMapRenderer? = null

    init {
        // A party that was on carries on (sending my position) when the car screen shows, as when
        // the phone's does: the phone may stay in a pocket the whole drive. Android lets the service
        // start while the car screen is in use.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = PartySession.resumeIfWasActive()
        })
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val r = CarMapRenderer(carContext, lifecycle)
        renderer = r
        val navigation = CarNavigation(carContext, lifecycle)
        val map = CarMapScreen(carContext, r, navigation)
        // Started by a request with places to choose from: they show over the map.
        val asked = request(intent) ?: return map
        screens().push(map)
        return asked
    }

    override fun onNewIntent(intent: Intent) {
        val asked = request(intent) ?: return
        screens().popToRoot()
        screens().push(asked)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        renderer?.onCarConfigurationChanged()
    }

    private fun screens() = carContext.getCarService(ScreenManager::class.java)

    /**
     * A request from the car (Assistant's "navigate to X", "find X", commands; [GeoRequest]): done
     * here, or the screen to show (the places found for X, to choose from; one clear place for
     * "navigate to" goes at once). Null when there's nothing to show.
     */
    private fun request(intent: Intent): Screen? {
        val r = renderer ?: return null
        return when (val req = GeoRequest.parse(intent.action, intent.dataString)) {
            null -> null
            is GeoRequest.Navigate -> {
                if (req.query != null) {
                    CarSearchScreen(carContext, r, req.query, req.near, req.profile, req.mode, goIfClear = true)
                } else {
                    req.point?.let { r.goTo(it, req.profile, req.mode) }
                    null
                }
            }
            is GeoRequest.Search -> CarSearchScreen(carContext, r, req.query, req.near, null, GeoRequest.Mode.NAVIGATE, goIfClear = false)
            is GeoRequest.Command -> {
                command(req)
                null
            }
        }
    }

    /** What MapAround can do of Assistant's commands; the others say so (Android Auto requires it). */
    private fun command(c: GeoRequest.Command) {
        val r = renderer
        val done = when (c.act) {
            "exit_navigation" -> {
                NavigationSession.stop()
                true
            }
            "mute", "unmute" -> {
                val on = c.act == "unmute"
                AppGraph.prefs.setNavVoice(on)
                NavigationSession.voiceChanged(on)
                true
            }
            "show_satellite", "hide_satellite" -> r?.setSatellite(c.act == "show_satellite") != null
            "follow_mode" -> r?.recenter() != null
            "route_overview" -> r?.overview() == true
            "go_back" -> {
                screens().pop()
                true
            }
            // The nth of the places found (0 the first), while they show.
            "select_search_result" -> (screens().top as? CarSearchScreen)?.pickAt(c.id ?: 0) == true
            else -> false
        }
        if (!done) CarToast.makeText(carContext, T.nav.carNotSupported, CarToast.LENGTH_LONG).show()
    }
}
