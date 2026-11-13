package app.maparound.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.car.app.CarContext
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import app.maparound.i18n.T
import app.maparound.routing.Guidance
import app.maparound.routing.NavigationSession
import app.maparound.routing.Turn
import app.maparound.routing.TurnGlyph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.TimeZone
import kotlin.math.roundToInt
import app.maparound.routing.Maneuver as RouteManeuver

/**
 * Turn-by-turn on the car: the maneuver card and arrival estimate on the map template, and the
 * trip for the car's own displays (cluster, head-up). Tells Android Auto when navigation starts
 * and ends; stopping it from the car (or another navigation app taking over) stops ours.
 */
class CarNavigation(private val carContext: CarContext, lifecycle: Lifecycle) : DefaultLifecycleObserver {
    private val manager = carContext.getCarService(NavigationManager::class.java)
    private var scope: CoroutineScope? = null
    private var navigating = false
    private var tripSentAt = 0L
    private val icons = HashMap<Pair<Turn, Int>, CarIcon>()

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        manager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = NavigationSession.stop()
        })
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            NavigationSession.state.collect { state ->
                if (state.active && !navigating) {
                    navigating = true
                    manager.navigationStarted()
                } else if (!state.active && navigating) {
                    navigating = false
                    manager.navigationEnded()
                }
                if (state.active) sendTrip(state)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope?.cancel()
        scope = null
        if (navigating) {
            navigating = false
            manager.navigationEnded()
        }
        manager.clearNavigationManagerCallback()
    }

    /** What the map template shows above the map while navigating. */
    fun info(state: NavigationSession.State): NavigationTemplate.NavigationInfo {
        val p = state.progress
        val next = p?.next ?: state.maneuvers.firstOrNull()
        return when {
            state.rerouting || next == null -> RoutingInfo.Builder().setLoading(true).build()
            p?.arrived == true -> MessageInfo.Builder(T.nav.youHaveArrived).setImage(icon(Turn.ARRIVE, 0)).build()
            p?.offRoute == true -> MessageInfo.Builder(T.nav.offRoute)
                .setText(state.error ?: T.nav.offRouteBy("${Guidance.rounded(p.offsetM).roundToInt()} ${T.common.unitM}"))
                .build()
            else -> RoutingInfo.Builder()
                .setCurrentStep(step(next), distance(p?.distanceToNextM ?: 0.0))
                .apply { p?.then?.let { setNextStep(step(it)) } }
                .build()
        }
    }

    /** Distance and time to the destination, or null when there is nothing to estimate. */
    fun estimate(state: NavigationSession.State): TravelEstimate? {
        val p = state.progress ?: return null
        if (p.arrived) return null
        val seconds = p.remainingS
        val arrival = System.currentTimeMillis() + (seconds ?: 0L) * 1000
        return TravelEstimate.Builder(distance(p.remainingM), DateTimeWithZone.create(arrival, TimeZone.getDefault()))
            .setRemainingTimeSeconds(seconds ?: TravelEstimate.REMAINING_TIME_UNKNOWN)
            .build()
    }

    /** The trip for the car's own displays, at most once a second (hosts throttle more often). */
    private fun sendTrip(state: NavigationSession.State) {
        val now = System.currentTimeMillis()
        if (now - tripSentAt < 1000) return
        tripSentAt = now
        val p = state.progress
        val next = p?.next
        val estimate = estimate(state)
        val trip = if (state.rerouting || p == null || next == null || estimate == null) {
            Trip.Builder().setLoading(true).build()
        } else {
            val stepEstimate = TravelEstimate.Builder(
                distance(p.distanceToNextM),
                DateTimeWithZone.create(now + timeTo(p.distanceToNextM, p.remainingM, p.remainingS) * 1000, TimeZone.getDefault()),
            ).build()
            Trip.Builder()
                .addStep(step(next), stepEstimate)
                .addDestination(Destination.Builder().setName(T.nav.destination).build(), estimate)
                .build()
        }
        runCatching { manager.updateTrip(trip) }
    }

    private fun timeTo(distanceM: Double, remainingM: Double, remainingS: Long?): Long =
        if (remainingS == null || remainingM <= 0) 0 else (remainingS * (distanceM / remainingM)).toLong()

    private fun step(m: RouteManeuver): Step =
        Step.Builder(Guidance.instruction(m)).setManeuver(maneuver(m)).build()

    private fun maneuver(m: RouteManeuver): Maneuver {
        val type = when (m.turn) {
            Turn.STRAIGHT -> Maneuver.TYPE_STRAIGHT
            Turn.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            Turn.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            Turn.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            Turn.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            Turn.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            Turn.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            Turn.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
            Turn.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
            Turn.EXIT_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
            Turn.EXIT_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            Turn.U_TURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
            Turn.U_TURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
            Turn.ROUNDABOUT -> if (m.exit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else Maneuver.TYPE_STRAIGHT
            Turn.ROUNDABOUT_CW -> if (m.exit > 0) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW else Maneuver.TYPE_STRAIGHT
            Turn.STOP -> Maneuver.TYPE_DESTINATION_STRAIGHT
            Turn.ARRIVE -> Maneuver.TYPE_DESTINATION
        }
        val b = Maneuver.Builder(type).setIcon(icon(m.turn, m.exit))
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW || type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW) {
            b.setRoundaboutExitNumber(m.exit)
        }
        return b.build()
    }

    /** The phone's arrow shapes, drawn white for the car's maneuver card. */
    private fun icon(turn: Turn, exit: Int): CarIcon = icons.getOrPut(turn to exit) {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val glyph = TurnGlyph.of(turn, exit)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = TurnGlyph.STROKE * size
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        fun path(points: List<TurnGlyph.Pt>, close: Boolean) = Path().apply {
            points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * size, p.y * size) else lineTo(p.x * size, p.y * size) }
            if (close) close()
        }
        glyph.lines.forEach { canvas.drawPath(path(it, false), stroke) }
        glyph.heads.forEach { canvas.drawPath(path(it, true), fill) }
        glyph.rings.forEach { canvas.drawCircle(it.center.x * size, it.center.y * size, it.radius * size, if (it.filled) fill else stroke) }
        CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
    }

    private fun distance(m: Double): Distance {
        val r = Guidance.rounded(m)
        return when {
            r < 1000 -> Distance.create(r, Distance.UNIT_METERS)
            r < 10_000 -> Distance.create(r / 1000, Distance.UNIT_KILOMETERS_P1)
            else -> Distance.create((r / 1000).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
        }
    }
}
