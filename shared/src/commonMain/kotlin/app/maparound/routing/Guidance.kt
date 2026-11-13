package app.maparound.routing

import app.maparound.i18n.T
import app.maparound.shared.util.toRadians
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What to do at a point along a route. */
enum class Turn {
    STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT, EXIT_LEFT, EXIT_RIGHT, U_TURN_LEFT, U_TURN_RIGHT,

    /** A roundabout driven counter-clockwise (right-hand traffic); [Maneuver.exit] says which exit. */
    ROUNDABOUT,

    /** A roundabout driven clockwise (left-hand traffic). */
    ROUNDABOUT_CW,

    /** One of the planner's stops, with nothing to turn there. */
    STOP,
    ARRIVE,
}

/**
 * One instruction: [turn] at point [index] of the route. [exit] is the roundabout exit (1 = first).
 * [stop] is the planner stop this happens at (1 = the first stop after the start; 0 = the start of a
 * route we are being led to), or null.
 */
data class Maneuver(val index: Int, val turn: Turn, val exit: Int = 0, val stop: Int? = null)

/** Turn instructions for a route, and how they are said. */
object Guidance {

    /** BRouter's turn commands as its GeoJSON "voicehints" number them (`Formatter.getJsonCommandIndex`). */
    fun brouterTurn(command: Int): Turn? = when (command) {
        1 -> Turn.STRAIGHT
        2 -> Turn.LEFT
        3 -> Turn.SLIGHT_LEFT
        4 -> Turn.SHARP_LEFT
        5 -> Turn.RIGHT
        6 -> Turn.SLIGHT_RIGHT
        7 -> Turn.SHARP_RIGHT
        8 -> Turn.KEEP_LEFT
        9 -> Turn.KEEP_RIGHT
        10, 15 -> Turn.U_TURN_LEFT
        11 -> Turn.U_TURN_RIGHT
        13 -> Turn.ROUNDABOUT
        14 -> Turn.ROUNDABOUT_CW
        17 -> Turn.EXIT_LEFT
        18 -> Turn.EXIT_RIGHT
        else -> null // 12 "off route" and 16 "beeline": nothing to say
    }

    /**
     * The turns in a BRouter GeoJSON route (asked for with `timode=2`): its "voicehints" are
     * `[point index, command, roundabout exit, distance to the next, angle]`. Only that array is
     * parsed; the rest of the file (thousands of points) is skipped.
     */
    fun brouterHints(geojson: String): List<Maneuver> {
        val key = geojson.indexOf("\"voicehints\"")
        if (key < 0) return emptyList()
        val start = geojson.indexOf('[', key)
        if (start < 0) return emptyList()
        var depth = 0
        var end = -1
        for (i in start until geojson.length) {
            when (geojson[i]) {
                '[' -> depth++
                ']' -> if (--depth == 0) { end = i; break }
                '"' -> return emptyList() // a geometry string (comment style): not what we asked for
            }
        }
        if (end < 0) return emptyList()
        val hints = runCatching { Json.parseToJsonElement(geojson.substring(start, end + 1)) as JsonArray }.getOrNull()
            ?: return emptyList()
        return hints.mapNotNull { h ->
            val a = h as? JsonArray ?: return@mapNotNull null
            val index = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val turn = a.getOrNull(1)?.jsonPrimitive?.intOrNull?.let(::brouterTurn) ?: return@mapNotNull null
            val exit = a.getOrNull(2)?.jsonPrimitive?.intOrNull ?: 0
            Maneuver(index, turn, abs(exit))
        }
    }

    /**
     * Everything navigation announces along [points]: the router's [turns], each stop between the
     * first and last of [waypoints], and the arrival. [stopNumbers] numbers those stops (default 1, 2…).
     * A route without turn hints (straight lines) gets the turns at its stops from its shape.
     */
    fun maneuvers(
        points: List<GeoPoint>,
        turns: List<Maneuver>,
        waypoints: List<GeoPoint>,
        stopNumbers: List<Int> = List((waypoints.size - 2).coerceAtLeast(0)) { it + 1 },
    ): List<Maneuver> {
        if (points.size < 2) return emptyList()
        val out = turns.filter { it.index in 0 until points.lastIndex }.sortedBy { it.index }.toMutableList()
        val stops = waypoints.drop(1).dropLast(1)
        val at = stopIndices(points, stops)
        val cum = cumulative(points)
        at.forEachIndexed { k, index ->
            val number = stopNumbers.getOrElse(k) { k + 1 }
            // A turn at the stop (within 25 m) says both; otherwise the stop gets its own entry.
            val near = out.indexOfFirst { it.stop == null && abs(cum[it.index] - cum[index]) <= 25.0 }
            if (near >= 0) {
                out[near] = out[near].copy(stop = number)
            } else {
                val shape = if (turns.isEmpty()) turnAt(points, index) else null
                out += Maneuver(index, shape ?: Turn.STOP, stop = number)
            }
        }
        out.sortBy { it.index }
        out += Maneuver(points.lastIndex, Turn.ARRIVE)
        return out
    }

    /** For each stop, the index of the route point closest to it, in order along the route. */
    fun stopIndices(points: List<GeoPoint>, stops: List<GeoPoint>): List<Int> {
        var from = 0
        return stops.map { s ->
            var best = from
            var bestD = Double.MAX_VALUE
            for (i in from until points.size) {
                val d = Geo.distance(points[i], s)
                if (d < bestD) { bestD = d; best = i }
            }
            from = best
            best
        }
    }

