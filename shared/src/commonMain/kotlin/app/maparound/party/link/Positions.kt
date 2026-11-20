package app.maparound.party.link

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.sensors.GeoFix
import app.maparound.share.TrackLink
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Plain Kotlin: when a member's position goes out on a connection (docs/LINK_PROTOCOL.md §3,
// "Modes"): dead reckoning, the modes' numbers, and the trail since the previous one. Unit-tested.

/** What kind of connection: they differ in the modes' numbers and the largest position. */
enum class ConnectionKind(
    /** The largest position packet, its trail included. */
    val positionMax: Int,
) {
    ONLINE(512), LORA(80), NEARBY(512)
}

/** One mode on one kind of connection (§3's table). */
data class ModeNumbers(val errorM: Double, val minGapS: Long, val maxSilenceS: Long, val trailToleranceM: Double)

object ModeTable {
    fun of(mode: LinkMode, kind: ConnectionKind): ModeNumbers {
        val radio = kind == ConnectionKind.LORA
        return when (mode) {
            LinkMode.SAVER -> if (radio) ModeNumbers(150.0, 180, 1800, 25.0) else ModeNumbers(50.0, 30, 300, 25.0)
            LinkMode.NORMAL -> if (radio) ModeNumbers(50.0, 45, 600, 10.0) else ModeNumbers(15.0, 5, 60, 10.0)
            LinkMode.LIVE -> if (radio) ModeNumbers(25.0, 30, 300, 3.0) else ModeNumbers(3.0, 1, 15, 3.0)
        }
    }

    /** Saver < Normal < Live. */
    fun rank(mode: LinkMode): Int = when (mode) {
        LinkMode.SAVER -> 0
        LinkMode.NORMAL -> 1
        LinkMode.LIVE -> 2
    }

    /** During an SOS: Live's numbers, and on the radio a position every 30 s at least. */
    const val SOS_RADIO_S = 30L
}

object DeadReckoning {
    /** How long a position is carried on. */
    const val HORIZON_S = 120.0

    /** Slower than this is standing still: nothing is predicted. */
    const val MIN_KMH = 2

    /**
     * Where [pos], taken at [timeS] (Unix seconds), puts the member at [nowMs]: straight on along
     * its heading at its speed, for at most [HORIZON_S]. Sender and receivers compute the same.
     */
    fun predict(pos: LinkMessage.Pos, timeS: Long, nowMs: Long): GeoPoint {
        val lat = pos.at.latDeg
        val lon = pos.at.lonDeg
        val speed = pos.speedKmh
        val heading = pos.headingDeg
        if (speed == null || heading == null || speed < MIN_KMH) return GeoPoint(lat, lon)
        val d = speed / 3.6 * ((nowMs - timeS * 1000) / 1000.0).coerceIn(0.0, HORIZON_S)
        val r = heading * PI / 180
        return GeoPoint(lat + d * cos(r) / M_PER_DEG, lon + d * sin(r) / (M_PER_DEG * cos(lat * PI / 180)))
    }

    /** Metres per degree of latitude (and of longitude at the equator), as the spec computes it. */
    const val M_PER_DEG = 111_320.0
}

/** A position to send: the message and its header time (when the fix was taken). */
data class OutgoingPos(val pos: LinkMessage.Pos, val time: Long)

/** What the sender needs to know besides the fixes, at each call. */
data class SendContext(
    val nowMs: Long,
    /** The mode on this connection now (Auto's choice, a pinned one, party Live, an SOS). */
    val mode: ModeInfo,
    /** The mode in my last HELLO on this connection: positions carry theirs only while it differs. */
    val helloMode: ModeInfo?,
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    val sos: Boolean = false,
    /** The connection can take a packet now (a radio's airtime budget). */
    val canSend: Boolean = true,
)

/**
 * My position on one connection of one party: takes the fixes ([onFix]) and says when one is due
 * ([next], called on each fix and about once a second). A position goes out when the others'
 * prediction of where I am is off by more than the mode's error, when I stop or start moving, when
 * a good fix comes back, when the mode got livelier or an SOS starts or ends, and as a heartbeat;
 * never sooner than the mode's min gap after the previous one. Each carries the way since the
 * previous one, simplified to fit.
 */
class PositionSender(val kind: ConnectionKind) {
    /** Why the last position went out (for tests and logs). */
    var lastReason: Reason? = null
        private set

