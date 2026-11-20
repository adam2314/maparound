package app.maparound.party.link

import app.maparound.data.KeyValueStore
import app.maparound.i18n.T
import app.maparound.party.HistoryPoint
import app.maparound.party.Meeting
import app.maparound.party.Member
import app.maparound.party.Mesh
import app.maparound.party.MeshNode
import app.maparound.party.MeshRadio
import app.maparound.party.MqttSockets
import app.maparound.party.PartyController
import app.maparound.party.PartyEvent
import app.maparound.party.PartyKey
import app.maparound.party.PartyMe
import app.maparound.party.PartyMessage
import app.maparound.party.PartyPalette
import app.maparound.party.PartyRadio
import app.maparound.party.PartyRadios
import app.maparound.party.PartyRelays
import app.maparound.party.PartyState
import app.maparound.party.TransportStatus
import app.maparound.party.Via
import app.maparound.sensors.GeoFix
import app.maparound.shared.crypto.randomBytes
import app.maparound.shared.util.hex
import app.maparound.shared.util.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * The party as the screens drive it ([PartyController]), on Link v1 (docs/LINK_PROTOCOL.md): one
 * party, on the servers in its invite (else the public ones, [publicServers]) and a radio where
 * the platform has one ([radios]). The platform gives the sockets and radios; everything else is
 * here, for the apps alike.
 *
 * It runs while active, either here ([scope]: following [locations], ticking every second; the web
 * app) or in the platform's own runner ([service]: Android's foreground service, which calls
 * [attach], [onFix], [tick] and [detach]).
 *
 * [store] keeps the invite, my id, name and look, the switches, the radio, and the sequence
 * numbers and marks' counter and clock (they must never go back). Everything runs on the main thread.
 */
