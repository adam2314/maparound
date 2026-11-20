package app.maparound.party.link

import app.maparound.party.MqttSockets
import app.maparound.party.TransportStatus
import app.maparound.shared.util.nowMillis

// Plain Kotlin: parties on connections (docs/LINK_PROTOCOL.md §7). The hub owns the connections
// (MQTT servers, radios), passes what comes in to the parties it's for, and sends what they
// return: online to the topics of §6, onto a radio through its queue and airtime budget, shared
// by every party on it. Everything runs on one thread (the platform's main), driven by [tick]
// about once a second and by the connections' callbacks. Unit-tested on a fake broker and mesh.

/** A radio as the hub uses it: the platform's Meshtastic connection (Bluetooth, USB). */
fun interface LinkRadio {
    /** Broadcasts a Link packet on the radio's primary channel, on the PRIVATE_APP port. */
    fun send(bytes: ByteArray)
}

class LinkHub(
    private val sockets: MqttSockets,
    private val listener: Listener,
    private val clock: () -> Long = ::nowMillis,
) {
    interface Listener {
        fun onEvent(party: LinkParty, event: LinkEvent)
        fun onStatus(connection: ConnectionId, status: TransportStatus) {}

        /** Something came in or connected: [nextDueMs] may be sooner now. */
        fun onActivity() {}
    }

    /** One MQTT server or one radio, for the screens. */
    class Connection internal constructor(val id: ConnectionId, val kind: ConnectionKind) {
        var status = TransportStatus(TransportStatus.State.OFF)
            internal set

        /** A radio's mesh is busy (its channel is crowded): Auto steps down a mode there. */
        var busy = false
            internal set

        /** Packets waiting for the radio's airtime. */
        val waiting: Int get() = queue?.size ?: 0

        internal var mqtt: LinkMqtt? = null
        internal var radio: LinkRadio? = null
        internal val queue: RadioQueue? = if (kind == ConnectionKind.LORA) RadioQueue() else null
        internal var radioUp = false
    }

    private val conns = LinkedHashMap<ConnectionId, Connection>()
    private val uses = LinkedHashMap<LinkParty, MutableSet<ConnectionId>>()

    val connections: Collection<Connection> get() = conns.values
    val parties: Set<LinkParty> get() = uses.keys

    fun connection(id: ConnectionId): Connection? = conns[id]

    /** The connections [party] sends on. */
    fun uses(party: LinkParty): Set<ConnectionId> = uses[party].orEmpty()

    // Connections.

    /** An MQTT server ([urls]: tried in turn until one answers), connected from now on. */
    fun addServer(id: ConnectionId, urls: List<String>) {
        if (id in conns) return
        val c = Connection(id, ConnectionKind.ONLINE)
        conns[id] = c
        val mqtt = LinkMqtt(id, urls, sockets, clock, object : LinkMqtt.Listener {
            override fun onStatus(status: TransportStatus) {
                c.status = status
                listener.onStatus(id, status)
            }

            override fun onSubscribed(filter: String) {
                val now = clock()
                for (p in partiesOn(id)) {
                    if (filterOf(p) == filter && p.connections.any { it.id == id && !it.up }) route(p, p.connectionUp(id, now))
                }
                listener.onActivity()
            }

            override fun onDown() = partiesOn(id).forEach { it.connectionDown(id) }

            override fun onMessage(topic: String, payload: ByteArray, retained: Boolean) =
                deliver(id, payload, snapshot = retained, kept = !LinkTopics.isRelayPost(topic))
        })
        c.mqtt = mqtt
        mqtt.start()
    }

    /** A radio; it counts as connected once the platform says so ([radioStatus]). */
    fun addRadio(id: ConnectionId, radio: LinkRadio) {
        if (id in conns) return
        conns[id] = Connection(id, ConnectionKind.LORA).also { it.radio = radio }
    }

    /** A radio's state as the platform has it: CONNECTED once its session is set up. */
    fun radioStatus(id: ConnectionId, status: TransportStatus) {
        val c = conns[id]?.takeIf { it.kind == ConnectionKind.LORA } ?: return
        c.status = status
        listener.onStatus(id, status)
        val up = status.state == TransportStatus.State.CONNECTED
        if (up == c.radioUp) return
        c.radioUp = up
        val now = clock()
        for (p in partiesOn(id)) if (up) route(p, p.connectionUp(id, now)) else p.connectionDown(id)
        if (up) pump(c, now)
        listener.onActivity()
    }

    /** A MapAround packet (PRIVATE_APP) a radio heard. */
    fun fromRadio(id: ConnectionId, bytes: ByteArray) {
        if (conns[id]?.radioUp == true) deliver(id, bytes, snapshot = false, kept = true)
    }

    /** A radio's mesh is busy, or no longer. */
    fun setMeshBusy(id: ConnectionId, busy: Boolean) {
        conns[id]?.busy = busy
    }

    /** The platform says the network is back: servers being waited for are tried now. */
    fun networkBack() = conns.values.forEach { it.mqtt?.retryNow() }

    /** Closes [id]; the parties stop using it. */
    fun removeConnection(id: ConnectionId) {
        val c = conns[id] ?: return
        for ((p, set) in uses) if (set.remove(id)) p.removeConnection(id)
        conns.remove(id)
        c.mqtt?.stop()
    }

    // Parties.

    /** Takes [party] in, sending on [on]. */
    fun addParty(party: LinkParty, on: Collection<ConnectionId> = emptyList()) {
        if (party in uses) return
        uses[party] = LinkedHashSet()
        on.forEach { use(party, it) }
    }

    /** Whether [party] sends on (and listens to) connection [id]. */
    fun use(party: LinkParty, id: ConnectionId, on: Boolean = true) {
        val set = uses[party] ?: return
        val c = conns[id] ?: return
        val now = clock()
        if (on) {
            if (!set.add(id)) return
            party.addConnection(id, c.kind, now, up = false)
            // Online it's up once subscribed (and the server gives what it keeps); a radio, now.
            c.mqtt?.subscribe(filterOf(party))
            if (c.kind == ConnectionKind.LORA && c.radioUp) route(party, party.connectionUp(id, now))
        } else {
            if (!set.remove(id)) return
            party.removeConnection(id)
            val f = filterOf(party)
            if (partiesOn(id).none { filterOf(it) == f }) c.mqtt?.unsubscribe(f)
        }
    }

    /** Lets [party] go (after its LEAVE, if it's leaving: [send] that first). */
    fun removeParty(party: LinkParty) {
        uses[party]?.toList()?.forEach { use(party, it, on = false) }
        uses.remove(party)
    }

    // Running.

    /** Sends what one of [party]'s actions returned ([LinkParty.sos], [LinkParty.createMark]…), and passes its events on. */
    fun send(party: LinkParty, result: LinkResult) = route(party, result)

    /** About once a second. */
    fun tick(batteryPct: Int? = null, charging: Boolean = false) {
        val now = clock()
        conns.values.toList().forEach { it.mqtt?.tick() }
        val device = LinkDevice(
            now, batteryPct, charging,
            meshBusy = { conns[it]?.busy == true },
            canSend = { conns[it]?.queue?.canSend(now) ?: true },
        )
        for (p in uses.keys.toList()) route(p, p.tick(device))
        conns.values.forEach { pump(it, now) }
    }

    /**
     * When [tick] next has something to do: the earliest of the parties' ([LinkParty.nextDueMs]),
     * the servers' (pings, retries) and the radios' queues (their next airtime).
     */
    fun nextDueMs(): Long {
        val now = clock()
        var next = now + LinkParty.MAX_IDLE_MS
        for (p in uses.keys) next = minOf(next, p.nextDueMs(now))
        for (c in conns.values) {
            c.mqtt?.nextDueMs()?.let { next = minOf(next, it) }
            if (c.radioUp) c.queue?.nextMs(now)?.let { next = minOf(next, it) }
        }
        return maxOf(next, now)
    }

    private fun deliver(id: ConnectionId, bytes: ByteArray, snapshot: Boolean, kept: Boolean) {
        val h = LinkEnvelope.header(bytes) ?: return
        val now = clock()
        // Two parties may share a tag: each tries its key.
        for (p in partiesOn(id)) if (p.key.tag == h.tag) route(p, p.receive(bytes, id, now, snapshot, kept))
        listener.onActivity()
    }

    private fun route(party: LinkParty, r: LinkResult) {
        val tagHex = party.key.tagHex
        var radios: MutableSet<Connection>? = null
        for (o in r.out) {
            val c = conns[o.to] ?: continue
            val mqtt = c.mqtt
            if (mqtt != null) {
                val h = o.header
                val t = LinkTopics.of(tagHex, h, o.snapshot) ?: continue
                mqtt.publish(t.topic, o.bytes, t.retained)
                if (h.type == LinkType.LEAVE) LinkTopics.cleared(tagHex, h.sender).forEach { mqtt.publish(it, ByteArray(0), retained = true) }
            } else if (c.queue != null) {
                c.queue.add(o.bytes, o.bulk)
                (radios ?: LinkedHashSet<Connection>().also { radios = it }) += c
            }
        }
        radios?.let { val now = clock(); it.forEach { c -> pump(c, now) } }
        r.events.forEach { listener.onEvent(party, it) }
    }

    /** Whatever the radio's queue lets go now. */
    private fun pump(c: Connection, nowMs: Long) {
        if (!c.radioUp) return
        val q = c.queue ?: return
        val radio = c.radio ?: return
        while (true) radio.send(q.poll(nowMs) ?: break)
    }

    private fun partiesOn(id: ConnectionId): List<LinkParty> = uses.filterValues { id in it }.keys.toList()

    private fun filterOf(p: LinkParty) = LinkTopics.all(p.key.tagHex)
}
