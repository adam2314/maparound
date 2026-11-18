package app.maparound.watch

import app.maparound.i18n.DistanceUnit
import app.maparound.i18n.HeightUnit
import app.maparound.i18n.I18n
import app.maparound.i18n.Units
import app.maparound.i18n.T
import app.maparound.routing.GeoPoint
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

/**
 * What the watch shows: navigation and recording now, in [language] ("en", "he"); the phone's
 * distance and height units, for what the watch measures itself (the route view's scale).
 */
data class WatchState(
    val language: String,
    val nav: Nav? = null,
    val recording: Recording? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.METRIC,
    val heightUnit: HeightUnit = HeightUnit.M,
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

/** The route on the watch: its line (thinned), the turns and the stops along it (the arrival last). */
data class WatchRoute(val points: List<GeoPoint>, val turns: List<GeoPoint>, val stops: List<GeoPoint>)

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

    /** The route being navigated (a data item of its own: sent once a route, not with every turn). */
    const val ROUTE_PATH = "/maparound/route"

    /** A route is thinned until it has no more points than this (a few kB). */
    const val ROUTE_MAX_POINTS = 600

    /** The route [nav] follows, thinned for the watch; null when not navigating. */
    fun routeOf(nav: NavigationSession.State): WatchRoute? {
        val all = nav.route?.points?.takeIf { nav.active && it.size >= 2 } ?: return null
        var toleranceM = 4.0
        var keep = app.maparound.share.TrackLink.douglasPeucker(all, toleranceM / 111_320.0)
        while (keep.size > ROUTE_MAX_POINTS) {
            toleranceM *= 2
            keep = app.maparound.share.TrackLink.douglasPeucker(all, toleranceM / 111_320.0)
        }
        val stop = { m: app.maparound.routing.Maneuver -> m.stop != null || m.turn == Turn.ARRIVE }
        return WatchRoute(
            points = keep.map { all[it] },
            turns = nav.maneuvers.filter { !stop(it) }.mapNotNull { all.getOrNull(it.index) },
            stops = nav.maneuvers.filter(stop).mapNotNull { all.getOrNull(it.index) },
        )
    }

    /** [r] as small JSON: each line of points as whole 1e-5 degrees, each after the first as the difference. */
    fun encodeRoute(r: WatchRoute?): ByteArray = buildJsonObject {
        put("v", 1)
        if (r != null) {
            put("p", packed(r.points))
            put("t", packed(r.turns))
            put("s", packed(r.stops))
        }
    }.toString().encodeToByteArray()

    /** The route in [bytes]; null when there's none (or they aren't one). */
    fun decodeRoute(bytes: ByteArray): WatchRoute? = runCatching {
        val o = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        val p = unpacked(o["p"] as? kotlinx.serialization.json.JsonArray ?: return null)
        if (p.size < 2) return null
        WatchRoute(
            p,
            unpacked(o["t"] as? kotlinx.serialization.json.JsonArray),
            unpacked(o["s"] as? kotlinx.serialization.json.JsonArray),
        )
    }.getOrNull()

    private fun packed(points: List<GeoPoint>) = kotlinx.serialization.json.buildJsonArray {
        var lat = 0L
        var lon = 0L
        for (p in points) {
            val a = kotlin.math.round(p.lat * 1e5).toLong()
            val b = kotlin.math.round(p.lon * 1e5).toLong()
            add(JsonPrimitive(a - lat))
            add(JsonPrimitive(b - lon))
            lat = a
            lon = b
        }
    }

    private fun unpacked(a: kotlinx.serialization.json.JsonArray?): List<GeoPoint> {
        if (a == null) return emptyList()
        val out = ArrayList<GeoPoint>(a.size / 2)
        var lat = 0L
        var lon = 0L
        var i = 0
        while (i + 1 < a.size) {
            lat += (a[i] as JsonPrimitive).longOrNull ?: 0L
            lon += (a[i + 1] as JsonPrimitive).longOrNull ?: 0L
            out += GeoPoint(lat / 1e5, lon / 1e5)
            i += 2
        }
        return out
    }

    /** What the watch should show for [nav] and [recording], in the app's language and units now. */
    fun stateOf(nav: NavigationSession.State, recording: TrackRecorder.State, now: Long): WatchState =
        WatchState(I18n.language.code, navOf(nav, now), recordingOf(recording), Units.current.distance, Units.current.height)

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
        put("dist", s.distanceUnit.name)
        put("height", s.heightUnit.name)
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
        WatchState(
            o.str("lang") ?: "en", nav, rec,
            DistanceUnit.entries.firstOrNull { it.name == o.str("dist") } ?: DistanceUnit.METRIC,
            HeightUnit.entries.firstOrNull { it.name == o.str("height") } ?: HeightUnit.M,
        )
    }.getOrNull()

    fun encode(c: WatchCommand): ByteArray = c.name.encodeToByteArray()

    fun command(bytes: ByteArray): WatchCommand? = WatchCommand.entries.firstOrNull { it.name == bytes.decodeToString() }
}
