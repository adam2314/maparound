package app.maparound.party.link

import app.maparound.i18n.T
import app.maparound.party.MqttSocket
import app.maparound.party.MqttSockets
import app.maparound.party.MqttWire
import app.maparound.party.PartyRelays
import app.maparound.party.TransportStatus
import app.maparound.shared.crypto.randomBytes

// Plain Kotlin: one MQTT server as a Link v1 connection (docs/LINK_PROTOCOL.md §6, §7). MQTT
// 3.1.1 over the platform's byte stream ([MqttSockets]), one subscription per party on it. Driven
// by [LinkMqtt.tick] and the socket's callbacks, all on one thread, so tests run it on a fake
// broker with a fake clock. Unit-tested.

/**
 * One server connection: [urls] are tried in turn until one answers (the public brokers count as
 * one connection), then that one is kept while it works. Clean session, QoS 0. Reconnects by
 * itself; [start] and [stop] turn it on and off.
 */
class LinkMqtt(
    val id: ConnectionId,
    private val urls: List<String>,
    private val sockets: MqttSockets,
    private val clock: () -> Long,
    private val listener: Listener,
    private val clientId: String = "maparound-" + randomBytes(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') },
) {
    init {
        require(urls.isNotEmpty())
    }

    interface Listener {
        fun onStatus(status: TransportStatus) {}

        /** The server took [filter]: what it keeps for it comes next (retained), then what's new. */
        fun onSubscribed(filter: String)

        /** The connection dropped, and every subscription with it (it reconnects by itself). */
        fun onDown()

        /** [retained]: kept by the server and given on subscribing, not sent just now. */
        fun onMessage(topic: String, payload: ByteArray, retained: Boolean)
    }

    var status = TransportStatus(TransportStatus.State.OFF)
        private set

    /** Connected (the server accepted us); each subscription is ready once [isSubscribed]. */
    val isConnected: Boolean get() = connected

    /** The server in use, or about to be tried. */
    val url: String get() = urls[urlIndex % urls.size]

    private val filters = LinkedHashSet<String>()
    private val granted = HashSet<String>()
    private val pending = HashMap<Int, Pair<String, Long>>()
    private var nextPacketId = 1

    private var running = false
    private var socket: MqttSocket? = null
    private var reader = MqttWire.Reader()

    /** Which socket the callbacks are about (an old one's come late, and are ignored). */
    private var generation = 0
    private var urlIndex = 0
    private var connected = false
    private var openedAt = 0L
    private var lastHeard = 0L
    /** When something last went to the server: a ping is needed only after [PING_MS] of nothing. */
    private var lastOut = 0L
    private var retryAt: Long? = null

    /** Rounds through [urls] that failed since one last worked. */
    private var rounds = 0

    fun start() {
        if (running) return
        running = true
        urlIndex = 0
        connect()
    }

    fun stop() {
        if (!running) return
        running = false
        retryAt = null
        if (connected) send(MqttWire.DISCONNECT)
        val was = connected
        close()
        setStatus(TransportStatus.State.OFF)
        if (was) listener.onDown()
    }

    /**
     * Subscribes to [filter] now and on every reconnect. Again for a filter it has: the server
     * gives what it keeps for it once more (for a second party with the same tag).
     */
    fun subscribe(filter: String) {
        filters += filter
        if (connected && pending.values.none { it.first == filter }) sendSubscribe(filter)
    }

    fun unsubscribe(filter: String) {
        if (!filters.remove(filter)) return
        granted -= filter
        pending.entries.removeAll { it.value.first == filter }
        if (connected) send(MqttWire.unsubscribe(packetId(), filter))
    }

    fun isSubscribed(filter: String) = filter in granted

    /** Publishes at QoS 0; false (and nothing sent) while not connected. */
    fun publish(topic: String, payload: ByteArray, retained: Boolean): Boolean {
        if (!connected) return false
        send(MqttWire.publish(topic, payload, retained))
        return true
    }

    /** About once a second: keeping alive, giving up on a silent server, retrying. */
    fun tick() {
        if (!running) return
        val now = clock()
        retryAt?.let {
            if (now >= it) {
                retryAt = null
                connect()
            }
            return
        }
        if (socket == null) return
        val stuck = !connected && now - openedAt >= CONNECT_TIMEOUT_MS ||
            pending.values.any { now - it.second >= CONNECT_TIMEOUT_MS } ||
            now - lastHeard >= DEAD_MS
        if (stuck) {
            lost(T.party.hostProblem(host, T.party.noAnswer))
            return
        }
        if (connected && now - lastOut >= PING_MS) send(MqttWire.PINGREQ)
    }

    /** When [tick] next has something to do (null: nothing until a callback). */
    fun nextDueMs(): Long? {
        if (!running) return null
        retryAt?.let { return it }
        if (socket == null) return null
        var t = lastHeard + DEAD_MS
        if (!connected) t = minOf(t, openedAt + CONNECT_TIMEOUT_MS)
        pending.values.forEach { t = minOf(t, it.second + CONNECT_TIMEOUT_MS) }
        if (connected) t = minOf(t, lastOut + PING_MS)
        return t
    }

    private val host: String get() = PartyRelays.host(url)

    private fun connect() {
        close()
        val gen = generation
        val host = host
        setStatus(TransportStatus.State.CONNECTING, host)
        openedAt = clock()
        lastHeard = openedAt
        val s = sockets.open(
            url,
            onOpen = { if (gen == generation) send(MqttWire.connect(clientId, KEEP_ALIVE_S)) },
            onData = { data -> if (gen == generation) onData(data, gen) },
            onClose = { why -> if (gen == generation) lost(T.party.hostProblem(host, why.ifEmpty { T.party.connectionClosed })) },
        )
        if (gen != generation) {
            s?.close()
            return
        }
        socket = s
        if (s == null) lost(T.party.hostProblem(host, T.party.cantConnect))
    }

    private fun onData(bytes: ByteArray, gen: Int) {
        val now = clock()
        lastHeard = now
        val packets = try {
            reader.feed(bytes)
        } catch (e: Exception) {
            lost(T.party.hostProblem(host, e.message ?: "?"))
            return
        }
        for (p in packets) {
            if (gen != generation) return // closed meanwhile (by a listener, or a refusal)
            when (p) {
                is MqttWire.Packet.ConnAck ->
                    if (p.code == 0) {
                        connected = true
                        rounds = 0
                        setStatus(TransportStatus.State.CONNECTED, host)
                        filters.forEach(::sendSubscribe)
                    } else {
                        lost(T.party.refusedConnection(host, p.code))
                    }
                is MqttWire.Packet.SubAck -> {
                    val filter = pending.remove(p.packetId)?.first ?: continue
                    if (p.codes.any { it >= 0x80 }) {
                        lost(T.party.refusedSubscription(host))
                    } else if (filter in filters) {
                        granted += filter
                        listener.onSubscribed(filter)
                    }
                }
                is MqttWire.Packet.Publish -> {
                    p.packetId?.let { send(MqttWire.pubAck(it)) }
                    // An empty retained message is something cleared (a member left).
                    if (p.payload.isNotEmpty()) listener.onMessage(p.topic, p.payload, p.retained)
                }
                else -> Unit
            }
        }
    }

    /** The network is back (the platform says so): a server being waited for is tried now. */
    fun retryNow() {
        if (running && retryAt != null) retryAt = clock()
    }

    /**
     * Lost or refused: the same server again after it worked, else the next one; after them all a
     * pause, longer each round (5 s, 10 s… up to [ROUND_PAUSE_MS]).
     */
    private fun lost(why: String) {
        val was = connected
        close()
        if (was) listener.onDown()
        if (!running) return
        val pause = if (was) {
            rounds = 0
            setStatus(TransportStatus.State.CONNECTING, T.party.reconnectingBecause(why))
            RETRY_MS
        } else {
            urlIndex++
            setStatus(TransportStatus.State.ERROR, why)
            if (urlIndex % urls.size == 0) (ROUND_STEP_MS * ++rounds).coerceAtMost(ROUND_PAUSE_MS) else RETRY_MS
        }
        retryAt = clock() + pause
    }

    private fun close() {
        generation++
        socket?.close()
        socket = null
        connected = false
        granted.clear()
        pending.clear()
        reader = MqttWire.Reader()
    }

    private fun sendSubscribe(filter: String) {
        val id = packetId()
        pending[id] = filter to clock()
        send(MqttWire.subscribe(id, filter))
    }

    private fun packetId(): Int = nextPacketId.also { nextPacketId = if (it >= 0xFFFF) 1 else it + 1 }

    private fun send(bytes: ByteArray) {
        val s = socket ?: return
        s.send(bytes)
        lastOut = clock()
    }

    private fun setStatus(state: TransportStatus.State, detail: String = "") {
        val s = TransportStatus(state, detail)
        if (s == status) return
        status = s
        listener.onStatus(s)
    }

    companion object {
        const val KEEP_ALIVE_S = 120
        const val PING_MS = 50_000L
        const val CONNECT_TIMEOUT_MS = 15_000L

        /** Nothing heard for this long (pings answered included): the connection is dead. */
        const val DEAD_MS = 2 * PING_MS + 15_000L
        const val RETRY_MS = 2_000L
        const val ROUND_STEP_MS = 5_000L
        const val ROUND_PAUSE_MS = 30_000L
    }
}
