package app.maparound.car

import androidx.car.app.model.CarColor
import android.graphics.Typeface
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Bitmap
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
import app.maparound.share.GeoRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The map screen on the car: speed/altitude line, or the next maneuver while navigating; start /
 * stop navigation, voice, search (not navigating), satellite toggle, re-centre, pan and zoom.
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
        } else {
            if (planner.active && planner.route != null && !planner.busy) {
                actions.addAction(Action.Builder().setTitle(T.common.start).setOnClickListener { renderer.startNavigation() }.build())
            }
            // Where to: typed or said, the places found to pick (not while navigating: no room left).
            actions.addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_search))
                    .setOnClickListener {
                        screenManager.push(CarSearchScreen(carContext, renderer, null, null, null, GeoRequest.Mode.NAVIGATE, goIfClear = false))
                    }
                    .build(),
            )
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
            // 2D / 3D, saying where it goes: "2D" while tilted, "3D" while flat.
            .addAction(Action.Builder().setIcon(textIcon(if (s.view3d) "2D" else "3D")).setOnClickListener { renderer.toggle3d() }.build())
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

    /** A short label as a map button's icon ("2D"), white on clear so the car tints it as its own. */
    private fun textIcon(text: String): CarIcon {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.5f
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        Canvas(bmp).drawText(text, size / 2f, y, paint)
        return CarIcon.Builder(IconCompat.createWithBitmap(bmp)).setTint(CarColor.DEFAULT).build()
    }
}
