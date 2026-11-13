package app.maparound.web

import app.maparound.data.KeyValueStore
import app.maparound.party.Member
import app.maparound.party.MqttWire
import app.maparound.party.PartyController
import app.maparound.party.PartyEngine
import app.maparound.party.PartyEvent
import app.maparound.party.PartyKey
import app.maparound.party.PartyRelays
import app.maparound.party.PartyState
import app.maparound.party.TransportStatus
import app.maparound.party.Via
import app.maparound.shared.crypto.randomBytes
import app.maparound.shared.toByteArray
import app.maparound.shared.toUint8Array
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ---- the browser's WebSocket (MQTT over WebSocket: binary messages, subprotocol "mqtt") ---------

private fun wsOpen(url: String, onOpen: () -> Unit, onData: (JsAny) -> Unit, onClose: (String) -> Unit): JsAny? = js(
    """(() => {
    let ws;
    try { ws = new WebSocket(url, 'mqtt'); } catch (e) { return null; }
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => onOpen();
    ws.onmessage = (e) => { if (e.data instanceof ArrayBuffer) onData(new Uint8Array(e.data)); };
    ws.onclose = (e) => onClose(e.reason || 'connection closed');
    return ws;
})()""",
)

private fun wsSend(ws: JsAny, data: JsAny): Unit = js("{ try { if (ws.readyState === 1) ws.send(data); } catch (e) {} }")

private fun wsClose(ws: JsAny): Unit = js("{ ws.onopen = null; ws.onmessage = null; ws.onclose = null; try { ws.close(); } catch (e) {} }")

/**
 * The party in the web app, watch only: it reads the party's packets from the same public MQTT
 * brokers as the app (over a secure WebSocket), decrypts them with the invite's key and shows the
 * members, the meeting point, SOS alerts and messages. It sends nothing to the party, so it shares
 * no position and the members don't see it. The invite (its key) is kept in [store].
 */
class WebPartyWatcher(private val store: KeyValueStore) : PartyController {
    override val watchOnly: Boolean get() = true