    enum class Reason { FIRST, SOS, MODE, FIX_BACK, STOP_START, ERROR, HEARTBEAT }

    private var last: OutgoingPos? = null
    private var lastSentMs = 0L
    private var lastMode: LinkMode? = null
    private var lastSos = false
    private var lastMoving = false
    private var moving = false

    /** A position was due but held back (too soon, or no airtime). */
    private var held = false
    private var latest: GeoFix? = null

    /** Good fixes since the last position: its trail. */
    private val way = ArrayDeque<GeoFix>()

    fun onFix(fix: GeoFix) {
        if (latest?.let { fix.timeMs <= it.timeMs } == true) return
        latest = fix
        fix.speedMps?.let { mps ->
            val kmh = mps * 3.6
            if (kmh >= START_KMH) moving = true else if (kmh < STOP_KMH) moving = false
        }
        if (isGood(fix)) {
            way.addLast(fix)
            while (way.size > MAX_WAY) way.removeFirst()
        }
    }

    /** As if nothing had been sent yet (the connection is back): the next current fix goes at once. */
    fun reset() {
        last = null
        lastSentMs = 0
        lastMode = null
        lastSos = false
        lastMoving = false
        held = false
    }

    /** The position to send now, or null. Once returned, it counts as sent. */
    fun next(ctx: SendContext): OutgoingPos? {
        // An SOS: Live's numbers, whatever the mode says.
        val mode = if (ctx.sos) LinkMode.LIVE else ctx.mode.mode
        val numbers = numbers(ctx.mode.mode, ctx.sos)
        val now = ctx.nowMs
        val fix = latest?.takeIf { now - it.timeMs <= CURRENT_MS }
        val good = fix?.takeIf(::isGood)
        val prev = last
        val reason = when {
            prev == null -> if (fix != null) Reason.FIRST else null
            ctx.sos != lastSos -> Reason.SOS
            lastMode != null && ModeTable.rank(mode) > ModeTable.rank(lastMode!!) -> Reason.MODE
            good != null && !prev.pos.isGoodFix && good.timeMs / 1000 > prev.time -> Reason.FIX_BACK
            moving != lastMoving -> Reason.STOP_START
            good != null && good.timeMs / 1000 >= prev.time && off(prev, good) > maxOf(numbers.errorM, (good.accuracyM ?: 0f).toDouble()) -> Reason.ERROR
            now - lastSentMs >= numbers.maxSilenceS * 1000 -> Reason.HEARTBEAT
            else -> null
        }
        // Held back (too soon, or no airtime): it goes at the next chance, without waiting for a fix.
        held = reason != null && (prev != null && now - lastSentMs < numbers.minGapS * 1000 || !ctx.canSend)
        if (reason == null || held) return null
        val out = build(ctx, fix, prev, numbers)
        last = out
        lastSentMs = now
        lastMode = mode
        lastSos = ctx.sos
        lastMoving = moving
        lastReason = reason
        while (way.isNotEmpty() && way.first().timeMs / 1000 <= out.time) way.removeFirst()
        return out
    }

    /**
     * When [next] may next have a position without a new fix coming first (a fix is ticked for
     * anyway): a heartbeat, or one that was held back. [Long.MAX_VALUE]: only a fix starts it.
     */
    fun nextDueMs(nowMs: Long, mode: LinkMode, sos: Boolean): Long {
        val numbers = numbers(mode, sos)
        if (last == null) return if (latest?.let { nowMs - it.timeMs <= CURRENT_MS } == true) nowMs else Long.MAX_VALUE
        if (held) return maxOf(lastSentMs + numbers.minGapS * 1000, nowMs + HELD_RETRY_MS)
        return lastSentMs + numbers.maxSilenceS * 1000
    }

    /** Whether I'm moving, as the fixes' speeds say (with hysteresis). */
    val isMoving: Boolean get() = moving

    /** The numbers [mode] (an SOS: Live's, and on the radio at most [ModeTable.SOS_RADIO_S] apart) sets here. */
    private fun numbers(mode: LinkMode, sos: Boolean): ModeNumbers =
        ModeTable.of(if (sos) LinkMode.LIVE else mode, kind).let {
            if (sos && kind == ConnectionKind.LORA) it.copy(maxSilenceS = minOf(it.maxSilenceS, ModeTable.SOS_RADIO_S)) else it
        }

