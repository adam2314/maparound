package app.maparound.party.link

import app.maparound.party.ChatLine
import app.maparound.party.PartyKey
import app.maparound.routing.Geo
import app.maparound.sensors.GeoFix
import kotlin.random.Random

// Plain Kotlin: one party over Link v1 (docs/LINK_PROTOCOL.md), on any number of connections. It
// takes packets ([receive]), the clock ([tick]) and my actions, and returns the packets to send
// on each connection; the connections themselves (MQTT servers, radios) are the platform's. Time
// is always passed in, so it runs the same in tests. Unit-tested on a simulated network.

/** My name and look in the party. */
data class LinkProfile(val name: String, val rgb: Int, val icon: Int = 0)

/** Another member, as last heard. Times in Unix seconds, except [heard] (ms). */
data class LinkMember(
    val id: Int,
    val name: String = "",
    val rgb: Int = 0x808080,
    val icon: Int = 0,
    val meshNode: Long? = null,
    val mode: ModeInfo? = null,
    val radio: Boolean = false,
    val gateway: Boolean = false,
    /** The newest position good enough to draw, and when it was taken. */
    val position: LinkMessage.Pos? = null,
    val positionAt: Long = 0,
    /** The newest report of any quality (a sign of life, the battery). */
    val report: LinkMessage.Pos? = null,
    val reportAt: Long = 0,
    /** When each connection last brought something of theirs (ms). */
    val heard: Map<ConnectionId, Long> = emptyMap(),
    val sos: Boolean = false,
    val sosText: String = "",
    val left: Boolean = false,
    /** Their packets are dated more than 10 minutes ahead: dropped until their clock is right. */
    val clockWrong: Boolean = false,
    val marks: MarksSummary? = null,
) {
    val lastHeard: Long get() = heard.values.maxOrNull() ?: 0
}

sealed class LinkEvent {
    data class Joined(val member: LinkMember) : LinkEvent()
    data class Left(val member: LinkMember) : LinkEvent()
    data class SosRaised(val member: LinkMember, val text: String) : LinkEvent()
    data class SosCancelled(val member: LinkMember) : LinkEvent()

    /** [member] got my SOS. */
    data class SosSeen(val member: LinkMember) : LinkEvent()
    data class TextReceived(val member: LinkMember, val text: String) : LinkEvent()
    data class MarkChanged(val mark: MarkEntry) : LinkEvent()

    /** Party Live started, changed, or ended (null). */
    data class LiveChanged(val live: LiveInfo?) : LinkEvent()
    data class ClockWrong(val sender: Int) : LinkEvent()
}

/** A packet to send on connection [to]. [snapshot]: a mark's full state, for its retained topic online. */
class LinkOut(val bytes: ByteArray, val to: ConnectionId, val bulk: Boolean = false, val snapshot: MarkId? = null) {
    val header: LinkHeader get() = LinkEnvelope.header(bytes)!!
}

class LinkResult(val events: List<LinkEvent> = emptyList(), val out: List<LinkOut> = emptyList())

/** The phone's side of things at a [LinkParty.tick]. */
data class LinkDevice(
    val nowMs: Long,
    val batteryPct: Int? = null,
    val charging: Boolean = false,
    /** A radio's mesh is busy. */
    val meshBusy: (ConnectionId) -> Boolean = { false },
    /** A connection can take a packet now (a radio's airtime budget). */
    val canSend: (ConnectionId) -> Boolean = { true },
)

/** A connection as this party uses it. */
class PartyConnection internal constructor(val id: ConnectionId, val kind: ConnectionKind, internal var sinceMs: Long, up: Boolean) {
    /** Connected (online: subscribed to the party): only then does anything go to it. */
    var up: Boolean = up
        internal set
    internal val positions = PositionSender(kind)
    internal val auto = AutoMode()
    internal var lastHelloMs: Long? = null
    internal var lastReplyMs: Long? = null
    internal var helloMode: ModeInfo? = null
    internal var lastSosMs: Long? = null
    internal var lastPartsWantMs = 0L

    /** When a DIGEST with my summary last went over it (mine or someone else's). */
    internal var lastDigestMs = 0L

    /** Online, since it came up: the marks the server is known to hold, and whether it holds a LIVE. */
    internal val delivered = HashSet<MarkId>()
    internal var liveDelivered = false

    /** The mode my positions follow here now. */
    var mode: ModeInfo? = null
        internal set

    /** The largest packet that goes here. */
    val maxPacket: Int get() = if (kind == ConnectionKind.ONLINE) LinkEnvelope.ONLINE_MAX else LinkEnvelope.LORA_MAX

    /** No mailbox keeps the party's state here: members catch up with DIGEST and WANT. */
    internal val catchesUp: Boolean get() = kind != ConnectionKind.ONLINE
}

/**
 * One party, me in it as [me] (my sender id). [seq], and the marks' counter and clock, are kept
 * across restarts by the caller ([seq], [marks]). [random] times the answers that wait (so that
 * not everyone answers at once).
 */