class LinkSession(
    private val store: KeyValueStore,
    private val sockets: MqttSockets,
    private val radios: PartyRadios? = null,
    private val publicServers: List<String> = PartyRelays.WEBSOCKET,
    private val battery: () -> Int? = { null },
    private val charging: () -> Boolean = { false },
    private val clock: () -> Long = ::nowMillis,
    private val service: ((on: Boolean) -> Unit)? = null,
    private val locations: () -> Flow<GeoFix> = { emptyFlow() },
    private val scope: CoroutineScope = MainScope(),
) : PartyController {
    private val _state = MutableStateFlow(PartyState())
    override val state: StateFlow<PartyState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PartyEvent> = _events.asSharedFlow()
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)
    override val hasRadio: Boolean get() = radios != null

    /** The party I'm in (null: none). */
    var party: LinkParty? = null
        private set
    private var key: PartyKey? = null

    /** Connected while active. */
    var hub: LinkHub? = null
        private set
    private var radio: PartyRadio? = null
    private var radioConn: ConnectionId? = null
    private val meshNodes = LinkedHashMap<Long, MeshNode>()
    private var running: Job? = null
    private var seqBase = 0
    private var savedCounter = 0L
    private var savedClock = 0L

    private val hubListener = object : LinkHub.Listener {
        override fun onEvent(party: LinkParty, event: LinkEvent) {
            announce(event)
            refresh()
        }

        override fun onStatus(connection: ConnectionId, status: TransportStatus) {
            if (connection == radioConn) update { it.copy(radio = status) }
            else update { it.copy(online = onlineStatus()) }
            replan()
        }

        override fun onActivity() = replan()

        override fun onRefused(connection: ConnectionId, party: LinkParty) {
            refused += connection
            update { it.copy(online = onlineStatus()) }
        }
    }

    /** Private servers that turned the party away (until they take it again). */
    private val refused = HashSet<ConnectionId>()

    /** A party's token: 16 random bytes, base64url without padding. */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun newToken(): String = kotlin.io.encoding.Base64.UrlSafe.encode(randomBytes(16)).trimEnd('=')

    // Ticking: when something is due, not every second.

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var ticking: Job? = null

    /** When the tick loop means to wake next (while it waits). */
    private var plannedMs = Long.MAX_VALUE

    /** For tests: how many times it ticked. */
    internal var ticks = 0
        private set

    private val _fixEveryMs = MutableStateFlow<Long?>(null)

    /**
     * How often the platform should take a fix now (ms), from the fastest mode in use: often in
     * Live and when moving, rarely when standing still; null when no fix is needed (not running,
     * or not sharing and no SOS).
     */
    val fixEveryMs: StateFlow<Long?> = _fixEveryMs.asStateFlow()

    init {
        val k = store.getString(K_LINK)?.let(PartyKey::parse)
        val id = store.getInt(K_ID, 0)
        if (k != null && id != 0) {
            open(k, id, store.getString(K_SEQ)?.toIntOrNull() ?: Random.nextInt(65_536))
            _state.value = settings(PartyState(party = k, me = me(id), sharing = store.getBoolean(K_SHARING, true)))
            // Running here: carry on as it was. (A service is started from the screens: resumeIfWasActive.)
            if (service == null && store.getBoolean(K_ACTIVE, true)) setActive(true)
        } else {
            _state.value = settings(PartyState())
            if (k != null) {
                // Watched only (the web app before it could join): asked whether to join it now.
                store.remove(K_LINK)
                pendingInvite.value = k
            }
        }
    }

    /** With a [service]: starts it again if the party was running when the app closed. */
    fun resumeIfWasActive() {
        if (party != null && store.getBoolean(K_ACTIVE, false) && !_state.value.active) setActive(true)
    }

    private fun open(k: PartyKey, id: Int, seq: Int) {
        key = k
        val p = LinkParty(
            k, id, profile(),
            seq = seq,
            marksCounter = store.getString(K_COUNTER)?.toLongOrNull() ?: 1,
            marksClock = store.getString(K_CLOCK)?.toLongOrNull() ?: 0,
        )
        p.sharing = store.getBoolean(K_SHARING, true)
        p.gateway = store.getBoolean(K_GATEWAY, true)
        // Made once per party, kept with it: it proves my id on the party's private servers.
        p.token = store.getString(K_TOKEN) ?: newToken().also { store.putString(K_TOKEN, it) }
        party = p
        savedCounter = p.marks.nextCounter
        savedClock = p.marks.clock
        reserveSeq(p)
    }

    /** The switches, the radio and my look, as kept. */
    private fun settings(s: PartyState) = s.copy(
        onlineEnabled = store.getBoolean(K_ONLINE, true),
        radioName = if (radios != null) store.getString(K_RADIO_NAME) else null,
        gateway = store.getBoolean(K_GATEWAY, true),
        look = keptLook(),
    )

    private fun keptLook(): PartyMe? =
        if (!store.contains(K_NAME) && !store.contains(K_COLOR)) null
        else PartyMe(0, store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]), store.getInt(K_ICON, 0))

    private fun me(id: Int) = PartyMe(id, store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]), store.getInt(K_ICON, 0))

    private fun profile() = LinkProfile(store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]) and 0xFFFFFF, store.getInt(K_ICON, 0))

    // Joining and leaving.

    override fun create(partyName: String, myName: String) = join(PartyKey.create(partyName, defaultServer), myName)

    override val defaultServer: String? get() = store.getString(K_SERVER)

    override fun join(key: PartyKey, myName: String) {
        leaveSilently()
        // A new id per party; the look (icon, colour) carries over from the last one.
        val id = randomBytes(4).let { b ->
            (b[0].toInt() shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        }.let { if (it == 0) 1 else it }
        store.putString(K_LINK, key.toLink())
        store.putInt(K_ID, id)
        store.putString(K_NAME, myName.trim().ifEmpty { T.party.defaultMyName })
        if (!store.contains(K_COLOR)) store.putInt(K_COLOR, PartyPalette.PRESETS.random())
        open(key, id, Random.nextInt(65_536))
        val me = me(id)
        _state.value = settings(PartyState(party = key, me = me, sharing = store.getBoolean(K_SHARING, true))).copy(look = me)
        setActive(true)
    }

    /** Tells the others (the servers forget what they kept of me), then stops. */
    override fun leave() {
        val p = party ?: return
        val h = hub
        if (h == null) {
            leaveSilently()
            return
        }
        h.send(p, p.leave(clock()))
        h.removeParty(p)
        scope.launch {
            delay(LEAVE_MS)
            if (party === p) leaveSilently()
        }
    }

    private fun leaveSilently() {
        run(false)
        party = null
        key = null
        meshNodes.clear()
        for (k in listOf(K_LINK, K_ID, K_SEQ, K_ACTIVE, K_COUNTER, K_CLOCK, K_TOKEN)) store.remove(k)
        _state.value = settings(PartyState())
    }

    override fun setServer(server: String?) {
        if (server == null) store.remove(K_SERVER) else store.putString(K_SERVER, server)
        val k = key?.withServer(server) ?: return
        key = k
        store.putString(K_LINK, k.toLink())
        update { it.copy(party = k) }
        if (hub != null && _state.value.onlineEnabled) {
            stopOnline()
            startOnline()
        }
    }

    // Running.

    override fun setActive(on: Boolean) {
        if (party == null) return
        store.putBoolean(K_ACTIVE, on)
        run(on)
    }

    private fun run(on: Boolean) {
        val s = service
        if (s != null) {
            if (!on) detach()
            s(on)
            return
        }
        if (on) {
            if (running != null) return
            attach()
            running = scope.launch {
                // Again when they end (no permission yet, or it was taken back).
                while (true) {
                    locations().collect { onFix(it) }
                    delay(LOCATION_RETRY_MS)
                }
            }
        } else {
            running?.cancel()
            running = null
            detach()
        }
    }

    /** Connects: the servers (if online is on) and the radio. */
    fun attach() {
        val p = party ?: return
        if (hub != null) return
        hub = LinkHub(sockets, hubListener, clock).also { it.addParty(p) }
        update { it.copy(active = true) }
        if (_state.value.onlineEnabled) startOnline()
        startRadio()
        ticking = scope.launch { tickLoop() }
    }

    fun detach() {
        val h = hub ?: return
        ticking?.cancel()
        ticking = null
        stopRadio()
        h.connections.map { it.id }.forEach(h::removeConnection)
        party?.let(h::removeParty)
        hub = null
        _fixEveryMs.value = null
        update { it.copy(active = false, online = TransportStatus(TransportStatus.State.OFF), radio = TransportStatus(TransportStatus.State.OFF)) }
    }

    /** My position, from the platform: ticked for at once (it may be worth sending). */
    fun onFix(fix: GeoFix) {
        update { it.copy(myPosition = fix) }
        party?.onFix(fix)
        wakeNow()
    }

    /**
     * Sends what's due. The session ticks itself while attached ([tickLoop]); the platform needn't,
     * but may.
     */
    fun tick() {
        val h = hub ?: return
        ticks++
        h.tick(battery()?.takeIf { it in 0..100 }, charging())
        afterSend()
        refresh()
    }

    /**
     * Ticks, then sleeps until the next thing is due ([LinkHub.nextDueMs]: at most 30 s away),
     * or until a fix, a packet or an action may have made something due sooner.
     */
    private suspend fun tickLoop() {
        while (true) {
            tick()
            wake.tryReceive() // what the tick itself stirred is in the plan below
            val h = hub ?: return
            val now = clock()
            val next = h.nextDueMs()
            plannedMs = next
            withTimeoutOrNull((next - now).coerceIn(MIN_WAIT_MS, LinkParty.MAX_IDLE_MS)) { wake.receive() }
            plannedMs = Long.MAX_VALUE
        }
    }

    /** Wakes the tick loop if something is now due before it meant to wake. */
    private fun replan() {
        val h = hub ?: return
        if (plannedMs != Long.MAX_VALUE && h.nextDueMs() < plannedMs) wake.trySend(Unit)
    }

    private fun wakeNow() {
        if (hub != null) wake.trySend(Unit)
    }

    /** The platform says the network is back: servers being waited for are tried now. */
    fun networkBack() {
        hub?.networkBack()
        wakeNow()
    }

    // Connections.

    /** The party's servers, else the public ones (one connection). */
    private fun servers(): Map<ConnectionId, List<String>> {
        val own = key?.servers.orEmpty()
        return if (own.isEmpty()) mapOf(PUBLIC to publicServers) else own.associate { "$SERVER_PREFIX$it" to listOf(it) }
    }

    private fun startOnline() {
        val h = hub ?: return
        val p = party ?: return
        for ((id, urls) in servers()) {
            h.addServer(id, urls, private = id != PUBLIC)
            h.use(p, id)
        }
    }

    private fun stopOnline() {
        val h = hub ?: return
        h.connections.filter { it.kind == ConnectionKind.ONLINE }.map { it.id }.forEach(h::removeConnection)
        update { it.copy(online = TransportStatus(TransportStatus.State.OFF)) }
    }

    /** The servers as one line on the screens: connected if any is, with their hosts. */
    private fun onlineStatus(): TransportStatus {
        val p = party
        // A server that took the party back: no longer refused.
        refused.removeAll { id -> p?.connections?.any { it.id == id && it.up } == true }
        val all = hub?.connections?.filter { it.kind == ConnectionKind.ONLINE }?.map { c ->
            if (c.id in refused && c.status.state == TransportStatus.State.CONNECTED) {
                TransportStatus(TransportStatus.State.ERROR, T.party.refusedByServer(c.status.detail))
            } else {
                c.status
            }
        }.orEmpty()
        val up = all.filter { it.state == TransportStatus.State.CONNECTED }
        return if (up.isNotEmpty()) TransportStatus(TransportStatus.State.CONNECTED, up.joinToString(", ") { it.detail })
        else all.firstOrNull { it.state == TransportStatus.State.CONNECTING }
            ?: all.firstOrNull { it.state == TransportStatus.State.ERROR }
            ?: TransportStatus(TransportStatus.State.OFF)
    }

    private fun startRadio() {
        val links = radios ?: return
        val h = hub ?: return
        val p = party ?: return
        if (radio != null) return
        val address = store.getString(K_RADIO_ID) ?: return
        val id = "$RADIO_PREFIX$address"
        val r = links.open(address, store.getString(K_RADIO_NAME) ?: address, object : MeshRadio.Listener {
            override fun onStatus(status: TransportStatus) {
                if (radioConn == id) hub?.radioStatus(id, status)
            }

            override fun onMyNode(num: Long) {
                party?.meshNode = num
            }

            override fun onPartyPacket(payload: ByteArray) {
                hub?.fromRadio(id, payload)
            }

            override fun onNode(num: Long, longName: String?, shortName: String?, position: Mesh.Position?) {
                meshNode(num, longName, shortName, position)
                refresh()
            }
        })
        if (r == null) {
            update { it.copy(radio = TransportStatus(TransportStatus.State.ERROR, T.party.bluetoothPermissionNeeded)) }
            return
        }
        h.addRadio(id, LinkRadio { r.send(it) })
        h.use(p, id)
        radio = r
        radioConn = id
        r.start()
    }

    private fun stopRadio() {
        radio?.stop()
        radio = null
        radioConn?.let { hub?.removeConnection(it) }
        radioConn = null
        update { it.copy(radio = TransportStatus(TransportStatus.State.OFF)) }
    }

    /** Plain Meshtastic radios (trackers, others' radios): not mine, not a member's. */
    private fun meshNode(num: Long, name: String?, shortName: String?, position: Mesh.Position?) {
        val p = party ?: return
        if (num == 0L || num == p.meshNode || p.members.values.any { it.meshNode == num }) return
        val old = meshNodes[num]
        meshNodes[num] = MeshNode(
            num,
            name?.takeIf { it.isNotBlank() } ?: old?.name ?: "!" + hex(num),
            shortName?.takeIf { it.isNotBlank() } ?: old?.shortName ?: "",
            position ?: old?.position,
            clock(),
        )
    }

    // Settings.

    override fun setSharing(on: Boolean) {
        store.putBoolean(K_SHARING, on)
        party?.sharing = on
        update { it.copy(sharing = on) }
        refresh()
        wakeNow()
    }

    override fun setProfile(name: String, color: Int, icon: Int) {
        val me = _state.value.me
        val was = me ?: _state.value.look ?: PartyMe(0, "", color, icon)
        val updated = was.copy(name = name.trim().ifEmpty { was.name }, color = color, icon = icon)
        store.putString(K_NAME, updated.name)
        store.putInt(K_COLOR, updated.color)
        store.putInt(K_ICON, updated.icon)
        if (me == null) {
            update { it.copy(look = updated) }
            return
        }
        update { it.copy(me = updated, look = updated) }
        party?.profile = profile()
        act { p, now -> p.helloNow(now) }
    }

    override fun setOnlineEnabled(on: Boolean) {
        store.putBoolean(K_ONLINE, on)
        update { it.copy(onlineEnabled = on) }
        if (hub == null) return
        if (on) startOnline() else stopOnline()
    }

    override fun setGateway(on: Boolean) {
        store.putBoolean(K_GATEWAY, on)
        party?.gateway = on
        update { it.copy(gateway = on) }
    }

    override fun setRadio(address: String, name: String) {
        if (radios == null) return
        store.putString(K_RADIO_ID, address)
        store.putString(K_RADIO_NAME, name)
        update { it.copy(radioName = name) }
        stopRadio()
        startRadio()
    }

    override fun forgetRadio() {
        store.remove(K_RADIO_ID)
        store.remove(K_RADIO_NAME)
        stopRadio()
        update { it.copy(radioName = null) }
    }

    // Actions.

    override fun sos(active: Boolean, text: String) = act { p, now -> p.sos(active, text, now) }

    override fun setMeeting(lat: Double, lon: Double, label: String) = act { p, now -> p.setMeeting(GridPoint.of(lat, lon), label, now) }

    override fun clearMeeting() = act { p, now -> p.clearMeeting(now) }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        act { p, now -> p.text(text.trim(), now) }
    }

    /** My action: sent where connected (else it goes when they come up); it announces nothing to me. */
    private fun act(f: (LinkParty, Long) -> LinkResult) {
        val p = party ?: return
        val r = f(p, clock())
        hub?.send(p, LinkResult(out = r.out))
        wakeNow()
        afterSend()
        refresh()
    }

    override fun history(member: Int): List<HistoryPoint> = party?.history?.of(member).orEmpty().map {
        HistoryPoint(it.time * 1000, PartyMessage.Position(it.at.latDeg, it.at.lonDeg, null, null, null, null, null))
    }

    // What the screens see.

    private fun announce(event: LinkEvent) {
        val p = party ?: return
        val e = when (event) {
            is LinkEvent.Joined -> PartyEvent.Joined(member(event.member))
            is LinkEvent.Left -> PartyEvent.Left(member(event.member))
            is LinkEvent.SosRaised -> PartyEvent.SosRaised(member(event.member), event.text)
            is LinkEvent.SosCancelled -> PartyEvent.SosCancelled(member(event.member))
            is LinkEvent.TextReceived -> PartyEvent.TextReceived(member(event.member), event.text)
            is LinkEvent.MarkChanged -> if (event.mark.id == MarkId.MEETING) PartyEvent.MeetingSet(meeting(p)) else null
            else -> null
        }
        e?.let { _events.tryEmit(it) }
    }

    private fun refresh() {
        val p = party ?: return
        update { s ->
            s.copy(
                members = p.members.values.filter { !it.left }.map(::member)
                    .sortedWith(compareByDescending<Member> { it.sos }.thenBy { it.displayName.lowercase() }),
                meshNodes = meshNodes.values.filter { it.position != null }.sortedBy { it.name },
                meeting = meeting(p),
                chat = p.chat.toList(),
                mySos = p.mySos,
                historyCounts = p.history.counts,
                online = if (hub != null && s.onlineEnabled) onlineStatus() else s.online,
            )
        }
        _fixEveryMs.value = fixEvery(p)
    }

    /** How often a fix is worth taking now ([fixEveryMs]). */
    private fun fixEvery(p: LinkParty): Long? {
        if (hub == null || (!p.sharing && !p.mySos)) return null
        // Standing still: just often enough to notice moving again, and to have a fresh fix for a heartbeat.
        if (!p.isMoving && !p.mySos) return FIX_STILL_MS
        val gaps = p.connections.map { c -> ModeTable.of(if (p.mySos) LinkMode.LIVE else c.mode?.mode ?: LinkMode.NORMAL, c.kind).minGapS * 1000 }
        return (gaps.minOrNull() ?: FIX_MOVING_MS).coerceIn(FIX_FAST_MS, FIX_MOVING_MS)
    }

    private fun member(m: LinkMember) = Member(
        id = m.id,
        name = m.name,
        color = if (m.name.isEmpty() && m.rgb == NO_HELLO_RGB) PartyPalette.LEGACY[0] else (0xFF shl 24) or m.rgb,
        icon = m.icon,
        meshNode = m.meshNode ?: 0,
        position = m.position?.let(::position),
        positionAt = m.positionAt * 1000,
        report = m.report?.let(::position),
        reportAt = m.reportAt * 1000,
        heard = m.heard.entries.groupBy { via(it.key) }.mapValues { e -> e.value.maxOf { it.value } },
        sos = m.sos,
        sosText = m.sosText,
        left = m.left,
    )

    private fun position(p: LinkMessage.Pos) = PartyMessage.Position(
        lat = p.at.latDeg,
        lon = p.at.lonDeg,
        altM = p.altM,
        accuracyM = p.accuracyM,
        speedKmh = p.speedKmh,
        headingDeg = p.headingDeg?.toInt(),
        batteryPct = p.batteryPct,
        sos = p.sos,
        driving = p.driving,
    )

    private fun meeting(p: LinkParty): Meeting? {
        val e = p.marks[MarkId.MEETING]?.takeIf { it.isShown } ?: return null
        val at = (e.geom?.value as? Geom.Point)?.at ?: return null
        return Meeting(at.latDeg, at.lonDeg, e.text?.value?.name.orEmpty(), e.lastEditor ?: 0)
    }

    private fun via(id: ConnectionId) = if (id.startsWith(RADIO_PREFIX)) Via.LORA else Via.ONLINE

    // Kept numbers.

    /** After sending: sequence numbers reserved ahead, the marks' counter and clock kept. */
    private fun afterSend() {
        val p = party ?: return
        if (((p.seq - seqBase) and 0xFFFF) >= SEQ_BLOCK - SEQ_MARGIN) reserveSeq(p)
        if (p.marks.nextCounter != savedCounter) {
            savedCounter = p.marks.nextCounter
            store.putString(K_COUNTER, savedCounter.toString())
        }
        if (p.marks.clock != savedClock) {
            savedClock = p.marks.clock
            store.putString(K_CLOCK, savedClock.toString())
        }
    }

    /** Sequence numbers must never repeat: reserved in blocks, so a restart starts past them. */
    private fun reserveSeq(p: LinkParty) {
        seqBase = p.seq
        store.putString(K_SEQ, ((p.seq + SEQ_BLOCK) and 0xFFFF).toString())
    }

    private inline fun update(f: (PartyState) -> PartyState) {
        _state.value = f(_state.value)
    }

    companion object {
        /** The public servers' connection. */
        const val PUBLIC = "public"

        /** A member's colour before their hello. */
        private const val NO_HELLO_RGB = 0x808080
        const val SERVER_PREFIX = "mqtt:"
        const val RADIO_PREFIX = "radio:"

        /** The tick loop waits at least this long for a timer (a fix, a packet or an action wakes it sooner). */
        private const val MIN_WAIT_MS = 1_000L

        /** Fixes: at most this often (Live, an SOS), at least this often while moving, and while standing still. */
        const val FIX_FAST_MS = 2_000L
        const val FIX_MOVING_MS = 10_000L
        const val FIX_STILL_MS = 20_000L
        private const val LOCATION_RETRY_MS = 10_000L
        private const val LEAVE_MS = 1_500L
        private const val SEQ_BLOCK = 1_000
        private const val SEQ_MARGIN = 500

        private const val K_LINK = "link"
        private const val K_SERVER = "server"
        private const val K_ACTIVE = "active"
        private const val K_ID = "my_id"
        private const val K_NAME = "my_name"
        private const val K_COLOR = "my_rgb"
        private const val K_ICON = "my_icon"
        private const val K_SHARING = "sharing"
        private const val K_ONLINE = "online"
        private const val K_GATEWAY = "gateway"
        private const val K_RADIO_ID = "radio_id"
        private const val K_RADIO_NAME = "radio_name"
        private const val K_SEQ = "link_seq"
        private const val K_COUNTER = "marks_counter"
        private const val K_CLOCK = "marks_clock"
        private const val K_TOKEN = "link_token"
    }
}
