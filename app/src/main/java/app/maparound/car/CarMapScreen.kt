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
import app.maparound.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The map screen on the car: speed/altitude line, satellite toggle, re-centre, pan and zoom. */
class CarMapScreen(carContext: CarContext, private val renderer: CarMapRenderer) : Screen(carContext) {
    init {
        // Refresh the template when the status changes, at most once a second (hosts limit updates).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                renderer.status.collect {
                    invalidate()
                    delay(1000)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = renderer.status.value
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(if (s.satellite) "Map" else "Satellite")
                    .setOnClickListener { renderer.toggleSatellite() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_car_my_location))
                    .setOnClickListener { renderer.recenter() }
                    .build(),
            )
            .build()
        val mapActions = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_in)).setOnClickListener { renderer.zoomIn() }.build())
            .addAction(Action.Builder().setIcon(icon(R.drawable.ic_car_zoom_out)).setOnClickListener { renderer.zoomOut() }.build())
            .build()
        return NavigationTemplate.Builder()
            .setNavigationInfo(MessageInfo.Builder(s.title).setText(s.text).build())
            .setActionStrip(actions)
            .setMapActionStrip(mapActions)
            .setPanModeListener { }
            .build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()
}