    /** How far [fix] is from where [prev] predicts it. */
    private fun off(prev: OutgoingPos, fix: GeoFix): Double {
        val p = DeadReckoning.predict(prev.pos, prev.time, fix.timeMs)
        return Geo.distance(p.lat, p.lon, fix.lat, fix.lon)
    }

    private fun build(ctx: SendContext, fix: GeoFix?, prev: OutgoingPos?, numbers: ModeNumbers): OutgoingPos {
        val mode = ctx.mode.takeIf { it != ctx.helloMode }
        if (fix == null) {
            // No location at all: a sign of life at the last place, "no fix".
            val pos = LinkMessage.Pos(
                prev!!.pos.at, accuracyM = LinkMessage.Pos.NO_FIX, batteryPct = ctx.batteryPct, charging = ctx.charging,
                sos = ctx.sos, mode = mode,
            )
            return OutgoingPos(pos, ctx.nowMs / 1000)
        }
        val time = fix.timeMs / 1000
        val kmh = fix.speedMps?.let { (it * 3.6).roundToInt().coerceIn(0, 255) }
        val base = LinkMessage.Pos(
            GridPoint.of(fix.lat, fix.lon),
            accuracyM = fix.accuracyM?.let { ceil(it).toInt().coerceIn(0, 254) },
            batteryPct = ctx.batteryPct,
            charging = ctx.charging,
            heading = fix.bearingDeg?.takeIf { moving }?.let { LinkMessage.Pos.heading(it.toDouble()) },
            speedKmh = kmh,
            altM = fix.altitudeM?.takeIf { fix.satellite }?.roundToInt(),
            sos = ctx.sos,
            mode = mode,
        )
        if (prev == null || !isGood(fix)) return OutgoingPos(base, time)
        return OutgoingPos(base.copy(trail = trail(prev, fix, base, time, numbers.trailToleranceM)), time)
    }

    /**
     * The way since [prev]: the good fixes between, simplified (Douglas–Peucker) to [toleranceM]
     * and further until the position fits [ConnectionKind.positionMax]; newest first.
     */
    private fun trail(prev: OutgoingPos, fix: GeoFix, base: LinkMessage.Pos, time: Long, toleranceM: Double): List<TrailPoint> {
        val between = way.filter { it.timeMs / 1000 in (prev.time + 1) until time }
        if (between.isEmpty()) return emptyList()
        val points = buildList {
            add(GeoPoint(prev.pos.at.latDeg, prev.pos.at.lonDeg))
            between.forEach { add(GeoPoint(it.lat, it.lon, time = it.timeMs / 1000)) }
            add(GeoPoint(fix.lat, fix.lon))
        }
        var tolerance = toleranceM
        while (true) {
            val kept = TrackLink.douglasPeucker(points, tolerance / DeadReckoning.M_PER_DEG)
            var trail = kept.drop(1).dropLast(1).map { TrailPoint(GridPoint.of(points[it].lat, points[it].lon), points[it].time!!) }
                .distinctBy { it.time }.reversed()
            if (tolerance >= MAX_TOLERANCE_M) {
                // Still too long: the oldest points go.
                while (trail.isNotEmpty() && size(base.copy(trail = trail), time) > kind.positionMax) trail = trail.dropLast(1)
                return trail
            }
            if (size(base.copy(trail = trail), time) <= kind.positionMax) return trail
            tolerance *= 2
        }
    }

    private fun size(pos: LinkMessage.Pos, time: Long) = Marks.sealedSize(pos, 1, time)

    private fun isGood(fix: GeoFix) = (fix.accuracyM ?: 0f) <= LinkMessage.Pos.GOOD_FIX_M

    private companion object {
        /** Moving from this speed, still below [STOP_KMH] (in between: as before). */
        const val START_KMH = 3.0
        const val STOP_KMH = 1.5

        /** A fix older than this isn't where I am now. */
        const val CURRENT_MS = 30_000L

        /** A position held back for airtime is tried again this often. */
        const val HELD_RETRY_MS = 1_000L

        /** Fixes kept for a trail: half an hour at one a second. */
        const val MAX_WAY = 1800

        const val MAX_TOLERANCE_M = 5000.0
    }
}