    private val _state = MutableStateFlow(PartyState())
    override val state: StateFlow<PartyState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PartyEvent> = _events.asSharedFlow()
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)

    private val scope = MainScope()
    private var engine: PartyEngine? = null
    private var socket: JsAny? = null
    private var reader = MqttWire.Reader()
    private var watchdog: Job? = null

    /** Which connection the callbacks are about (older sockets' come late, and are ignored). */
    private var generation = 0
    private var broker = 0
    private var subscribed = false
    private var lastHeard = 0L
    private val clientId = "maparound-web-" + randomBytes(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    init {
        store.getString(K_LINK)?.let(PartyKey::parse)?.let { key ->
            watch(key)
            if (store.getBoolean(K_ACTIVE, true)) setActive(true)
        }
    }

    private fun watch(key: PartyKey) {
        // Our id only tells our own packets apart, and we send none.
        engine = PartyEngine(key, randomBytes(4).let { b -> (b[0].toInt() shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF) })
        _state.value = PartyState(party = key)
    }

    /** A watcher can't start a party. */
    override fun create(partyName: String, myName: String) = Unit

    override fun join(key: PartyKey, myName: String) {
        stop()
        store.putString(K_LINK, key.toLink())
        watch(key)
        setActive(true)
    }

    override fun leave() {
        stop()
        engine = null
        store.remove(K_LINK)
        store.remove(K_ACTIVE)
        _state.value = PartyState()
    }

    override fun setActive(on: Boolean) {
        if (engine == null) return
        store.putBoolean(K_ACTIVE, on)
        if (on) {
            update { it.copy(active = true) }
            broker = 0
            connect()
        } else {
            stop()
        }
    }

    // Watch only: nothing is sent, and there's no radio in the browser.
    override fun setSharing(on: Boolean) = Unit
    override fun setProfile(name: String, color: Int, icon: Int) = Unit
    override fun setOnlineEnabled(on: Boolean) = Unit
    override fun setGateway(on: Boolean) = Unit
    override fun setRadio(address: String, name: String) = Unit
    override fun forgetRadio() = Unit
    override fun sos(active: Boolean, text: String) = Unit
    override fun setMeeting(lat: Double, lon: Double, label: String) = Unit
    override fun clearMeeting() = Unit
    override fun sendText(text: String) = Unit

    // ---- the connection ---------------------------------------------------------------------------

    private fun connect() {
        closeSocket()
        val gen = ++generation
        val url = PartyRelays.WEBSOCKET[broker % PartyRelays.WEBSOCKET.size]
        val host = PartyRelays.host(url)
        update { it.copy(online = TransportStatus(TransportStatus.State.CONNECTING, host)) }
        reader = MqttWire.Reader()
        subscribed = false
        lastHeard = nowMillis()
        socket = wsOpen(
            url,
            onOpen = { if (gen == generation) send(MqttWire.connect(clientId, KEEP_ALIVE_S)) },
            onData = { data -> if (gen == generation) onData(data.toByteArray(), host) },
            onClose = { why -> if (gen == generation) lost("$host: $why") },
        )
        if (socket == null) {
            lost("$host: can't connect")
            return
        }
        watchdog = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (gen != generation) return@launch
            if (!subscribed) {
                lost("$host: no answer")
                return@launch
            }
            while (gen == generation) {
                delay(PING_MS)
                if (gen != generation) break
                if (nowMillis() - lastHeard > 2 * PING_MS + 15_000) {
                    lost("$host: no answer")
                    break
                }
                send(MqttWire.PINGREQ)
            }
        }
    }

    private fun onData(bytes: ByteArray, host: String) {
        lastHeard = nowMillis()
        val packets = try {
            reader.feed(bytes)
        } catch (e: Exception) {
            lost("$host: ${e.message}")
            return
        }
        for (p in packets) {
            when (p) {
                is MqttWire.Packet.ConnAck ->
                    if (p.code == 0) {
                        send(MqttWire.subscribe(1, PartyRelays.allOf(engine?.key?.tagHex ?: return)))
                    } else {
                        lost("$host refused the connection (code ${p.code})")
                        return
                    }
                is MqttWire.Packet.SubAck ->
                    if (p.codes.any { it >= 0x80 }) {
                        lost("$host refused the subscription")
                        return
                    } else {
                        subscribed = true
                        update { it.copy(online = TransportStatus(TransportStatus.State.CONNECTED, host)) }
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
            update { it.copy(online = TransportStatus(TransportStatus.State.CONNECTING, "Reconnecting… ($why)")) }
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
        socket?.let { send(MqttWire.DISCONNECT) }
        closeSocket()
        update { it.copy(active = false, online = TransportStatus(TransportStatus.State.OFF)) }
    }

    private fun closeSocket() {
        watchdog?.cancel()
        watchdog = null
        socket?.let(::wsClose)
        socket = null
        subscribed = false
    }

    private fun send(bytes: ByteArray) {
        socket?.let { wsSend(it, bytes.toUint8Array()) }
    }

    // ---- the party's packets --------------------------------------------------------------------

    private fun onPacket(bytes: ByteArray, retained: Boolean) {
        val e = engine ?: return
        val accepted = e.receive(bytes, Via.ONLINE) ?: return
        for (ev in accepted.events) {
            // On connecting, the broker sends everyone's kept state: they didn't just join.
            if (retained && ev is PartyEvent.Joined) continue
            _events.tryEmit(ev)
        }
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

    private companion object {
        const val K_LINK = "link"
        const val K_ACTIVE = "active"
        const val KEEP_ALIVE_S = 120
        const val PING_MS = 50_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val RETRY_MS = 2_000L
        const val ROUND_PAUSE_MS = 30_000L
    }
}
