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
import app.maparound.party.PartyConnection
import app.maparound.party.PartyController
import app.maparound.party.PartyEvent
import app.maparound.party.PartyKey
import app.maparound.party.PartyLayers
import app.maparound.party.MarkShape
import app.maparound.party.PartyMark
import app.maparound.party.PartyMe
import app.maparound.party.PartyMessage
import app.maparound.party.PartyPalette
import app.maparound.party.PartyRadio
import app.maparound.party.PartyRadios
import app.maparound.party.PartyRelays
import app.maparound.party.PartyState
import app.maparound.party.TimedPosition
import app.maparound.party.TransportStatus
import app.maparound.party.WayPoint
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
 * The parties as the screens drive them ([PartyController]), on Link v1 (docs/LINK_PROTOCOL.md):
 * up to [PartyController.MAX_PARTIES] at once, each on the servers in its invite (else the public
 * ones, [publicServers]), all on one radio where the platform has one ([radios]), over one
 * [LinkHub] that shares the connections. The screens show one ([state], picked with [select]),
 * the map all ([parties]). The platform gives the sockets and radios; everything else is here, for
 * the apps alike.
 *
 * It runs while any party is on, either here ([scope]: following [locations]; the web app) or in
 * the platform's own runner ([service]: Android's foreground service, which calls [attach],
 * [onFix], [tick] and [detach]).
 *
 * [store] keeps, for each party (`p<n>.…`), the invite, my id, its switches, its sequence numbers,
 * its marks' counter and clock (they must never go back) and its token; for all of them, my name
 * and look, the online switch, the gateway, the radio, and the server new parties start on.
 * Everything runs on the main thread.
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
    private val _parties = MutableStateFlow<List<PartyState>>(emptyList())
    override val parties: StateFlow<List<PartyState>> = _parties.asStateFlow()
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<PartyEvent> = _events.asSharedFlow()
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)
    override val hasRadio: Boolean get() = radios != null

    /** One party I'm in: where it's kept ([n]), its engine, and what the screens see of it. */
    private class Slot(val n: Int, var key: PartyKey, val party: LinkParty) {
        /** Switched on: sending and receiving (in the hub while it runs). */
        var on = false
        var seqBase = 0
        var savedCounter = party.marks.nextCounter
        var savedClock = party.marks.clock

        /** Its private servers that turned it away (until they take it again). */
        val refused = HashSet<ConnectionId>()

        /** What its marks were when last read for the screens (they're read again only when it changes). */
        var marksSeen = -1L

        /** Its own part of what the screens see (the shared part is [common]'s). */
        var state = PartyState()

        fun k(name: String) = "p$n.$name"
    }

    private val slots = ArrayList<Slot>()
    private var selected: Slot? = null

    /** What all the parties share: my look, the switches, the radio, its nodes, my position. */
    private var common = PartyState()

    /** The party the screens show (null: none). */
    val party: LinkParty? get() = selected?.party

    /** Connected while any party is on. */
    var hub: LinkHub? = null
        private set
    private var radio: PartyRadio? = null
    private var radioConn: ConnectionId? = null

    /** My radio's own node number, once it said. */
    private var myNode: Long? = null
    private val meshNodes = LinkedHashMap<Long, MeshNode>()
    private var locating: Job? = null

    private val hubListener = object : LinkHub.Listener {
        override fun onEvent(party: LinkParty, event: LinkEvent) {
            slotOf(party)?.let { announce(it, event) }
            refresh()
        }

        override fun onStatus(connection: ConnectionId, status: TransportStatus) {
            if (connection == radioConn) common = common.copy(radio = status)
            refresh()
            replan()
        }

        override fun onActivity() = replan()

        override fun onRefused(connection: ConnectionId, party: LinkParty) {
            slotOf(party)?.refused?.add(connection)
            refresh()
        }
    }

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
     * How often the platform should take a fix now (ms), from the fastest mode any party uses:
     * often in Live and when moving, rarely when standing still; null when no fix is needed (not
     * running, or no party sharing and no SOS).
     */
    val fixEveryMs: StateFlow<Long?> = _fixEveryMs.asStateFlow()

    init {
        upgrade()
        common = settings(PartyState())
        for (n in slotNumbers()) {
            val s = load(n)
            if (s != null) slots += s else forgetKeys(n)
        }
        saveSlots()
        selected = slots.firstOrNull { it.n == store.getInt(K_SELECTED, -1) } ?: slots.firstOrNull()
        refresh()
        // Running here: carry on as it was. (A service is started from the screens: resumeIfWasActive.)
        if (service == null) slots.filter { store.getBoolean(it.k(K_ACTIVE), true) }.forEach { turn(it, true) }
    }

    /** The versions with one party kept it without a prefix: it becomes the first. */
    private fun upgrade() {
        if (store.contains(K_SLOTS)) return
        val link = store.getString(K_LINK)
        val id = store.getInt(K_ID, 0)
        if (link != null && id != 0) {
            store.putString("p0.$K_LINK", link)
            store.putInt("p0.$K_ID", id)
            for (name in listOf(K_SEQ, K_COUNTER, K_CLOCK, K_TOKEN)) store.getString(name)?.let { store.putString("p0.$name", it) }
            for (name in listOf(K_SHARING, K_ACTIVE)) if (store.contains(name)) store.putBoolean("p0.$name", store.getBoolean(name, true))
            store.putString(K_SLOTS, "0")
            store.putInt(K_SELECTED, 0)
        } else {
            store.putString(K_SLOTS, "")
            // Watched only (the web app before it could join): asked whether to join it now.
            link?.let(PartyKey::parse)?.let { pendingInvite.value = it }
        }
        for (name in listOf(K_LINK, K_ID, K_SEQ, K_COUNTER, K_CLOCK, K_TOKEN, K_SHARING, K_ACTIVE)) store.remove(name)
    }

    private fun slotNumbers(): List<Int> = store.getString(K_SLOTS).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()

    private fun saveSlots() = store.putString(K_SLOTS, slots.joinToString(",") { it.n.toString() })

    private fun load(n: Int): Slot? {
        val k = store.getString("p$n.$K_LINK")?.let(PartyKey::parse) ?: return null
        val id = store.getInt("p$n.$K_ID", 0).takeIf { it != 0 } ?: return null
        return open(n, k, id, store.getString("p$n.$K_SEQ")?.toIntOrNull() ?: Random.nextInt(65_536))
    }

    private fun open(n: Int, k: PartyKey, id: Int, seq: Int): Slot {
        val p = LinkParty(
            k, id, profile(),
            seq = seq,
            marksCounter = store.getString("p$n.$K_COUNTER")?.toLongOrNull() ?: 1,
            marksClock = store.getString("p$n.$K_CLOCK")?.toLongOrNull() ?: 0,
        )
        val s = Slot(n, k, p)
        p.sharing = store.getBoolean(s.k(K_SHARING), true)
        p.gateway = store.getBoolean(K_GATEWAY, true)
        p.meshNode = myNode
        // Made once per party, kept with it: it proves my id on the party's private servers.
        p.token = store.getString(s.k(K_TOKEN)) ?: newToken().also { store.putString(s.k(K_TOKEN), it) }
        // Its colour on this device: one no other party has, kept.
        val color = store.getInt(s.k(K_PARTY_COLOR), 0).takeIf { it != 0 }
            ?: (PARTY_COLORS.firstOrNull { c -> slots.none { it.state.color == c } } ?: PARTY_COLORS[n % PARTY_COLORS.size])
                .also { store.putInt(s.k(K_PARTY_COLOR), it) }
        val layers = PartyLayers(
            members = store.getBoolean(s.k(K_SHOW_MEMBERS), true),
            trails = store.getBoolean(s.k(K_SHOW_TRAILS), true),
            marks = store.getBoolean(s.k(K_SHOW_MARKS), true),
        )
        s.state = PartyState(party = k, me = me(id), sharing = p.sharing, color = color, layers = layers)
        reserveSeq(s)
        return s
    }

    private fun forgetKeys(n: Int) {
        for (name in PER_PARTY) store.remove("p$n.$name")
    }

    /** With a [service]: starts it again if parties were on when the app closed (or it stopped by itself). */
    fun resumeIfWasActive() {
        for (s in slots.toList()) {
            if (!store.getBoolean(s.k(K_ACTIVE), false)) continue
            if (!s.on) turn(s, true) else if (hub == null) running(true)
        }
    }

    /** The switches, the radio, my look and the connections hidden on the map, as kept. */
    private fun settings(s: PartyState) = s.copy(
        onlineEnabled = store.getBoolean(K_ONLINE, true),
        radioName = if (radios != null) store.getString(K_RADIO_NAME) else null,
        gateway = store.getBoolean(K_GATEWAY, true),
        look = keptLook(),
        hiddenConnections = store.getStringSet(K_HIDDEN).orEmpty(),
        nodesShown = store.getBoolean(K_SHOW_NODES, true),
    )

    private fun keptLook(): PartyMe? =
        if (!store.contains(K_NAME) && !store.contains(K_COLOR)) null
        else PartyMe(0, store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]), store.getInt(K_ICON, 0))

    private fun me(id: Int) = PartyMe(id, store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]), store.getInt(K_ICON, 0))

    private fun profile() = LinkProfile(store.getString(K_NAME).orEmpty(), store.getInt(K_COLOR, PartyPalette.PRESETS[0]) and 0xFFFFFF, store.getInt(K_ICON, 0))

    private fun slotOf(p: LinkParty) = slots.firstOrNull { it.party === p }

    private fun slotOf(k: PartyKey) = slots.firstOrNull { it.key.id.contentEquals(k.id) }

    // Joining, picking and leaving.

    override fun create(partyName: String, myName: String) = join(PartyKey.create(partyName, defaultServer), myName)

    override val defaultServer: String? get() = store.getString(K_SERVER)

    /** Joins [key] beside the parties I'm in (one I'm in already: shown), and shows it. */
    override fun join(key: PartyKey, myName: String) {
        slotOf(key)?.let {
            select(it.key)
            return
        }
        if (slots.size >= PartyController.MAX_PARTIES) return
        // My name is the same in every party: a new one is the others' too.
        val name = myName.trim().ifEmpty { store.getString(K_NAME)?.takeIf { it.isNotBlank() } ?: T.party.defaultMyName }
        val renamed = slots.isNotEmpty() && name != store.getString(K_NAME)
        store.putString(K_NAME, name)
        if (!store.contains(K_COLOR)) store.putInt(K_COLOR, PartyPalette.PRESETS.random())
        common = common.copy(look = keptLook())
        if (renamed) profileChanged()
        // A new id per party: nothing links my ids in two parties.
        val id = randomBytes(4).let { b ->
            (b[0].toInt() shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        }.let { if (it == 0) 1 else it }
        val n = (slots.maxOfOrNull { it.n } ?: -1) + 1
        store.putString("p$n.$K_LINK", key.toLink())
        store.putInt("p$n.$K_ID", id)
        val s = open(n, key, id, Random.nextInt(65_536))
        slots += s
        saveSlots()
        selected = s
        store.putInt(K_SELECTED, n)
        refresh()
        setActive(true)
    }

    override fun select(party: PartyKey) {
        val s = slotOf(party) ?: return
        selected = s
        store.putInt(K_SELECTED, s.n)
        publish()
    }

    // What the map shows.

    override fun setLayers(party: PartyKey, layers: PartyLayers) {
        val s = slotOf(party) ?: return
        store.putBoolean(s.k(K_SHOW_MEMBERS), layers.members)
        store.putBoolean(s.k(K_SHOW_TRAILS), layers.trails)
        store.putBoolean(s.k(K_SHOW_MARKS), layers.marks)
        s.state = s.state.copy(layers = layers)
        publish()
    }

    override fun setConnectionShown(id: String, shown: Boolean) {
        val hidden = if (shown) common.hiddenConnections - id else common.hiddenConnections + id
        store.putStringSet(K_HIDDEN, hidden)
        common = common.copy(hiddenConnections = hidden)
        publish()
    }

    override fun setNodesShown(shown: Boolean) {
        store.putBoolean(K_SHOW_NODES, shown)
        common = common.copy(nodesShown = shown)
        publish()
    }

    /** Tells the others in the shown party (the servers forget what they kept of me), then lets it go. */
    override fun leave() {
        val s = selected ?: return
        forget(s)
        val h = hub
        val inHub = h != null && s.party in h.parties
        s.on = false
        if (h == null || !inHub) {
            settle()
            return
        }
        h.send(s.party, s.party.leave(clock()))
        h.removeParty(s.party)
        refresh()
        // The leave goes out first; then what no party uses any more closes.
        scope.launch {
            delay(LEAVE_MS)
            settle()
        }
    }

    private fun forget(s: Slot) {
        slots -= s
        forgetKeys(s.n)
        saveSlots()
        if (selected === s) {
            selected = slots.firstOrNull()
            selected?.let { store.putInt(K_SELECTED, it.n) } ?: store.remove(K_SELECTED)
        }
    }

    /** The shown party moves to [server] (null: the public ones), and new parties start there. */
    override fun setServer(server: String?) {
        if (server == null) store.remove(K_SERVER) else store.putString(K_SERVER, server)
        val s = selected ?: return
        val k = s.key.withServer(server)
        s.key = k
        store.putString(s.k(K_LINK), k.toLink())
        s.state = s.state.copy(party = k)
        s.refused.clear()
        val h = hub
        if (h != null && s.party in h.parties && common.onlineEnabled) {
            // Off the old servers, onto the new.
            h.uses(s.party).filter { h.connection(it)?.kind == ConnectionKind.ONLINE }.forEach { h.use(s.party, it, on = false) }
            goOnline(s)
            closeUnused()
        }
        refresh()
    }

    // Running.

    /** The shown party on or off. */
    override fun setActive(on: Boolean) {
        val s = selected ?: return
        store.putBoolean(s.k(K_ACTIVE), on)
        turn(s, on)
    }

    private fun turn(s: Slot, on: Boolean) {
        if (s.on == on) return
        s.on = on
        if (on) {
            if (hub != null) enter(s) else running(true) // attach() takes in every party that's on
        } else {
            hub?.let { h -> if (s.party in h.parties) h.removeParty(s.party) }
            settle()
        }
        refresh()
    }

    /** After a party went off or left: what no party uses closes; with none on, it all stops. */
    private fun settle() {
        if (slots.none { it.on }) running(false) else closeUnused()
        refresh()
    }

    /** Runs (connected, ticking, following my position) while any party is on. */
    private fun running(on: Boolean) {
        val svc = service
        if (svc != null) {
            if (on) {
                if (hub == null) svc(true)
            } else {
                detach()
                svc(false)
            }
            return
        }
        if (on) {
            attach()
            if (locating == null) {
                locating = scope.launch {
                    // Again when they end (no permission yet, or it was taken back).
                    while (true) {
                        locations().collect { onFix(it) }
                        delay(LOCATION_RETRY_MS)
                    }
                }
            }
        } else {
            locating?.cancel()
            locating = null
            detach()
        }
    }

    /** Connects: the radio, and each party that's on to its servers (if online is on) and the radio. */
    fun attach() {
        if (hub != null) return
        // Started by the platform by itself (Android bringing its service back): the parties that were on.
        if (slots.none { it.on }) slots.filter { store.getBoolean(it.k(K_ACTIVE), false) }.forEach { it.on = true }
        if (slots.none { it.on }) return
        hub = LinkHub(sockets, hubListener, clock)
        startRadio()
        for (s in slots) if (s.on) enter(s)
        ticking = scope.launch { tickLoop() }
        refresh()
    }

    fun detach() {
        val h = hub ?: return
        ticking?.cancel()
        ticking = null
        stopRadio()
        h.connections.map { it.id }.forEach(h::removeConnection)
        h.parties.toList().forEach(h::removeParty)
        hub = null
        _fixEveryMs.value = null
        for (s in slots) s.state = s.state.copy(online = OFF)
        common = common.copy(radio = OFF)
        refresh()
    }

    /** [s] into the running hub: on its servers and the radio. */
    private fun enter(s: Slot) {
        val h = hub ?: return
        h.addParty(s.party)
        if (common.onlineEnabled) goOnline(s)
        radioConn?.let { h.use(s.party, it) }
    }

    /** My position, from the platform: ticked for at once (it may be worth sending). */
    fun onFix(fix: GeoFix) {
        common = common.copy(myPosition = fix)
        for (s in slots) s.party.onFix(fix)
        publish()
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

    /** [s]'s servers, else the public ones (one connection, shared by the parties on them). */
    private fun servers(s: Slot): Map<ConnectionId, List<String>> {
        val own = s.key.servers
        return if (own.isEmpty()) mapOf(PUBLIC to publicServers) else own.associate { "$SERVER_PREFIX$it" to listOf(it) }
    }

    private fun goOnline(s: Slot) {
        val h = hub ?: return
        for ((id, urls) in servers(s)) {
            h.addServer(id, urls, private = id != PUBLIC)
            h.use(s.party, id)
        }
    }

    private fun stopOnline() {
        val h = hub ?: return
        h.connections.filter { it.kind == ConnectionKind.ONLINE }.map { it.id }.forEach(h::removeConnection)
        for (s in slots) s.state = s.state.copy(online = OFF)
    }

    /** The servers no party uses any more: closed. */
    private fun closeUnused() {
        val h = hub ?: return
        val used = h.parties.flatMap { h.uses(it) }.toSet()
        h.connections.filter { it.kind == ConnectionKind.ONLINE && it.id !in used }.map { it.id }.forEach(h::removeConnection)
    }

    /** [s]'s servers as one line on the screens: connected if any is, with their hosts. */
    private fun onlineStatus(s: Slot): TransportStatus {
        val h = hub ?: return OFF
        // A server that took the party back: no longer refused.
        s.refused.removeAll { id -> s.party.connections.any { it.id == id && it.up } }
        val all = h.uses(s.party).mapNotNull(h::connection).filter { it.kind == ConnectionKind.ONLINE }.map { c ->
            if (c.id in s.refused && c.status.state == TransportStatus.State.CONNECTED) {
                TransportStatus(TransportStatus.State.ERROR, T.party.refusedByServer(c.status.detail))
            } else {
                c.status
            }
        }
        val up = all.filter { it.state == TransportStatus.State.CONNECTED }
        return if (up.isNotEmpty()) TransportStatus(TransportStatus.State.CONNECTED, up.joinToString(", ") { it.detail })
        else all.firstOrNull { it.state == TransportStatus.State.CONNECTING }
            ?: all.firstOrNull { it.state == TransportStatus.State.ERROR }
            ?: OFF
    }

    /** The radio, shared by every party that's on. */
    private fun startRadio() {
        val links = radios ?: return
        val h = hub ?: return
        if (radio != null) return
        val address = store.getString(K_RADIO_ID) ?: return
        val id = "$RADIO_PREFIX$address"
        val r = links.open(address, store.getString(K_RADIO_NAME) ?: address, object : MeshRadio.Listener {
            override fun onStatus(status: TransportStatus) {
                if (radioConn == id) hub?.radioStatus(id, status)
            }

            override fun onMyNode(num: Long) {
                myNode = num
                for (s in slots) s.party.meshNode = num
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
            common = common.copy(radio = TransportStatus(TransportStatus.State.ERROR, T.party.bluetoothPermissionNeeded))
            publish()
            return
        }
        h.addRadio(id, LinkRadio { r.send(it) })
        radio = r
        radioConn = id
        for (s in slots) if (s.on) h.use(s.party, id)
        r.start()
    }

    private fun stopRadio() {
        radio?.stop()
        radio = null
        radioConn?.let { hub?.removeConnection(it) }
        radioConn = null
        common = common.copy(radio = OFF)
        publish()
    }

    /** Plain Meshtastic radios (trackers, others' radios): not mine, not a member's in any party. */
    private fun meshNode(num: Long, name: String?, shortName: String?, position: Mesh.Position?) {
        if (num == 0L || num == myNode || slots.any { s -> s.party.members.values.any { it.meshNode == num } }) return
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

    /** Shares my position in the shown party, or not (the others still seen). */
    override fun setSharing(on: Boolean) {
        val s = selected ?: return
        store.putBoolean(s.k(K_SHARING), on)
        s.party.sharing = on
        s.state = s.state.copy(sharing = on)
        refresh()
        wakeNow()
    }

    /** My name, colour and icon: the same in every party (each is told). */
    override fun setProfile(name: String, color: Int, icon: Int) {
        val was = selected?.state?.me ?: common.look ?: PartyMe(0, "", color, icon)
        store.putString(K_NAME, name.trim().ifEmpty { was.name })
        store.putInt(K_COLOR, color)
        store.putInt(K_ICON, icon)
        common = common.copy(look = keptLook())
        profileChanged()
        refresh()
    }

    private fun profileChanged() {
        for (s in slots) {
            s.party.profile = profile()
            s.state = s.state.copy(me = me(s.party.me))
            act(s) { p, now -> p.helloNow(now) }
        }
    }

    override fun setOnlineEnabled(on: Boolean) {
        store.putBoolean(K_ONLINE, on)
        common = common.copy(onlineEnabled = on)
        if (hub != null) {
            if (on) slots.filter { it.on }.forEach(::goOnline) else stopOnline()
        }
        refresh()
    }

    override fun setGateway(on: Boolean) {
        store.putBoolean(K_GATEWAY, on)
        for (s in slots) s.party.gateway = on
        common = common.copy(gateway = on)
        publish()
    }

    override fun setRadio(address: String, name: String) {
        if (radios == null) return
        store.putString(K_RADIO_ID, address)
        store.putString(K_RADIO_NAME, name)
        common = common.copy(radioName = name)
        stopRadio()
        startRadio()
    }

    override fun forgetRadio() {
        store.remove(K_RADIO_ID)
        store.remove(K_RADIO_NAME)
        stopRadio()
        common = common.copy(radioName = null)
        publish()
    }

    // Actions.

    /**
     * An SOS goes to every party that's on, and the shown one: help may be in any of them. Its
     * end goes wherever one is standing.
     */
    override fun sos(active: Boolean, text: String) {
        val to = if (active) slots.filter { it.on || it === selected } else slots.filter { it.party.mySos }
        for (s in to) act(s) { p, now -> p.sos(active, text, now) }
    }

    override fun setMeeting(lat: Double, lon: Double, label: String) = actShown { p, now -> p.setMeeting(GridPoint.of(lat, lon), label, now) }

    override fun clearMeeting() = actShown { p, now -> p.clearMeeting(now) }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        actShown { p, now -> p.text(text.trim(), now) }
    }

    private fun actShown(f: (LinkParty, Long) -> LinkResult) {
        selected?.let { act(it, f) }
    }

    /** My action in [s]: sent where connected (else it goes when they come up); it announces nothing to me. */
    private fun act(s: Slot, f: (LinkParty, Long) -> LinkResult) {
        val r = f(s.party, clock())
        hub?.send(s.party, LinkResult(out = r.out))
        wakeNow()
        afterSend()
        refresh()
    }

    override fun history(member: Int): List<HistoryPoint> = selected?.party?.history?.of(member).orEmpty().map {
        HistoryPoint(it.time * 1000, PartyMessage.Position(it.at.latDeg, it.at.lonDeg, null, null, null, null, null))
    }

    // What the screens see.

    private fun announce(s: Slot, event: LinkEvent) {
        val k = s.key
        val e = when (event) {
            is LinkEvent.Joined -> PartyEvent.Joined(member(event.member), k)
            is LinkEvent.Left -> PartyEvent.Left(member(event.member), k)
            is LinkEvent.SosRaised -> PartyEvent.SosRaised(member(event.member), event.text, k)
            is LinkEvent.SosCancelled -> PartyEvent.SosCancelled(member(event.member), k)
            is LinkEvent.TextReceived -> PartyEvent.TextReceived(member(event.member), event.text, k)
            is LinkEvent.MarkChanged -> if (event.mark.id == MarkId.MEETING) PartyEvent.MeetingSet(meeting(s.party), k) else null
            else -> null
        }
        e?.let { _events.tryEmit(it) }
    }

    /** Each party's members, chat, meeting point and servers read again, then shown. */
    private fun refresh() {
        val h = hub
        for (s in slots) {
            val p = s.party
            s.state = s.state.copy(
                members = p.members.values.filter { !it.left }.map(::member)
                    .sortedWith(compareByDescending<Member> { it.sos }.thenBy { it.displayName.lowercase() }),
                meeting = meeting(p),
                chat = p.chat.toList(),
                mySos = p.mySos,
                historyCounts = p.history.counts,
                recentWays = recentWays(p),
                marks = marks(s),
                online = if (h != null && common.onlineEnabled && p in h.parties) onlineStatus(s) else OFF,
            )
        }
        common = common.copy(
            meshNodes = meshNodes.values.filter { it.position != null }.sortedBy { it.name },
            connections = connections(),
        )
        publish()
        _fixEveryMs.value = fixEvery()
    }

    /** [s]'s marks as the map draws them; read again only when one changed (or came over another connection). */
    private fun marks(s: Slot): List<PartyMark> {
        val book = s.party.marks
        var seen = book.all.size.toLong()
        for (e in book.all) {
            seen = seen * 31 + e.id.hashCode()
            seen = seen * 31 + (e.lastEdited ?: 0) + 7L * (e.lastEditor ?: 0) + 13L * e.via.size + (if (e.isShown) 1 else 0) + (if (e.mine) 2 else 0)
        }
        if (seen == s.marksSeen) return s.state.marks
        s.marksSeen = seen
        return book.all.filter { it.isShown && it.id != MarkId.MEETING }.mapNotNull(::mark)
    }

    private fun mark(e: MarkEntry): PartyMark? {
        fun at(p: GridPoint) = app.maparound.routing.GeoPoint(p.latDeg, p.lonDeg)
        val shape = when (val g = e.geom?.value) {
            is Geom.Point -> MarkShape.Point(at(g.at))
            is Geom.Circle -> MarkShape.Circle(at(g.center), g.radiusM.toDouble())
            is Geom.Path -> if (g.area) MarkShape.Area(g.vertices.map(::at)) else MarkShape.Line(g.vertices.map(::at))
            null -> return null
        }
        val style = e.style?.value
        val text = e.text?.value
        return PartyMark(
            id = "${hex(e.id.creator).padStart(8, '0')}.${e.id.counter}",
            shape = shape,
            name = text?.name.orEmpty(),
            note = text?.note.orEmpty(),
            icon = style?.icon,
            rgb = style?.rgb,
            width = style?.width ?: Width.NORMAL,
            line = style?.line ?: LineStyle.SOLID,
            fill = style?.fill ?: Fill.NONE,
            via = e.via,
            mine = e.mine,
            editor = e.lastEditor,
            edited = e.lastEdited?.let { it * 1000 },
        )
    }

    /** Each member's way over the last half hour (their positions and the trails they sent), for the trails layer. */
    private fun recentWays(p: LinkParty): Map<Int, List<WayPoint>> {
        val since = (clock() - PartyState.RECENT_WAY_MS) / 1000
        val out = HashMap<Int, List<WayPoint>>()
        for (m in p.members.values) {
            if (m.left) continue
            val all = p.history.of(m.id)
            // Oldest first: from the first one in the half hour.
            var i = all.size
            while (i > 0 && all[i - 1].time >= since) i--
            if (all.size - i >= 2) out[m.id] = all.subList(i, all.size).map { WayPoint(it.at.latDeg, it.at.lonDeg, it.time * 1000) }
        }
        return out
    }

    /** The hub's connections as the layers show them: what each is, how it's doing, what it brought. */
    private fun connections(): List<PartyConnection> {
        val h = hub ?: return emptyList()
        return h.connections.map { c ->
            val heard = slots.flatMap { s -> s.party.members.values.filter { !it.left }.mapNotNull { it.heard[c.id] } }
            val radio = c.kind == ConnectionKind.LORA
            PartyConnection(
                id = c.id,
                radio = radio,
                label = when {
                    c.id == PUBLIC -> T.party.publicServers
                    radio -> store.getString(K_RADIO_NAME) ?: c.id.removePrefix(RADIO_PREFIX)
                    else -> PartyRelays.host(c.id.removePrefix(SERVER_PREFIX))
                },
                status = c.status,
                lastHeard = heard.maxOrNull() ?: 0,
                members = heard.size,
                nodes = if (radio) meshNodes.values.count { it.position != null } else 0,
            )
        }
    }

    /** The parties, each with what they share, to the screens: all to the map, the picked one to the rest. */
    private fun publish() {
        _parties.value = slots.map(::shown)
        _state.value = selected?.let(::shown) ?: shared(PartyState())
    }

    private fun shown(s: Slot) = shared(s.state).copy(active = s.on && hub?.parties?.contains(s.party) == true)

    private fun shared(p: PartyState) = p.copy(
        look = common.look,
        onlineEnabled = common.onlineEnabled,
        radio = common.radio,
        radioName = common.radioName,
        gateway = common.gateway,
        myPosition = common.myPosition,
        meshNodes = common.meshNodes,
        connections = common.connections,
        hiddenConnections = common.hiddenConnections,
        nodesShown = common.nodesShown,
    )

    /** How often a fix is worth taking now ([fixEveryMs]): what the busiest party needs. */
    private fun fixEvery(): Long? {
        val h = hub ?: return null
        val sending = slots.filter { it.party in h.parties && (it.party.sharing || it.party.mySos) }.map { it.party }
        if (sending.isEmpty()) return null
        // Standing still: just often enough to notice moving again, and to have a fresh fix for a heartbeat.
        val moving = sending.filter { it.isMoving || it.mySos }
        if (moving.isEmpty()) return FIX_STILL_MS
        val gaps = moving.flatMap { p -> p.connections.map { c -> ModeTable.of(if (p.mySos) LinkMode.LIVE else c.mode?.mode ?: LinkMode.NORMAL, c.kind).minGapS * 1000 } }
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
        heardOn = m.heard,
        positionsOn = m.positions.mapValues { (_, v) -> TimedPosition(position(v.first), v.second * 1000) },
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

    /** After sending: each party's sequence numbers reserved ahead, its marks' counter and clock kept. */
    private fun afterSend() {
        for (s in slots) {
            val p = s.party
            if (((p.seq - s.seqBase) and 0xFFFF) >= SEQ_BLOCK - SEQ_MARGIN) reserveSeq(s)
            if (p.marks.nextCounter != s.savedCounter) {
                s.savedCounter = p.marks.nextCounter
                store.putString(s.k(K_COUNTER), s.savedCounter.toString())
            }
            if (p.marks.clock != s.savedClock) {
                s.savedClock = p.marks.clock
                store.putString(s.k(K_CLOCK), s.savedClock.toString())
            }
        }
    }

    /** Sequence numbers must never repeat: reserved in blocks, so a restart starts past them. */
    private fun reserveSeq(s: Slot) {
        s.seqBase = s.party.seq
        store.putString(s.k(K_SEQ), ((s.party.seq + SEQ_BLOCK) and 0xFFFF).toString())
    }

    companion object {
        /** The public servers' connection. */
        const val PUBLIC = "public"

        /** A member's colour before their hello. */
        private const val NO_HELLO_RGB = 0x808080
        const val SERVER_PREFIX = "mqtt:"
        const val RADIO_PREFIX = "radio:"
        private val OFF = TransportStatus(TransportStatus.State.OFF)

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

        // Kept for all the parties.
        private const val K_SLOTS = "parties"
        private const val K_SELECTED = "shown_party"
        private const val K_SERVER = "server"
        private const val K_NAME = "my_name"
        private const val K_COLOR = "my_rgb"
        private const val K_ICON = "my_icon"
        private const val K_ONLINE = "online"
        private const val K_GATEWAY = "gateway"
        private const val K_RADIO_ID = "radio_id"
        private const val K_RADIO_NAME = "radio_name"
        private const val K_HIDDEN = "hidden_connections"
        private const val K_SHOW_NODES = "show_nodes"

        /** The parties' colours on this device: strong, apart from each other and from an SOS's red. */
        val PARTY_COLORS = listOf(0xFF1E88E5, 0xFFFFB300, 0xFF8E24AA, 0xFF00897B, 0xFFD81B60, 0xFF7CB342).map { it.toInt() }

        // Kept for each party, as `p<n>.<key>` (before several parties: without the prefix).
        private const val K_LINK = "link"
        private const val K_ACTIVE = "active"
        private const val K_ID = "my_id"
        private const val K_SHARING = "sharing"
        private const val K_SEQ = "link_seq"
        private const val K_COUNTER = "marks_counter"
        private const val K_CLOCK = "marks_clock"
        private const val K_TOKEN = "link_token"
        private const val K_PARTY_COLOR = "color"
        private const val K_SHOW_MEMBERS = "show_members"
        private const val K_SHOW_MARKS = "show_marks"
        private const val K_SHOW_TRAILS = "show_trails"
        private val PER_PARTY = listOf(K_LINK, K_ACTIVE, K_ID, K_SHARING, K_SEQ, K_COUNTER, K_CLOCK, K_TOKEN, K_PARTY_COLOR, K_SHOW_MEMBERS, K_SHOW_TRAILS, K_SHOW_MARKS)
    }
}
