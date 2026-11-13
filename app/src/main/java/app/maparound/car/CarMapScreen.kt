package app.maparound.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.maparound.AppGraph
import app.maparound.R
import app.maparound.i18n.T
import app.maparound.routing.NavigationSession
import app.maparound.routing.PlannerSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The map screen on the car: speed/altitude line, or the next maneuver while navigating; start /
 * stop navigation, voice, satellite toggle, re-centre, pan and zoom.
 */
class CarMapScreen(
    carContext: CarContext,
    private val renderer: CarMapRenderer,
    private val navigation: CarNavigation,
) : Screen(carContext) {
    init {
        // Refresh the template when anything shown changes, at most once a second (hosts limit updates).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(renderer.status, NavigationSession.state, PlannerSession.state, AppGraph.prefs.navVoice) { _, _, _, _ -> Unit }
                    .collect {
                        invalidate()
                        delay(1000)
                    }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = renderer.status.value
        val nav = NavigationSession.state.value
        val planner = PlannerSession.state.value
        val actions = ActionStrip.Builder()
        if (nav.active) {
            actions.addAction(Action.Builder().setTitle(T.nav.stopNavigation).setOnClickListener { NavigationSession.stop() }.build())
            val voice = AppGraph.prefs.navVoice.value
            actions.addAction(
                Action.Builder()
                    .setTitle(if (voice) T.nav.mute else T.nav.voice)
                    .setOnClickListener {
                        AppGraph.prefs.setNavVoice(!voice)
                        NavigationSession.voiceChanged(!voice)
                    }
                    .build(),
            )
        } else if (planner.active && planner.route != null && !planner.busy) {
            actions.addAction(Action.Builder().setTitle(T.common.start).setOnClickListener { renderer.startNavigation() }.build())
        }
        actions
            .addAction(
                Action.Builder()
                    .setTitle(if (s.satellite) T.nav.carMap else T.nav.carSatellite)
                    .setOnClickListener { renderer.toggleSatellite() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_my_location))
                    .setOnClickListener { renderer.recenter() }
                    .build(),
            )
        val mapActions = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_in)).setOnClickListener { renderer.zoomIn() }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_out)).setOnClickListener { renderer.zoomOut() }.build())
            .build()
        val template = NavigationTemplate.Builder()
            .setActionStrip(actions.build())
            .setMapActionStrip(mapActions)
            .setPanModeListener { }
        if (nav.active) {
            template.setNavigationInfo(navigation.info(nav))
            navigation.estimate(nav)?.let { template.setDestinationTravelEstimate(it) }
        } else {
            template.setNavigationInfo(MessageInfo.Builder(s.title).setText(s.text).build())
        }
        return template.build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
