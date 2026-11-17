package app.maparound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.party.HistoryPoint
import app.maparound.party.Member
import app.maparound.party.PartyMessage
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.sensors.SpeedUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a member's position says besides where: speed and the way they're heading, height,
 * how exact it is, battery ("12 km/h NE · alt. 345 m · ±8 m · battery 80%").
 */
fun partyPositionDetails(p: PartyMessage.Position, unit: SpeedUnit): List<String> = buildList {
    p.speedKmh?.let { kmh ->
        val speed = "${(kmh / 3.6 * unit.perMps).roundToInt()} ${unit.label}"
        add(p.headingDeg?.takeIf { kmh >= 3 }?.let { "$speed ${Fmt.cardinal(it.toFloat())}" } ?: speed)
    }
    p.altM?.let { add(T.party.altitude(it)) }
    p.accuracyM?.let { add(T.party.accuracy(it)) }
    p.batteryPct?.let { add(T.party.battery(it)) }
}

/**
 * A member's details: speed and heading, height and accuracy of the position shown; battery from
 * their newest report (also one without a GPS fix).
 */
fun memberDetails(m: Member, unit: SpeedUnit): List<String> {
    val battery = m.report?.batteryPct ?: m.position?.batteryPct
    return m.position?.let { partyPositionDetails(it.copy(batteryPct = null), unit) }.orEmpty() +
        listOfNotNull(battery?.let { T.party.battery(it) })
}

/**
 * While a member's newest report has no GPS fix good enough to show: "no GPS fix (±45 m) · heard
 * 2 min ago", a sign of life (the map keeps their last good position). Null otherwise.
 */
fun noFixLine(m: Member, now: Long): String? {
    if (!m.noFixNow) return null
    val heard = m.lastHeard.takeIf { it > 0 }?.let { at ->
        if (now - at < 60_000) T.party.heardJustNow else T.party.heard(Fmt.relativeTime(at, now).replaceFirstChar { it.lowercase() })
    }
    return listOfNotNull(T.party.noGpsFix(m.report?.accuracyM), heard).joinToString(" · ")
}

/**
 * A party member's history replayed on the map: their way so far as a line, and their marker
 * going along it, faster than it happened. The Party screen opens it, the map screen draws it
 * and plays it ([advance] each frame while [State.playing]).
 */
object PartyReplay {
    /** How much faster than it happened, to choose from. */
    val SPEEDS = listOf(10, 60, 300, 1200)

    /** A pause between positions longer than this is skipped while playing. */
    private const val MAX_GAP_MS = 10 * 60_000L

    /** About how long a replay takes at the speed chosen at first. */
    private const val TARGET_MS = 40_000L

    data class State(
        val memberId: Int,
        val name: String,
        val color: Int,
        val icon: Int,
        /** Oldest first, at least one. */
        val points: List<HistoryPoint>,
        /** From the first position to each one, metres. */
        val distances: List<Double>,
        /** The moment shown (the member's clock, ms). */
        val t: Long,
        val playing: Boolean,
        val speed: Int,
    ) {
        val start: Long get() = points.first().at
        val end: Long get() = points.last().at

        /** The last position at or before [t]. */
        val index: Int
            get() {
                var lo = 0
                var hi = points.lastIndex
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (points[mid].at <= t) lo = mid else hi = mid - 1
                }
                return lo
            }

        /** Where the member was at [t]: between two positions, as far along as the time is. */
        val where: GeoPoint
            get() {
                val i = index
                val a = points[i]
                val b = points.getOrNull(i + 1) ?: return GeoPoint(a.position.lat, a.position.lon)
                val f = if (b.at - a.at > MAX_GAP_MS) 0.0 else ((t - a.at).toDouble() / (b.at - a.at)).coerceIn(0.0, 1.0)
                return GeoPoint(
                    a.position.lat + (b.position.lat - a.position.lat) * f,
                    a.position.lon + (b.position.lon - a.position.lon) * f,
                )
            }

