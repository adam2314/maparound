package app.maparound.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import app.maparound.routing.GeoPoint
import app.maparound.share.Shared
import app.maparound.share.SharedLinks

/**
 * Android Auto entry point (navigation category): shows the MapAround map on the car screen with
 * your position, the planned route, shown tracks, the recording and the party.
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

    override fun onCreateScreen(intent: Intent): Screen {
        val r = CarMapRenderer(carContext, lifecycle)
        renderer = r
        val navigation = CarNavigation(carContext, lifecycle)
        handle(intent)
        return CarMapScreen(carContext, r, navigation)
    }

    override fun onNewIntent(intent: Intent) = handle(intent)

    /** "Navigate to …" from the car (Assistant, other apps): route there and start guidance. */
    private fun handle(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val shared = intent.dataString?.let { SharedLinks.parse(it) } as? Shared.Point ?: return
        renderer?.showDestination(GeoPoint(shared.lat, shared.lon))
    }
}
