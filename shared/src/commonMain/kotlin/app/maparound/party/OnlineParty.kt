package app.maparound.party

import app.maparound.data.KeyValueStore
import app.maparound.i18n.T
import app.maparound.sensors.GeoFix
import app.maparound.shared.crypto.randomBytes
import app.maparound.shared.util.hex
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A connection to a broker that carries MQTT: the platform's WebSocket (subprotocol "mqtt", binary). */
interface MqttSocket {
    fun send(bytes: ByteArray)
    fun close()
}

fun interface MqttSockets {
    /** Opens [url], or null if it can't even start. The callbacks come on the main thread. */
    fun open(url: String, onOpen: () -> Unit, onData: (ByteArray) -> Unit, onClose: (String) -> Unit): MqttSocket?
}

/**
 * The party online only, as a full member: for the apps without a session of their own (the web
 * app; Android has PartySession, with the LoRa radio too). MQTT 3.1.1 over a WebSocket to the public
 * brokers ([PartyRelays.WEBSOCKET]), the packets end-to-end encrypted ([Envelope]), as the apps send
 * them: our position while sharing (or in an SOS), hellos with our name and look, SOS alerts, the
 * meeting point and messages. Positions, hellos, SOS and the meeting point are kept (retained) on the
 * broker, so whoever (re)joins gets everyone's latest at once.
 *
 * [store] keeps the invite, our id, name and look, sharing, and the packets' sequence numbers.
 * [locations] are our positions (the platform asks for permission); they're followed while the party
 * is on. Everything runs on the main thread.
 */