class LinkParty(
    val party: PartyKey,
    val me: Int,
    var profile: LinkProfile,
    seq: Int = Random.nextInt(65_536),
    marksCounter: Long = 1,
    marksClock: Long = 0,
    private val random: Random = Random.Default,
) {
    init {
        require(me != 0)
    }

    val key = LinkKey.of(party)
    val marks = MarkBook(me, marksCounter, marksClock)

    /** The last sequence number used. */
    var seq: Int = seq and 0xFFFF
        private set

    val members = LinkedHashMap<Int, LinkMember>()
    val history = LinkHistory()

    /** The messages in the order they were written, the last [MAX_CHAT]. */
    val chat = ArrayDeque<ChatLine>()

    /** The newest party Live known, on or ended (it's what a later one is compared with). */
    var live: LiveInfo? = null
        private set
    private var liveBytes: ByteArray? = null
    private var liveOn = false

    fun isLive(nowS: Long): Boolean = live?.let { it.until > nowS } == true

    /** Relay between connections (a gateway). */
    var gateway: Boolean = true

    /** Share my position (off: see the others, stay hidden). */
    var sharing: Boolean = true

    /** My Meshtastic node, if I have a radio. */
    var meshNode: Long? = null

    /** My secret for this party on private servers (docs/LINK_PROTOCOL.md, "Private servers"), kept by the caller. */
    var token: String? = null

    /** What a private server gets for this party: `tag.sender.token`; null without a token. */
    fun credential(): String? = token?.let { "${key.tagHex}.${app.maparound.shared.util.hex(me).padStart(8, '0')}.$it" }

    /** A mode I pinned (null: Auto), on every connection. */
    var pinnedMode: LinkMode? = null
        set(value) {
            field = value
            conns.values.forEach { it.auto.pinned = value }
        }

    var mySos: Boolean = false
        private set
    private var mySosText = ""
    private val mySosRefs = HashSet<PacketRef>()

    /** Who got my SOS. */
    val sosSeenBy = LinkedHashSet<Int>()

    private val conns = LinkedHashMap<ConnectionId, PartyConnection>()
    val connections: Collection<PartyConnection> get() = conns.values

    private val seen = LinkedHashSet<PacketRef>()
    private val relayedTo = LinkedHashMap<PacketRef, MutableSet<ConnectionId>>()
    private val lastPosRelay = HashMap<Pair<Int, ConnectionId>, Long>()
    private val jobs = ArrayList<Job>()

    /** Each member's latest HELLO, POS and SOS, as they came: what a gateway introduces them with. */
    private val latest = HashMap<Int, HashMap<Int, ByteArray>>()

    private var lastFix: GeoFix? = null
    private var moving = false
    private var stillSinceS: Long? = null

    // Connections.

    /** A connection this party uses; [up]: connected already (else [connectionUp] when it is). */
    fun addConnection(id: ConnectionId, kind: ConnectionKind, nowMs: Long, up: Boolean = true): PartyConnection =
        conns.getOrPut(id) { PartyConnection(id, kind, nowMs, up).also { it.auto.pinned = pinnedMode } }

    fun removeConnection(id: ConnectionId) {
        conns.remove(id)
        jobs.removeAll { it.conn == id }
    }

    /**
     * [id] is connected (again): my hello now and my position at the next tick, my SOS if I'm in
     * one. Online, the server then gives back what it keeps; a little later, whatever of my marks
     * (and Live) it didn't turn out to hold is published, so marks made or changed while it was
     * away reach it.
     */
    fun connectionUp(id: ConnectionId, nowMs: Long): LinkResult {
        val c = conns[id] ?: return NOTHING
        c.up = true
        c.sinceMs = nowMs
        c.positions.reset()
        c.lastSosMs = null
        c.delivered.clear()
        c.liveDelivered = false
        jobs.removeAll { it.conn == id }
        val out = arrayListOf(hello(c, nowMs))
        if (c.kind == ConnectionKind.ONLINE) {
            if (mySos) {
                out += LinkOut(sealSos(nowMs / 1000), id)
                c.lastSosMs = nowMs
            }
            jobs += SettleJob(nowMs + SETTLE_MS, id)
        }
        return LinkResult(out = out)
    }

    /** [id] dropped: nothing goes to it until [connectionUp]. */
    fun connectionDown(id: ConnectionId) {
        val c = conns[id] ?: return
        c.up = false
        jobs.removeAll { it.conn == id }
    }

    // Receiving.

    /**
     * A packet that came over [via]. [snapshot]: kept by the server and delivered on subscribing
     * (retained), not sent just now: it announces no one. [kept] (online): it came on the topic
     * the server keeps it on (a mark's own, `live`), not as a relay's post of a radio MARK. A
     * mark's or Live's state there older than mine is answered with mine.
     */
    fun receive(bytes: ByteArray, via: ConnectionId, nowMs: Long, snapshot: Boolean = false, kept: Boolean = true): LinkResult {
        val h = LinkEnvelope.header(bytes) ?: return NOTHING
        if (h.tag != key.tag) return NOTHING
        val conn = conns[via]?.takeIf { it.up } ?: return NOTHING
        val holds = conn.kind == ConnectionKind.ONLINE && kept
        if (h.sender == me) return if (holds) ownState(bytes, conn, nowMs, snapshot) else NOTHING
        val nowS = nowMs / 1000
        if (h.time > nowS + MarkBook.FUTURE_S) {
            val m = members[h.sender] ?: LinkMember(h.sender)
            if (m.clockWrong) return NOTHING
            members[h.sender] = m.copy(clockWrong = true)
            return LinkResult(listOf(LinkEvent.ClockWrong(h.sender)))
        }
        val packet = LinkEnvelope.open(key, bytes) ?: return NOTHING
        val ref = h.ref
        // Someone else already put it where I was about to relay it.
        jobs.removeAll { it is RelayJob && it.ref == ref && it.conn == via }
        val myRelayCameBack = relayedTo[ref]?.contains(via) == true
        val known = members[h.sender]
        var m = (known ?: LinkMember(h.sender)).copy(clockWrong = false)
        if (!myRelayCameBack) m = m.copy(heard = m.heard + (via to nowMs))
        if (!seen.add(ref)) {
            members[h.sender] = m
            // A server gives back, on subscribing, what I've seen before: still, if mine is newer
            // (changed while I wasn't connected there), it gets mine.
            when (val msg = packet.message) {
                is LinkMessage.Mark -> if (holds) held(msg, marks.receive(msg, via, nowS), conn, nowMs)
                is LinkMessage.Live -> if (holds) heldLive(LiveInfo(minOf(msg.until, h.time + LinkMessage.Live.MAX_SECONDS), h.time, h.sender), conn, nowMs)
                else -> Unit
            }
            return NOTHING
        }
        while (seen.size > MAX_SEEN) seen.remove(seen.first())

        val events = ArrayList<LinkEvent>()
        val out = ArrayList<LinkOut>()
        var relay = gateway
        when (val msg = packet.message) {
            is LinkMessage.Pos -> {
                if (h.time >= m.reportAt) {
                    if (msg.sos && !m.sos) events += LinkEvent.SosRaised(m, m.sosText)
                    if (!msg.sos && m.sos) events += LinkEvent.SosCancelled(m)
                    m = m.copy(report = msg, reportAt = h.time, sos = msg.sos, left = false, mode = msg.mode ?: m.mode)
                }
                if (msg.isGoodFix) {
                    history.add(h.sender, h.time, msg.at)
                    if (h.time >= m.positionAt) m = m.copy(position = msg, positionAt = h.time)
                }
                msg.trail.forEach { history.add(h.sender, it.time, it.at) }
            }
            is LinkMessage.Hello -> {
                m = m.copy(
                    name = msg.name, rgb = msg.rgb, icon = msg.icon, meshNode = msg.meshNode, mode = msg.mode,
                    radio = msg.radio, gateway = msg.gateway, marks = msg.marks, left = false,
                )
                msg.live?.let { l ->
                    val info = l.copy(until = minOf(l.until, l.time + LinkMessage.Live.MAX_SECONDS))
                    if (newer(info, live)) setLive(info, null, nowS, events)
                }
                // A summary I answered already starts no new round, unless that was a while ago (a page lost on the air).
                val answered = msg.marks == known?.marks && nowMs - conn.lastDigestMs < DIGEST_AGAIN_MS
                if (conn.catchesUp && msg.marks != null && msg.marks != marks.summary() && !answered) scheduleDigest(conn, nowMs)
            }
            is LinkMessage.Sos -> {
                if (msg.active && !m.sos) events += LinkEvent.SosRaised(m.copy(sosText = msg.text), msg.text)
                if (!msg.active && m.sos) events += LinkEvent.SosCancelled(m)
                m = m.copy(sos = msg.active, sosText = msg.text)
                if (msg.active) {
                    val ack = { seal(LinkMessage.Ack(listOf(ref)), nowS) }
                    if (conn.kind == ConnectionKind.ONLINE) out += LinkOut(ack(), via)
                    else jobs += AckJob(nowMs + random.nextLong(ACK_JITTER_MS), via, ref)
                }
            }
            is LinkMessage.Ack -> msg.refs.filter { it in mySosRefs }.forEach { _ ->
                if (sosSeenBy.add(h.sender)) events += LinkEvent.SosSeen(m)
            }
            is LinkMessage.Text -> {
                addChat(ChatLine(h.sender, msg.text, h.time * 1000))
                if (!snapshot) events += LinkEvent.TextReceived(m, msg.text)
            }
            LinkMessage.Leave -> {
                m = m.copy(left = true, sos = false)
                events += LinkEvent.Left(m)
            }
            is LinkMessage.Mark -> {
                relay = false // marks go on by their own rules
                val receipt = marks.receive(msg, via, nowS)
                (jobs.find { it is WantJob && it.conn == via } as WantJob?)?.entries
                    ?.remove(WantEntry.Fingerprint(Marks.fingerprint(receipt.entry.full()) and 0xFFFF))
                if (!receipt.behind) (jobs.find { it is MarksJob && it.conn == via } as MarksJob?)?.ids?.remove(msg.id)
                if (holds) held(msg, receipt, conn, nowMs)
                if (receipt.changed) {
                    events += LinkEvent.MarkChanged(receipt.entry)
                    // The others online get the new state from me, unless it came to them already.
                    conns.values.filter { it.kind == ConnectionKind.ONLINE && it.up && !(it.id == via && holds && !receipt.behind) }
                        .forEach { scheduleSnapshot(it.id, msg.id, nowMs) }
                    if (gateway) out += relayMark(bytes, msg, via, nowS, bulk = snapshot)
                }
            }
            is LinkMessage.Digest -> {
                relay = false
                m = m.copy(marks = msg.summary)
                if (conn.catchesUp) {
                    val mine = marks.summary()
                    val (want, send) = marks.compare(msg)
                    if (msg.summary == mine) {
                        jobs.removeAll { it is DigestJob && it.conn == via }
                        conn.lastDigestMs = nowMs
                    }
                    // They may hold what I lack: my pages too. (Only behind, my marks answer them.)
                    else if (want.isNotEmpty() || msg.pages > 1) scheduleDigest(conn, nowMs)
                    if (want.isNotEmpty()) wantJob(via, nowMs).entries.addAll(want.map { WantEntry.Fingerprint(it) })
                    if (send.isNotEmpty()) marksJob(via, nowMs).ids.putAll(send.associateWith { null })
                }
            }
            is LinkMessage.Want -> {
                relay = false
                if (conn.catchesUp) {
                    // Someone asked already: the answer reaches me too.
                    (jobs.find { it is WantJob && it.conn == via } as WantJob?)?.entries?.removeAll(msg.entries.toSet())
                    val parts = msg.entries.filterIsInstance<WantEntry.Parts>().associate { it.id to it.missing }
                    val wanted = marks.wanted(msg)
                    if (wanted.isNotEmpty()) {
                        val job = marksJob(via, nowMs)
                        wanted.forEach { w -> job.ids[w.id] = parts[w.id]?.let { mask -> (job.ids[w.id] ?: 0) or mask } }
                    }
                }
            }
            is LinkMessage.Live -> {
                val info = LiveInfo(minOf(msg.until, h.time + LinkMessage.Live.MAX_SECONDS), h.time, h.sender)
                if (newer(info, live)) setLive(info, bytes, nowS, events)
                if (holds) heldLive(info, conn, nowMs)
            }
            is LinkMessage.Unknown -> Unit
        }
        if (known == null && !m.left && !snapshot) {
            events.add(0, LinkEvent.Joined(m))
            // Introduce myself to newcomers, at most every 2 minutes (my last hello may have gone before they were there).
            if (conn.lastReplyMs?.let { nowMs - it > HELLO_REPLY_MS } != false) {
                out += hello(conn, nowMs)
                conn.lastReplyMs = nowMs
            }
        }
        members[h.sender] = m
        if (m.left) latest.remove(h.sender)
        else if (h.type == LinkType.HELLO || h.type == LinkType.POS || h.type == LinkType.SOS) {
            val kept = latest.getOrPut(h.sender) { HashMap() }
            if (kept[h.type]?.let { LinkEnvelope.header(it)!!.time > h.time } != true) kept[h.type] = bytes
        }
        if (relay) out += relayRaw(bytes, h, packet.message, via, nowMs, snapshot)
        return LinkResult(events, out)
    }

    private fun relayRaw(bytes: ByteArray, h: LinkHeader, msg: LinkMessage, from: ConnectionId, nowMs: Long, snapshot: Boolean): List<LinkOut> {
        val relayed = LinkEnvelope.relayed(bytes) ?: return emptyList()
        val nowS = nowMs / 1000
        val out = ArrayList<LinkOut>()
        for (to in conns.values) {
            if (to.id == from || !to.up) continue
            if (to.kind == ConnectionKind.ONLINE) {
                out += LinkOut(relayed, to.id)
                noteRelay(h.ref, to.id)
                continue
            }
            if (relayed.size > LinkEnvelope.LORA_MAX) continue
            if ((h.type == LinkType.POS || h.type == LinkType.HELLO) && nowS - h.time > STALE_S) continue
            // A server's log: a gateway connecting would otherwise put days of messages on the air.
            if (snapshot && h.type != LinkType.SOS && nowS - h.time > KEPT_ONTO_RADIO_S) continue
            if (msg is LinkMessage.Pos) {
                val k = h.sender to to.id
                val gap = if (msg.sos) SOS_RELAY_S else POS_RELAY_S
                if (lastPosRelay[k]?.let { nowS - it < gap } == true) continue
                lastPosRelay[k] = nowS
            }
            jobs += RelayJob(nowMs + random.nextLong(RELAY_JITTER_MS), to.id, relayed, h.ref)
        }
        return out
    }

    /** A mark that changed my state goes on to my radios: as it came if it fits, else from me in parts. */
    private fun relayMark(bytes: ByteArray, msg: LinkMessage.Mark, from: ConnectionId, nowS: Long, bulk: Boolean): List<LinkOut> {
        val relayed = LinkEnvelope.relayed(bytes)
        val out = ArrayList<LinkOut>()
        for (to in conns.values) {
            if (to.id == from || !to.up || to.kind == ConnectionKind.ONLINE) continue
            if (relayed != null && relayed.size <= to.maxPacket) {
                out += LinkOut(relayed, to.id, bulk)
                LinkEnvelope.header(relayed)?.let { noteRelay(it.ref, to.id) }
            } else {
                val full = marks[msg.id]?.full() ?: continue
                Marks.split(full, me, nowS, to.maxPacket).forEach { out += LinkOut(seal(it, nowS), to.id, bulk) }
            }
        }
        return out
    }

    private fun noteRelay(ref: PacketRef, to: ConnectionId) {
        relayedTo.getOrPut(ref) { HashSet() } += to
        while (relayedTo.size > MAX_RELAYED) relayedTo.remove(relayedTo.keys.first())
    }

    // What a server holds.

    /**
     * Online, [conn]'s server holds [mark] (its full state) as it came: older than mine, it gets
     * mine after a wait; otherwise nobody needs to send it.
     */
    private fun held(mark: LinkMessage.Mark, receipt: MarkReceipt, conn: PartyConnection, nowMs: Long) {
        conn.delivered += mark.id
        if (receipt.behind) scheduleSnapshot(conn.id, mark.id, nowMs)
        else jobs.removeAll { it is SnapshotJob && it.conn == conn.id && it.id == mark.id }
    }

    /** The same for the party's Live: the server holds [info]. */
    private fun heldLive(info: LiveInfo, conn: PartyConnection, nowMs: Long) {
        conn.liveDelivered = true
        val mine = live
        if (mine != null && liveBytes != null && newer(mine, info)) {
            if (jobs.none { it is LiveJob && it.conn == conn.id }) jobs += LiveJob(nowMs + random.nextLong(SNAPSHOT_MIN_MS, SNAPSHOT_MAX_MS), conn.id)
        } else {
            jobs.removeAll { it is LiveJob && it.conn == conn.id }
        }
    }

    /**
     * My own packets, as a server gives them back: the marks and Live it holds (after a restart
     * they may be all I have of them), and on subscribing my SOS as it was kept (not as it is now:
     * mine goes again).
     */
    private fun ownState(bytes: ByteArray, conn: PartyConnection, nowMs: Long, snapshot: Boolean): LinkResult {
        val packet = LinkEnvelope.open(key, bytes) ?: return NOTHING
        val h = packet.header
        val nowS = nowMs / 1000
        val events = ArrayList<LinkEvent>()
        val out = ArrayList<LinkOut>()
        when (val msg = packet.message) {
            is LinkMessage.Mark -> {
                val receipt = marks.receive(msg, conn.id, nowS)
                held(msg, receipt, conn, nowMs)
                if (receipt.changed) events += LinkEvent.MarkChanged(receipt.entry)
            }
            is LinkMessage.Live -> {
                val info = LiveInfo(minOf(msg.until, h.time + LinkMessage.Live.MAX_SECONDS), h.time, h.sender)
                if (newer(info, live)) setLive(info, bytes, nowS, events)
                heldLive(info, conn, nowMs)
            }
            is LinkMessage.Sos -> if (snapshot && msg.active != mySos) out += LinkOut(sealSos(nowS), conn.id)
            else -> Unit
        }
        return LinkResult(events, out)
    }

    // The clock.

    /** Feeds my position to every connection's sender. */
    fun onFix(fix: GeoFix) {
        lastFix = fix
        fix.speedMps?.let { mps ->
            val kmh = mps * 3.6
            if (kmh >= 3.0) moving = true else if (kmh < 1.5) moving = false
        }
        if (moving) stillSinceS = null else if (stillSinceS == null) stillSinceS = fix.timeMs / 1000
        conns.values.forEach { it.positions.onFix(fix) }
    }

    /** About once a second: what's due now (positions, hellos, repeats, answers that waited). */
    fun tick(device: LinkDevice): LinkResult {
        val now = device.nowMs
        val nowS = now / 1000
        val events = ArrayList<LinkEvent>()
        val out = ArrayList<LinkOut>()
        marks.expire(nowS)
        if (liveOn && !isLive(nowS)) {
            liveOn = false
            events += LinkEvent.LiveChanged(null)
        }
        for (c in conns.values) {
            if (!c.up) continue
            c.mode = c.auto.update(autoInputs(c, device))
            val helloEvery = if (c.kind == ConnectionKind.LORA) HELLO_RADIO_MS else HELLO_ONLINE_MS
            if (c.lastHelloMs?.let { now - it >= helloEvery } != false) out += hello(c, now)
            if (sharing || mySos) {
                val ctx = SendContext(now, c.mode!!, c.helloMode, device.batteryPct, device.charging, mySos, device.canSend(c.id))
                c.positions.next(ctx)?.let { out += LinkOut(seal(it.pos, it.time), c.id) }
            }
            if (mySos && c.kind != ConnectionKind.ONLINE) {
                val every = if (sosSeenBy.isEmpty()) SOS_REPEAT_MS else SOS_SEEN_REPEAT_MS
                if (c.lastSosMs?.let { now - it >= every } != false) {
                    out += LinkOut(sealSos(nowS), c.id)
                    c.lastSosMs = now
                }
            }
            if (c.catchesUp && now - c.lastPartsWantMs >= PARTS_WANT_MS) {
                val missing = marks.missingParts(nowS)
                if (missing.isNotEmpty()) {
                    out += LinkOut(seal(LinkMessage.Want(missing), nowS), c.id, bulk = true)
                    c.lastPartsWantMs = now
                }
            }
        }
        val due = jobs.filter { it.dueMs <= now }
        jobs.removeAll(due)
        for (job in due) out += run(job, now)
        return LinkResult(events, out)
    }

    /**
     * When [tick] next has something to do: positions' heartbeats and those held back, hellos, SOS
     * repeats, answers that wait, Live's end. At most [MAX_IDLE_MS] away, for what depends on time
     * passing (Auto's slower modes, shapes missing parts). A fix or a packet is ticked for anyway.
     */
    fun nextDueMs(nowMs: Long): Long {
        var next = nowMs + MAX_IDLE_MS
        fun at(t: Long) {
            if (t < next) next = t
        }
        jobs.forEach { at(it.dueMs) }
        if (liveOn) live?.let { at(it.until * 1000) }
        for (c in conns.values) {
            if (!c.up) continue
            val helloEvery = if (c.kind == ConnectionKind.LORA) HELLO_RADIO_MS else HELLO_ONLINE_MS
            at(c.lastHelloMs?.let { it + helloEvery } ?: nowMs)
            // No mode yet: the next tick sets it.
            if (sharing || mySos) at(c.mode?.let { c.positions.nextDueMs(nowMs, it.mode, mySos) } ?: nowMs)
            if (mySos && c.kind != ConnectionKind.ONLINE) {
                val every = if (sosSeenBy.isEmpty()) SOS_REPEAT_MS else SOS_SEEN_REPEAT_MS
                at(c.lastSosMs?.let { it + every } ?: nowMs)
            }
        }
        return maxOf(next, nowMs)
    }

    /** Whether I'm moving, as my fixes' speeds say. */
    val isMoving: Boolean get() = moving

    private fun run(job: Job, nowMs: Long): List<LinkOut> {
        val nowS = nowMs / 1000
        val c = conns[job.conn]?.takeIf { it.up } ?: return emptyList()
        return when (job) {
            is DigestJob -> {
                // Not when everyone heard here last said they hold what I hold (caught up meanwhile).
                val mine = marks.summary()
                val differs = members.values.any { !it.left && it.heard.containsKey(c.id) && it.marks != null && it.marks != mine }
                // With no marks there's nothing for others to lack: my HELLO says so, and I ask for theirs.
                if (!differs || mine.count == 0) return emptyList()
                c.lastDigestMs = nowMs
                marks.digest().map { LinkOut(seal(it, nowS), c.id, bulk = true) }
            }
            is WantJob -> if (job.entries.isEmpty()) emptyList() else listOf(LinkOut(seal(LinkMessage.Want(job.entries.toList()), nowS), c.id, bulk = true))
            is MarksJob -> job.ids.flatMap { (id, mask) ->
                val full = marks[id]?.full() ?: return@flatMap emptyList()
                val pieces = Marks.split(full, me, nowS, c.maxPacket)
                fun part(p: LinkMessage.Mark) = (p.geom?.value as? Geom.Path)?.part
                // The parts asked for; all of it when it goes whole from me.
                val chosen = if (mask == null || pieces.none { part(it) != null }) pieces
                else pieces.filter { p -> part(p)?.let { mask and (1 shl it.index) != 0 } == true }
                chosen.map { LinkOut(seal(it, nowS), c.id, bulk = true) }
            }
            is SnapshotJob -> marks[job.id]?.full()?.let { c.delivered += job.id; listOf(LinkOut(seal(it, nowS), c.id, snapshot = job.id)) }.orEmpty()
            is RelayJob -> listOf(LinkOut(job.bytes, c.id)).also { noteRelay(job.ref, c.id) }
            is AckJob -> listOf(LinkOut(seal(LinkMessage.Ack(listOf(job.ref)), nowS), c.id))
            is LiveJob -> liveBytes?.let { c.liveDelivered = true; listOf(LinkOut(it, c.id)) }.orEmpty()
            is SettleJob -> {
                // What the server didn't give back since it came up, it lacks: it gets mine.
                marks.fingerprints().keys.filter { it !in c.delivered }.forEach { scheduleSnapshot(c.id, it, nowMs) }
                if (isLive(nowS) && !c.liveDelivered && liveBytes != null) jobs += LiveJob(nowMs, c.id)
                // A gateway: members it knows from elsewhere whom this server didn't bring, it
                // introduces (their latest hello, position and SOS, as they sent them).
                if (!gateway) return emptyList()
                latest.flatMap { (sender, packets) ->
                    val mem = members[sender]
                    if (mem == null || mem.left || (mem.heard[c.id] ?: -1) >= c.sinceMs || mem.heard.keys.none { it != c.id }) return@flatMap emptyList()
                    packets.mapNotNull { (type, bytes) ->
                        if (type == LinkType.SOS && !mem.sos) return@mapNotNull null
                        val relayed = LinkEnvelope.relayed(bytes) ?: return@mapNotNull null
                        LinkEnvelope.header(bytes)?.let { noteRelay(it.ref, c.id) }
                        LinkOut(relayed, c.id)
                    }
                }
            }
        }
    }

    private fun autoInputs(c: PartyConnection, d: LinkDevice): AutoInputs {
        val nowS = d.nowMs / 1000
        val fix = lastFix
        val others = members.values.filter { !it.left }
        val nearest = if (fix == null) null else others.mapNotNull { m ->
            m.position?.takeIf { nowS - m.positionAt <= NEAR_FRESH_S }?.let { Geo.distance(fix.lat, fix.lon, it.at.latDeg, it.at.lonDeg) }
        }.minOrNull()
        val meeting = marks[MarkId.MEETING]?.takeIf { it.isShown }?.geom?.value as? Geom.Point
        val heardHere = others.mapNotNull { it.heard[c.id] }.maxOrNull() ?: c.sinceMs
        return AutoInputs(
            nowS = nowS,
            batteryPct = d.batteryPct,
            charging = d.charging,
            moving = moving,
            stillForS = stillSinceS?.let { nowS - it } ?: 0,
            nearestMemberM = nearest,
            meetingPointM = if (fix != null && meeting != null) Geo.distance(fix.lat, fix.lon, meeting.at.latDeg, meeting.at.lonDeg) else null,
            mySos = mySos,
            sosInParty = others.any { it.sos },
            partyLiveUntil = live?.until ?: 0,
            aloneForS = (d.nowMs - heardHere) / 1000,
            meshBusy = c.kind == ConnectionKind.LORA && d.meshBusy(c.id),
        )
    }

    // My actions. Each returns what to send.

    fun sos(active: Boolean, text: String, nowMs: Long): LinkResult {
        mySos = active
        mySosText = text
        if (active) sosSeenBy.clear()
        val bytes = sealSos(nowMs / 1000)
        conns.values.forEach { it.lastSosMs = nowMs }
        return toAll(bytes)
    }

    private fun sealSos(nowS: Long): ByteArray =
        seal(LinkMessage.Sos(mySos, mySosText), nowS).also { b -> if (mySos) LinkEnvelope.header(b)?.let { mySosRefs += it.ref } }

    fun text(text: String, nowMs: Long): LinkResult {
        val nowS = nowMs / 1000
        val bytes = seal(LinkMessage.Text(text), nowS)
        addChat(ChatLine(me, utf8Cut(text, LinkMessage.Text.TEXT_MAX).decodeToString(), nowS * 1000))
        return toAll(bytes)
    }

    /** Puts the whole party in Live for [seconds] (at most 12 hours). */
    fun startLive(seconds: Long, nowMs: Long): LinkResult {
        val nowS = nowMs / 1000
        return sendLive(nowS + seconds.coerceIn(1, LinkMessage.Live.MAX_SECONDS), nowS)
    }

    fun stopLive(nowMs: Long): LinkResult = sendLive(0, nowMs / 1000)

    private fun sendLive(until: Long, nowS: Long): LinkResult {
        val bytes = seal(LinkMessage.Live(until), nowS)
        val events = ArrayList<LinkEvent>()
        setLive(LiveInfo(until, nowS, me), bytes, nowS, events)
        for (c in conns.values) if (c.up && c.kind == ConnectionKind.ONLINE) c.liveDelivered = true
        jobs.removeAll { it is LiveJob }
        return LinkResult(events, toAll(bytes).out)
    }

    fun leave(nowMs: Long): LinkResult = toAll(seal(LinkMessage.Leave, nowMs / 1000))

    /** My hello on every connection now: my name or look changed. */
    fun helloNow(nowMs: Long): LinkResult = LinkResult(out = conns.values.filter { it.up }.map { hello(it, nowMs) })

    fun createMark(nowMs: Long, geom: Geom, style: Style? = null, text: MarkText? = null) =
        markOut(marks.create(nowMs / 1000, geom, style, text), nowMs)

    fun editMark(id: MarkId, nowMs: Long, geom: Geom? = null, style: Style? = null, text: MarkText? = null) =
        markOut(marks.edit(id, nowMs / 1000, geom, style, text), nowMs)

    fun deleteMark(id: MarkId, nowMs: Long) = markOut(marks.delete(id, nowMs / 1000), nowMs)

    fun setMeeting(at: GridPoint, label: String, nowMs: Long) = markOut(marks.setMeeting(nowMs / 1000, at, label), nowMs)

    fun clearMeeting(nowMs: Long) = markOut(marks.clearMeeting(nowMs / 1000), nowMs)

    /** My change: online the mark's whole state (its retained topic), elsewhere the change, in parts if need be. */
    private fun markOut(change: LinkMessage.Mark, nowMs: Long): LinkResult {
        val nowS = nowMs / 1000
        val out = ArrayList<LinkOut>()
        var sealedChange: List<ByteArray>? = null
        for (c in conns.values) {
            if (!c.up) continue
            if (c.kind == ConnectionKind.ONLINE) {
                out += LinkOut(seal(marks[change.id]!!.full(), nowS), c.id, snapshot = change.id)
                c.delivered += change.id
                jobs.removeAll { it is SnapshotJob && it.conn == c.id && it.id == change.id }
            } else {
                val parts = sealedChange ?: Marks.split(change, me, nowS).map { seal(it, nowS) }.also { sealedChange = it }
                parts.forEach { out += LinkOut(it, c.id) }
            }
        }
        return LinkResult(listOf(LinkEvent.MarkChanged(marks[change.id]!!)), out)
    }

    // Helpers.

    private fun hello(c: PartyConnection, nowMs: Long): LinkOut {
        val mode = c.mode ?: c.auto.update(AutoInputs(nowMs / 1000))
        val l = live?.takeIf { isLive(nowMs / 1000) }
        val hello = LinkMessage.Hello(
            profile.name, profile.rgb, profile.icon, mode,
            radio = conns.values.any { it.kind == ConnectionKind.LORA },
            gateway = gateway && conns.values.count { it.up } > 1,
            meshNode = meshNode, marks = marks.summary(), live = l,
        )
        c.lastHelloMs = nowMs
        c.helloMode = mode
        return LinkOut(seal(hello, nowMs / 1000), c.id)
    }

    private fun toAll(bytes: ByteArray) = LinkResult(out = conns.values.filter { it.up }.map { LinkOut(bytes, it.id) })

    private fun seal(m: LinkMessage, timeS: Long): ByteArray {
        seq = (seq + 1) and 0xFFFF
        return LinkEnvelope.seal(key, me, timeS, seq, m)
    }

    /** Newer: later sent, then the higher sender id (unsigned). */
    private fun newer(a: LiveInfo, b: LiveInfo?): Boolean =
        b == null || a.time > b.time || (a.time == b.time && a.by.toUInt() > b.by.toUInt())

    private fun setLive(info: LiveInfo, bytes: ByteArray?, nowS: Long, events: MutableList<LinkEvent>) {
        live = info
        liveBytes = bytes ?: liveBytes
        val on = info.until > nowS
        if (on || liveOn) events += LinkEvent.LiveChanged(info.takeIf { on })
        liveOn = on
    }

    private fun addChat(line: ChatLine) {
        var i = chat.size
        while (i > 0 && chat[i - 1].at > line.at) i--
        chat.add(i, line)
        while (chat.size > MAX_CHAT) chat.removeFirst()
    }

    private fun scheduleDigest(c: PartyConnection, nowMs: Long) {
        if (jobs.none { it is DigestJob && it.conn == c.id }) jobs += DigestJob(nowMs + random.nextLong(DIGEST_MIN_MS, DIGEST_MAX_MS), c.id)
    }

    private fun scheduleSnapshot(conn: ConnectionId, id: MarkId, nowMs: Long) {
        if (jobs.none { it is SnapshotJob && it.conn == conn && it.id == id }) jobs += SnapshotJob(nowMs + random.nextLong(SNAPSHOT_MIN_MS, SNAPSHOT_MAX_MS), conn, id)
    }

    private fun wantJob(conn: ConnectionId, nowMs: Long): WantJob =
        jobs.find { it is WantJob && it.conn == conn } as WantJob? ?: WantJob(nowMs + answerDelay(), conn).also { jobs += it }

    private fun marksJob(conn: ConnectionId, nowMs: Long): MarksJob =
        jobs.find { it is MarksJob && it.conn == conn } as MarksJob? ?: MarksJob(nowMs + answerDelay(), conn).also { jobs += it }

    private fun answerDelay() = random.nextLong(ANSWER_MIN_MS, ANSWER_MAX_MS)

    /** Something waiting for its time on one connection. */
    private sealed class Job(val dueMs: Long, val conn: ConnectionId)
    private class DigestJob(dueMs: Long, conn: ConnectionId) : Job(dueMs, conn)
    private class WantJob(dueMs: Long, conn: ConnectionId, val entries: LinkedHashSet<WantEntry> = LinkedHashSet()) : Job(dueMs, conn)

    /** Marks to send in full: by id, the parts asked for (bit i: part i), null for all of it. */
    private class MarksJob(dueMs: Long, conn: ConnectionId, val ids: LinkedHashMap<MarkId, Int?> = LinkedHashMap()) : Job(dueMs, conn)
    private class SnapshotJob(dueMs: Long, conn: ConnectionId, val id: MarkId) : Job(dueMs, conn)
    private class RelayJob(dueMs: Long, conn: ConnectionId, val bytes: ByteArray, val ref: PacketRef) : Job(dueMs, conn)
    private class AckJob(dueMs: Long, conn: ConnectionId, val ref: PacketRef) : Job(dueMs, conn)
    private class LiveJob(dueMs: Long, conn: ConnectionId) : Job(dueMs, conn)

    /** Online, a while after coming up: whatever the server didn't give back is published. */
    private class SettleJob(dueMs: Long, conn: ConnectionId) : Job(dueMs, conn)

    companion object {
        const val MAX_CHAT = 100
        private const val MAX_SEEN = 20_000
        private const val MAX_RELAYED = 2_000
        private val NOTHING = LinkResult()

        const val HELLO_ONLINE_MS = 10 * 60_000L
        const val HELLO_RADIO_MS = 30 * 60_000L
        const val HELLO_REPLY_MS = 2 * 60_000L
        const val SOS_REPEAT_MS = 60_000L
        const val SOS_SEEN_REPEAT_MS = 10 * 60_000L
        const val PARTS_WANT_MS = 60_000L

        /** [nextDueMs] is at most this far away. */
        const val MAX_IDLE_MS = 30_000L

        /** Online: how long after coming up the server counts as having given back what it holds. */
        const val SETTLE_MS = 10_000L

        /** Waits, so that not everyone answers at once (§5, §8). */
        const val ACK_JITTER_MS = 10_000L
        const val RELAY_JITTER_MS = 3_000L
        const val DIGEST_MIN_MS = 5_000L
        const val DIGEST_MAX_MS = 30_000L

        /** A summary already answered with a DIGEST is answered again after this long. */
        const val DIGEST_AGAIN_MS = 5 * 60_000L
        const val ANSWER_MIN_MS = 1_000L
        const val ANSWER_MAX_MS = 8_000L
        const val SNAPSHOT_MIN_MS = 1_000L
        const val SNAPSHOT_MAX_MS = 5_000L

        /** Onto a radio: a member's position at most this often (s), an SOS's more often. */
        const val POS_RELAY_S = 60L
        const val SOS_RELAY_S = 30L

        /** Positions and hellos older than this don't go onto a radio (s). */
        const val STALE_S = 120L

        /**
         * What a server hands over on subscribing (a party server's log of messages) goes onto a
         * radio only this fresh (s), an SOS whatever its age: the radio heard the rest when it was new.
         */
        const val KEPT_ONTO_RADIO_S = 30 * 60L

        /** Members' positions older than this don't count as near (s). */
        const val NEAR_FRESH_S = 600L
    }
}

/** Members' ways: their positions and trails, by time, the last [maxPerMember] each. */
class LinkHistory(private val maxPerMember: Int = 5000) {
    private val byMember = HashMap<Int, ArrayList<TrailPoint>>()

    fun add(member: Int, time: Long, at: GridPoint) {
        val list = byMember.getOrPut(member) { ArrayList() }
        var i = list.size
        while (i > 0 && list[i - 1].time > time) i--
        if (i > 0 && list[i - 1].time == time) return
        list.add(i, TrailPoint(at, time))
        if (list.size > maxPerMember) list.removeAt(0)
    }

    fun of(member: Int): List<TrailPoint> = byMember[member].orEmpty()

    /** How many points each member has. */
    val counts: Map<Int, Int> get() = byMember.filterValues { it.isNotEmpty() }.mapValues { it.value.size }
}