        /** How far they had gone by [t]. */
        val distance: Double
            get() {
                val i = index
                return distances[i] + Geo.distance(GeoPoint(points[i].position.lat, points[i].position.lon), where)
            }
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    /** Opens [member]'s history ([points], oldest first) at its start, playing. */
    fun open(member: Member, points: List<HistoryPoint>) {
        if (points.isEmpty()) return
        val distances = ArrayList<Double>(points.size)
        var d = 0.0
        points.forEachIndexed { i, h ->
            if (i > 0) {
                val p = points[i - 1].position
                d += Geo.distance(p.lat, p.lon, h.position.lat, h.position.lon)
            }
            distances += d
        }
        val span = points.last().at - points.first().at
        _state.value = State(
            member.id, member.displayName, member.color, member.icon, points, distances,
            t = points.first().at,
            playing = points.size > 1,
            speed = SPEEDS.minBy { abs(span / it - TARGET_MS) },
        )
    }

    fun close() {
        _state.value = null
    }

    /** Shows the moment [t] (stopped). */
    fun seek(t: Long) = _state.value?.let { s -> _state.value = s.copy(t = t.coerceIn(s.start, s.end), playing = false) }

    /** Plays (from the start again when it's at the end) or stops. */
    fun play(on: Boolean) = _state.value?.let { s ->
        if (s.points.size < 2) return@let
        _state.value = s.copy(playing = on, t = if (on && s.t >= s.end) s.start else s.t)
    }

    fun fromTheStart() = _state.value?.let { s -> _state.value = s.copy(t = s.start, playing = s.points.size > 1) }

    fun setSpeed(times: Int) = _state.value?.let { s -> _state.value = s.copy(speed = times) }

    /** [ms] of real time went by while playing: the moment moves on, over long pauses at once. */
    fun advance(ms: Long) {
        val s = _state.value?.takeIf { it.playing } ?: return
        var t = s.t + ms * s.speed
        val next = s.points.getOrNull(s.index + 1)
        if (next != null && next.at - s.points[s.index].at > MAX_GAP_MS) t = maxOf(t, next.at)
        _state.value = if (t >= s.end) s.copy(t = s.end, playing = false) else s.copy(t = t)
    }
}

/** The replay's controls over the map: whose, when, what then, the time line, play and speed. */
@Composable
fun PartyReplayPanel(s: PartyReplay.State, unit: SpeedUnit, folded: Boolean = false, onFold: (Boolean) -> Unit = {}) {
    MapDrawer(
        folded, onFold,
        summary = {
            // Whose, the moment shown, and play.
            PartyAvatar(s.name, s.color, s.icon, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "${s.name} · ${Fmt.moment(s.t, seconds = true)} · ${Fmt.distance(s.distance.toFloat())}",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (s.points.size > 1) IconButton(onClick = { PartyReplay.play(!s.playing) }) {
                Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (s.playing) T.party.pause else T.party.play)
            }
            IconButton(onClick = PartyReplay::close) { Icon(Icons.Filled.Close, contentDescription = T.common.close) }
        },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PartyAvatar(s.name, s.color, s.icon, 32.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(T.party.historyOf(s.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        T.party.historyPositions(s.points.size, Fmt.moment(s.start)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = PartyReplay::close) { Icon(Icons.Filled.Close, contentDescription = T.common.close) }
            }
            // The moment shown, how far by then, and the position's details then.
            val at = s.points[s.index]
            val parts = listOf(Fmt.moment(s.t, seconds = true), T.party.distanceSoFar(Fmt.distance(s.distance.toFloat()))) +
                partyPositionDetails(at.position, unit)
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            if (s.points.size > 1) {
                Slider(
                    value = (s.t - s.start).toFloat(),
                    onValueChange = { PartyReplay.seek(s.start + it.toLong()) },
                    valueRange = 0f..(s.end - s.start).toFloat(),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { PartyReplay.play(!s.playing) }) {
                        Icon(
                            if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (s.playing) T.party.pause else T.party.play,
                        )
                    }
                    IconButton(onClick = PartyReplay::fromTheStart) { Icon(Icons.Filled.Replay, contentDescription = T.party.fromTheStart) }
                    Spacer(Modifier.weight(1f))
                    PartyReplay.SPEEDS.forEach { x ->
                        FilterChip(
                            selected = s.speed == x,
                            onClick = { PartyReplay.setSpeed(x) },
                            label = { Text(T.party.replaySpeed(x)) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
