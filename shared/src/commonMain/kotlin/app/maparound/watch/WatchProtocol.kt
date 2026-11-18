package app.maparound.watch

import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.routing.Guidance
import app.maparound.routing.NavigationSession
import app.maparound.routing.Turn
import app.maparound.tracks.TrackRecorder
import app.maparound.ui.Fmt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Plain Kotlin: what the phone and a watch (Wear OS) tell each other over Google's watch link (the
// Wearable Data Layer). The phone sends what to show, already in its language and units, so the
// watch only shows it; the watch sends commands back. Unit-tested.

/** What the watch shows: navigation and recording now, in [language] ("en", "he"). */
data class WatchState(
    val language: String,
    val nav: Nav? = null,
    val recording: Recording? = null,
) {
    /**
     * Navigation: what to do next ([title]: "Turn left", "Recalculating…", "You have arrived"), how
     * far ([distance]: "180 m"), its arrow ([turn], [exit]: a roundabout's), what's left ("4.6 km
     * left", "arrival 12:43"), and where it stands.
     */
    data class Nav(
        val title: String,
        val distance: String? = null,
        val turn: Turn? = null,
        val exit: Int = 0,
        val left: String? = null,
        val arrival: String? = null,
        val status: NavStatus = NavStatus.ON_ROUTE,
    )

    enum class NavStatus { WAITING, ON_ROUTE, OFF_ROUTE, REROUTING, ARRIVED }

    /** A recording since [startedAt] (the watch counts the time itself), its distance and climb as shown. */
    data class Recording(val startedAt: Long, val distance: String, val climb: String)
}

/** The watch's commands. */
enum class WatchCommand { STOP_NAVIGATION, START_RECORDING, STOP_RECORDING }

object WatchProtocol {
    /** The phone's state for the watch (a data item, kept in sync). */
    const val STATE_PATH = "/maparound/state"

    /** A command from the watch (a message to the phone). */
    const val COMMAND_PATH = "/maparound/command"

    /** The phone app says it has this capability, so the watch finds it among the paired devices. */
    const val PHONE_CAPABILITY = "maparound_phone"

    /** The key of the state's bytes in its data item. */
    const val STATE_KEY = "state"

    /** What the watch should show for [nav] and [recording], in the app's language and units now. */
    fun stateOf(nav: NavigationSession.State, recording: TrackRecorder.State, now: Long): WatchState =
        WatchState(I18n.language.code, navOf(nav, now), recordingOf(recording))

    private fun navOf(s: NavigationSession.State, now: Long): WatchState.Nav? {
        if (!s.active) return null
        val p = s.progress
        val m = p?.next
        val status = when {
            s.rerouting -> WatchState.NavStatus.REROUTING
            p == null -> WatchState.NavStatus.WAITING
            p.arrived -> WatchState.NavStatus.ARRIVED
            p.offRoute -> WatchState.NavStatus.OFF_ROUTE
            else -> WatchState.NavStatus.ON_ROUTE
        }
        val title = when (status) {
            WatchState.NavStatus.REROUTING -> T.nav.recalculating
            WatchState.NavStatus.WAITING -> T.nav.waitingForGps
            WatchState.NavStatus.ARRIVED -> T.nav.youHaveArrived
            WatchState.NavStatus.OFF_ROUTE -> T.nav.offRoute
            WatchState.NavStatus.ON_ROUTE -> m?.let(Guidance::instruction) ?: T.nav.navigating
        }
        val onRoute = status == WatchState.NavStatus.ON_ROUTE && m != null
        return WatchState.Nav(
            title = title,
            distance = if (onRoute && p != null) Fmt.distance(Guidance.rounded(p.distanceToNextM).toFloat()) else null,
            turn = m?.turn?.takeIf { onRoute },
            exit = m?.exit?.takeIf { onRoute } ?: 0,
            left = p?.takeIf { !it.arrived }?.let { T.nav.distanceLeft(Fmt.distance(it.remainingM.toFloat())) },
            arrival = p?.takeIf { !it.arrived }?.remainingS?.let { Fmt.timeOfDay(now + it * 1000) },
            status = status,
        )
    }

    private fun recordingOf(r: TrackRecorder.State): WatchState.Recording? {
        if (!r.recording) return null
        return WatchState.Recording(
            startedAt = r.startedAt ?: 0L,
            distance = Fmt.distance(r.stats.distanceM.toFloat()),
            climb = r.stats.ascentM?.let { "↑ " + Fmt.height(it) }.orEmpty(),
        )
    }

    fun encode(s: WatchState): ByteArray = buildJsonObject {
        put("v", 1)
        put("lang", s.language)
        s.nav?.let { n ->
            put("nav", buildJsonObject {
                put("title", n.title)
                n.distance?.let { put("distance", it) }
                n.turn?.let { put("turn", it.name) }
                if (n.exit != 0) put("exit", n.exit)
                n.left?.let { put("left", it) }
                n.arrival?.let { put("arrival", it) }
                put("status", n.status.name)
            })
        }
        s.recording?.let { r ->
            put("rec", buildJsonObject {
                put("since", r.startedAt)
                put("distance", r.distance)
                put("climb", r.climb)
            })
        }
    }.toString().encodeToByteArray()

    /** The state in [bytes], or null when they aren't one. */
    fun decode(bytes: ByteArray): WatchState? = runCatching {
        val o = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val nav = (o["nav"] as? JsonObject)?.let { n ->
            WatchState.Nav(
                title = n.str("title").orEmpty(),
                distance = n.str("distance"),
                turn = n.str("turn")?.let { t -> Turn.entries.firstOrNull { it.name == t } },
                exit = (n["exit"] as? JsonPrimitive)?.intOrNull ?: 0,
                left = n.str("left"),
                arrival = n.str("arrival"),
                status = n.str("status")?.let { s -> WatchState.NavStatus.entries.firstOrNull { it.name == s } } ?: WatchState.NavStatus.ON_ROUTE,
            )
        }
        val rec = (o["rec"] as? JsonObject)?.let { r ->
            WatchState.Recording(
                startedAt = (r["since"] as? JsonPrimitive)?.longOrNull ?: 0L,
                distance = r.str("distance").orEmpty(),
                climb = r.str("climb").orEmpty(),
            )
        }
        WatchState(o.str("lang") ?: "en", nav, rec)
    }.getOrNull()

    fun encode(c: WatchCommand): ByteArray = c.name.encodeToByteArray()

    fun command(bytes: ByteArray): WatchCommand? = WatchCommand.entries.firstOrNull { it.name == bytes.decodeToString() }
}