class OnlineParty(
    private val store: KeyValueStore,
    private val sockets: MqttSockets,
    private val locations: () -> Flow<GeoFix>,
    private val battery: () -> Int? = { null },
    private val scope: CoroutineScope = MainScope(),
    private val clock: () -> Long = ::nowMillis,
) : PartyController {
    private val _state = MutableStateFlow(PartyState())
    override val state: StateFlow<PartyState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PartyEvent> = _events.asSharedFlow()
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)

    /** Online only: no LoRa radio. */
    override val hasRadio: Boolean get() = false

    private var engine: PartyEngine? = null
    private var seq = 0L
    private var seqReserved = 0L
    private var lastSent: SendPolicy.Last? = null
    private var lastHello: Long? = null
    private var lastFix: GeoFix? = null
    private var following: Job? = null
    private var ticking: Job? = null

    // The connection.
    private var socket: MqttSocket? = null
    private var reader = MqttWire.Reader()
    private var watchdog: Job? = null

    /** Which connection the callbacks are about (older sockets' come late, and are ignored). */
    private var generation = 0
    private var broker = 0
    private var subscribed = false
    private var lastHeard = 0L
    private val clientId = "maparound-" + randomBytes(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    init {
        val key = store.getString(K_LINK)?.let(PartyKey::parse)
        val id = store.getInt(K_ID, 0)
        if (key != null && id != 0) {
            val me = PartyMe(id, store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]), store.getInt(K_ICON, 0))
            engine = PartyEngine(key, me.id, clock)
            seq = store.getString(K_SEQ)?.toLongOrNull() ?: 0
            reserveSeq()
            _state.value = PartyState(party = key, me = me, sharing = store.getBoolean(K_SHARING, true))
            if (store.getBoolean(K_ACTIVE, true)) setActive(true)
        } else if (key != null) {
            // Watched only (the web app before it could join): asked whether to join it now.
            store.remove(K_LINK)
            pendingInvite.value = key
        }
    }

    override fun create(partyName: String, myName: String) = join(PartyKey.create(partyName), myName)

    override fun join(key: PartyKey, myName: String) {
        leaveSilently()
        // A new id per party; the look (icon, colour) carries over from the last one.
        val id = randomBytes(4).let { b ->
            (b[0].toInt() shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        }.let { if (it == 0) 1 else it }
        val me = PartyMe(
            id, myName.trim().ifEmpty { T.party.defaultMyName },
            if (store.contains(K_COLOR)) store.getInt(K_COLOR, PartyPalette.PRESETS[0]) else PartyPalette.PRESETS.random(),
            store.getInt(K_ICON, 0),
        )
        store.putString(K_LINK, key.toLink())
        store.putInt(K_ID, me.id)
        store.putString(K_NAME, me.name)
        store.putInt(K_COLOR, me.color)
        seq = 1
        reserveSeq()
        engine = PartyEngine(key, me.id, clock)
        lastSent = null
        lastHello = null
        _state.value = _state.value.copy(party = key, me = me, members = emptyList(), meeting = null, chat = emptyList(), mySos = false)
        setActive(true)
    }

    /** Tells the others, clears our kept state on the broker, stops. */
    override fun leave() {
        val e = engine ?: return
        sendNow(PartyMessage.Leave)
        // Empty retained messages delete our last position, name, alert and meeting point from the broker.
        val me = senderHex(e.myId)
        if (subscribed) for (kind in listOf("pos", "hello", "sos", "meet")) send(MqttWire.publish(topic(e, me, kind), ByteArray(0), retained = true))
        scope.launch {
            delay(1_500)
            leaveSilently()
        }
    }

    private fun leaveSilently() {
        stop()
        engine = null
        for (k in listOf(K_LINK, K_ID, K_SEQ, K_ACTIVE)) store.remove(k)
        _state.value = PartyState()
    }

    override fun setActive(on: Boolean) {
        if (engine == null) return
        store.putBoolean(K_ACTIVE, on)
        if (on) {
            if (_state.value.active) return
            update { it.copy(active = true) }
            broker = 0
            connect()
            following = scope.launch {
                // Again when they end (no permission yet, or it was taken back).
                while (true) {
                    locations().collect { onLocation(it) }
                    delay(LOCATION_RETRY_MS)
                }
            }
            ticking = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    tick()
                }
            }
        } else {
            stop()
        }
    }

    override fun setSharing(on: Boolean) {
        store.putBoolean(K_SHARING, on)
        update { it.copy(sharing = on) }
        if (on) {
            lastSent = null // send soon
            lastFix?.let(::onLocation)
        }
    }

    override fun setProfile(name: String, color: Int, icon: Int) {
        val me = _state.value.me ?: return
        val updated = me.copy(name = name.trim().ifEmpty { me.name }, color = color, icon = icon)
        store.putString(K_NAME, updated.name)
        store.putInt(K_COLOR, updated.color)
        store.putInt(K_ICON, updated.icon)
        update { it.copy(me = updated) }
        sendHello(force = true)
    }

    // Online only: always on, no radio.
    override fun setOnlineEnabled(on: Boolean) = Unit
    override fun setGateway(on: Boolean) = Unit
    override fun setRadio(address: String, name: String) = Unit
    override fun forgetRadio() = Unit

    override fun sos(active: Boolean, text: String) {
        update { it.copy(mySos = active) }
        sendNow(PartyMessage.Sos(active, text))
        lastSent = null // the position with the SOS flag goes out right away
        lastFix?.let(::onLocation)
    }

    override fun setMeeting(lat: Double, lon: Double, label: String) = sendNow(PartyMessage.Meet(lat, lon, label))

    override fun clearMeeting() = sendNow(PartyMessage.Meet(0.0, 0.0, ""))

    override fun sendText(text: String) {
        if (text.isBlank()) return
        sendNow(PartyMessage.Text(text.trim()))
    }

    // ---- our position and hellos -----------------------------------------------------------------

    /** Our position: shown, and sent when due (sharing, or in an SOS). */
    private fun onLocation(fix: GeoFix) {
        lastFix = fix
        update { it.copy(myPosition = fix) }
        val s = _state.value
        if (engine == null || !subscribed || (!s.sharing && !s.mySos)) return
        val now = clock()
        val speedKmh = (fix.speedMps ?: 0f) * 3.6
        if (!SendPolicy.due(Via.ONLINE, lastSent, now, fix.lat, fix.lon, speedKmh, s.mySos)) return
        sendNow(
            PartyMessage.Position(
                lat = fix.lat,
                lon = fix.lon,
                altM = fix.altitudeM?.toInt(),
                accuracyM = fix.accuracyM?.toInt(),
                speedKmh = fix.speedMps?.let { speedKmh.toInt() },
                headingDeg = fix.bearingDeg?.takeIf { speedKmh > 2 }?.toInt(),
                batteryPct = battery()?.takeIf { it in 0..100 },
                sos = s.mySos,
                driving = speedKmh >= SendPolicy.DRIVING_KMH,
            ),
        )
        lastSent = SendPolicy.Last(now, fix.lat, fix.lon)
    }

    /** Every few seconds while on: hellos when due, and the position while standing still. */
    private fun tick() {
        sendHello(force = false)
        lastFix?.let(::onLocation)
    }

    /** Hello carries name and look: on connecting (kept online), every 10 minutes, and to newcomers at most every 2. */
    private fun sendHello(force: Boolean, reply: Boolean = false) {
        val e = engine ?: return
        val me = _state.value.me ?: return
        if (!subscribed) return
        val now = clock()
        val last = lastHello
        val due = force || last == null || (if (reply) now - last > 120_000 else now - last > 10 * 60_000L)
        if (!due) return
        lastHello = now
        sendNow(PartyMessage.Hello(me.name, me.color, e.myMeshNode, me.icon))
    }

    private fun sendNow(message: PartyMessage) {
        val e = engine ?: return
        val me = _state.value.me ?: return
        val now = clock()
        val bytes = Envelope.encode(e.key, me.id, nextSeq(), now, message)
        e.applyOwn(message, now)
        if (subscribed) {
            val (kind, retained) = kindOf(message)
            send(MqttWire.publish(topic(e, senderHex(me.id), kind), bytes, retained))
        }
        refresh()
    }

    private fun nextSeq(): Long {
        seq++
        if (seq >= seqReserved) reserveSeq()
        return seq
    }

    /** Sequence numbers must never repeat; reserved in blocks, so a closed page can't reuse one. */
    private fun reserveSeq() {
        seqReserved = seq + 500
        store.putString(K_SEQ, seqReserved.toString())
    }

    // ---- the connection ---------------------------------------------------------------------------

    private fun connect() {
        closeSocket()
        val gen = ++generation
        val url = PartyRelays.WEBSOCKET[broker % PartyRelays.WEBSOCKET.size]
        val host = PartyRelays.host(url)
        update { it.copy(online = TransportStatus(TransportStatus.State.CONNECTING, host)) }
        reader = MqttWire.Reader()
        subscribed = false
        lastHeard = clock()
        socket = sockets.open(
            url,
            onOpen = { if (gen == generation) send(MqttWire.connect(clientId, KEEP_ALIVE_S)) },
            onData = { data -> if (gen == generation) onData(data, host) },
            onClose = { why -> if (gen == generation) lost(T.party.hostProblem(host, why.ifEmpty { T.party.connectionClosed })) },
        )
        if (socket == null) {
            lost(T.party.hostProblem(host, T.party.cantConnect))
            return
        }
        watchdog = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (gen != generation) return@launch
            if (!subscribed) {
                lost(T.party.hostProblem(host, T.party.noAnswer))
                return@launch
            }
            while (gen == generation) {
                delay(PING_MS)
                if (gen != generation) break
                if (clock() - lastHeard > 2 * PING_MS + 15_000) {
                    lost(T.party.hostProblem(host, T.party.noAnswer))
                    break
                }
                send(MqttWire.PINGREQ)
            }
        }
    }

    private fun onData(bytes: ByteArray, host: String) {
        lastHeard = clock()
        val packets = try {
            reader.feed(bytes)
        } catch (e: Exception) {
            lost(T.party.hostProblem(host, "${e.message}"))
            return
        }
        for (p in packets) {
            when (p) {
                is MqttWire.Packet.ConnAck ->
                    if (p.code == 0) {
                        send(MqttWire.subscribe(1, PartyRelays.allOf(engine?.key?.tagHex ?: return)))
                    } else {
                        lost(T.party.refusedConnection(host, p.code))
                        return
                    }
                is MqttWire.Packet.SubAck ->
                    if (p.codes.any { it >= 0x80 }) {
                        lost(T.party.refusedSubscription(host))
                        return
                    } else {
                        subscribed = true
                        update { it.copy(online = TransportStatus(TransportStatus.State.CONNECTED, host)) }
                        // On (re)connecting: our name and position again, kept for whoever comes.
                        lastHello = null
                        lastSent = null
                        sendHello(force = false)
                        lastFix?.let(::onLocation)
                    }
                is MqttWire.Packet.Publish -> {
                    p.packetId?.let { send(MqttWire.pubAck(it)) }
                    // An empty retained message is a member's state removed (they left).
                    if (p.payload.isNotEmpty()) onPacket(p.payload, p.retained)
                }
                else -> Unit
            }
        }
    }

    /** Lost or refused: the same broker again after it worked, else the next one (a pause after them all). */
    private fun lost(why: String) {
        val wasSubscribed = subscribed
        closeSocket()
        if (!_state.value.active) return
        val gen = ++generation
        val pause = if (wasSubscribed) {
            update { it.copy(online = TransportStatus(TransportStatus.State.CONNECTING, T.party.reconnectingBecause(why))) }
            RETRY_MS
        } else {
            broker++
            update { it.copy(online = TransportStatus(TransportStatus.State.ERROR, why)) }
            if (broker % PartyRelays.WEBSOCKET.size == 0) ROUND_PAUSE_MS else RETRY_MS
        }
        watchdog = scope.launch {
            delay(pause)
            if (gen == generation && _state.value.active) connect()
        }
    }

    private fun stop() {
        generation++
        following?.cancel()
        following = null
        ticking?.cancel()
        ticking = null
        socket?.let { send(MqttWire.DISCONNECT) }
        closeSocket()
        update { it.copy(active = false, online = TransportStatus(TransportStatus.State.OFF)) }
    }

    private fun closeSocket() {
        watchdog?.cancel()
        watchdog = null
        socket?.close()
        socket = null
        subscribed = false
    }

    private fun send(bytes: ByteArray) {
        socket?.send(bytes)
    }

    // ---- the party's packets --------------------------------------------------------------------

    private fun onPacket(bytes: ByteArray, retained: Boolean) {
        val e = engine ?: return
        val accepted = e.receive(bytes, Via.ONLINE) ?: return
        for (ev in accepted.events) {
            // On connecting, the broker sends everyone's kept state: they didn't just join.
            if (retained && ev is PartyEvent.Joined) continue
            _events.tryEmit(ev)
            if (ev is PartyEvent.Joined) sendHello(force = false, reply = true) // introduce ourselves
        }
        refresh()
    }

    private fun refresh() {
        val e = engine ?: return
        update { s ->
            s.copy(
                members = e.members.values.filterNot { it.left }
                    .sortedWith(compareByDescending<Member> { it.sos }.thenBy { it.displayName.lowercase() }),
                meeting = e.meeting,
                chat = e.chat.toList(),
            )
        }
    }

    private inline fun update(f: (PartyState) -> PartyState) {
        _state.value = f(_state.value)
    }

    companion object {
        /** A sender in the topics: 8 hex digits, as the Android app writes them. */
        fun senderHex(id: Int): String = hex(id).padStart(8, '0')

        /** Where a message goes ([PartyRelays]) and whether the broker keeps it: as MqttTransport on Android. */
        fun kindOf(message: PartyMessage): Pair<String, Boolean> = when (message) {
            is PartyMessage.Position -> "pos" to true
            is PartyMessage.Hello -> "hello" to true
            is PartyMessage.Sos -> "sos" to true
            is PartyMessage.Meet -> "meet" to true
            is PartyMessage.Text -> "msg" to false
            PartyMessage.Leave -> "msg" to false
        }

        private fun topic(e: PartyEngine, sender: String, kind: String) = "${PartyRelays.ROOT}/${e.key.tagHex}/$sender/$kind"

        private const val K_LINK = "link"
        private const val K_ACTIVE = "active"
        private const val K_ID = "my_id"
        private const val K_NAME = "my_name"
        private const val K_COLOR = "my_rgb"
        private const val K_ICON = "my_icon"
        private const val K_SHARING = "sharing"
        private const val K_SEQ = "seq"
        private const val KEEP_ALIVE_S = 120
        private const val PING_MS = 50_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val RETRY_MS = 2_000L
        private const val ROUND_PAUSE_MS = 30_000L
        private const val TICK_MS = 5_000L
        private const val LOCATION_RETRY_MS = 10_000L
    }
}