    fun cumulative(points: List<GeoPoint>): DoubleArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
        return cum
    }

    /** The turn a line makes at point [index], judged ~20 m either side; null when it goes on straight. */
    fun turnAt(points: List<GeoPoint>, index: Int): Turn? {
        if (index <= 0 || index >= points.lastIndex) return null
        val p = points[index]
        val before = (index - 1 downTo 0).firstOrNull { Geo.distance(points[it], p) >= 20.0 } ?: 0
        val after = (index + 1..points.lastIndex).firstOrNull { Geo.distance(p, points[it]) >= 20.0 } ?: points.lastIndex
        if (Geo.distance(points[before], p) < 1.0 || Geo.distance(p, points[after]) < 1.0) return null
        return turnFor(angle(bearing(points[before], p), bearing(p, points[after])))
    }

    /** Turn for a change of direction in degrees (positive = to the right). */
    fun turnFor(angle: Double): Turn? {
        val a = abs(angle)
        val right = angle > 0
        return when {
            a < 25 -> null
            a < 60 -> if (right) Turn.SLIGHT_RIGHT else Turn.SLIGHT_LEFT
            a < 120 -> if (right) Turn.RIGHT else Turn.LEFT
            a < 165 -> if (right) Turn.SHARP_RIGHT else Turn.SHARP_LEFT
            // Straight back has no side: a left U-turn, as in right-hand traffic.
            else -> if (right && a < 178) Turn.U_TURN_RIGHT else Turn.U_TURN_LEFT
        }
    }

    /** Initial great-circle bearing from [a] to [b], degrees from north. */
    fun bearing(a: GeoPoint, b: GeoPoint): Double {
        val p1 = toRadians(a.lat)
        val p2 = toRadians(b.lat)
        val dl = toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180.0 / kotlin.math.PI + 360.0) % 360.0
    }

    /** From bearing [from] to bearing [to], -180..180 (positive = clockwise). */
    fun angle(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    // ---- words ---------------------------------------------------------------------------------
    // In the app's language ([T]): the screen's and the voice's.

    /** What to do, for the screen: "Turn left", "At the roundabout, take the 2nd exit". */
    fun instruction(m: Maneuver): String = when (m.turn) {
        Turn.ARRIVE -> T.nav.arriveAtDestination
        Turn.STOP -> T.nav.reachStop(m.stop)
        else -> m.stop?.let { T.nav.actionAtStop(action(m), it) } ?: action(m)
    }

    /** What is said a while before the maneuver: "In 300 metres, turn left". */
    fun ahead(m: Maneuver, distanceM: Double): String = T.nav.inDistance(spokenDistance(distanceM), inSentence(m))

    /** What is said at the maneuver: "Turn left". */
    fun now(m: Maneuver): String = when (m.turn) {
        Turn.ARRIVE -> T.nav.arrivedAtDestination
        Turn.STOP -> T.nav.reachedStop(m.stop)
        else -> instruction(m)
    }

    /** Joined to an announcement when the next maneuver follows closely: "then turn right". */
    fun then(m: Maneuver): String = T.nav.then(inSentence(m))

    /** The maneuver inside a longer sentence: "turn left", "you arrive at your destination". */
    private fun inSentence(m: Maneuver): String = when (m.turn) {
        Turn.ARRIVE -> T.nav.youArrive
        Turn.STOP -> T.nav.youReachStop(m.stop)
        else -> lowerFirst(instruction(m))
    }

    private fun action(m: Maneuver): String = when (m.turn) {
        Turn.STRAIGHT -> T.nav.continueStraight
        Turn.SLIGHT_LEFT -> T.nav.turnSlightLeft
        Turn.LEFT -> T.nav.turnLeft
        Turn.SHARP_LEFT -> T.nav.turnSharpLeft
        Turn.SLIGHT_RIGHT -> T.nav.turnSlightRight
        Turn.RIGHT -> T.nav.turnRight
        Turn.SHARP_RIGHT -> T.nav.turnSharpRight
        Turn.KEEP_LEFT -> T.nav.keepLeft
        Turn.KEEP_RIGHT -> T.nav.keepRight
        Turn.EXIT_LEFT -> T.nav.exitLeft
        Turn.EXIT_RIGHT -> T.nav.exitRight
        Turn.U_TURN_LEFT, Turn.U_TURN_RIGHT -> T.nav.uTurn
        Turn.ROUNDABOUT, Turn.ROUNDABOUT_CW ->
            if (m.exit > 0) T.nav.roundaboutExit(m.exit) else T.nav.enterRoundabout
        Turn.STOP -> T.nav.continueOn
        Turn.ARRIVE -> T.nav.arrive
    }

    /** English sentences go on in lower case (a no-op in Hebrew). */
    private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercaseChar() }

    fun ordinal(n: Int): String = T.nav.ordinal(n)

    /** A distance rounded the way it is shown and said: 5 m steps up to 100 m, then 10, 50, 100 m. */
    fun rounded(m: Double): Double = when {
        m < 100 -> (m / 5).roundToInt() * 5.0
        m < 300 -> (m / 10).roundToInt() * 10.0
        m < 1000 -> (m / 50).roundToInt() * 50.0
        else -> (m / 100).roundToInt() * 100.0
    }

    /** "50 metres", "1 kilometre", "2.5 kilometres" (always a dot: English and Hebrew both say it so). */
    fun spokenDistance(m: Double): String {
        val r = when {
            m < 100 -> maxOf(10.0, (m / 10).roundToInt() * 10.0)
            else -> rounded(m)
        }
        if (r < 1000) return T.nav.spokenMetres(r.toInt())
        val tenths = (r / 100).roundToInt()
        val km = if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
        return T.nav.spokenKilometres(km)
    }
}
