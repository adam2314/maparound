package app.maparound.party

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import app.maparound.sensors.toGeoFix
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.maparound.MainActivity
import app.maparound.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/**
 * The party the user is in, and everything that moves its packets: online (MQTT) and LoRa
 * (Meshtastic radio). Lives for the whole process; [PartyService] keeps it running with the
 * screen off. All state changes happen on the main thread.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object PartySession : PartyController {

    private val _state = MutableStateFlow(PartyState())

    /** The last fix from the service, as Android gives it (speed, bearing… for sending). */
    private var lastLocation: Location? = null
    override val state: StateFlow<PartyState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PartyEvent> = _events.asSharedFlow()

    /** A party link opened from outside the app, waiting for the user to confirm joining. */
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("party", Context.MODE_PRIVATE) }
    private var engine: PartyEngine? = null
    private var mqtt: MqttTransport? = null
    private var radio: MeshtasticTransport? = null
    private var seq = 0L
    private var seqReserved = 0L
    private val lastSent = HashMap<Via, SendPolicy.Last>()
    private val lastHello = HashMap<Via, Long>()

    // ---- setup --------------------------------------------------------------------------------

    fun init(context: Context) {
        app = context.applicationContext
        val key = prefs.getString("link", null)?.let(PartyKey::parse)
        if (key != null) {
            val me = PartyMe(prefs.getInt("my_id", 0), prefs.getString("my_name", "")!!, prefs.getInt("my_color", 0))
            engine = PartyEngine(key, me.id)
            seq = prefs.getLong("seq", 0)
            reserveSeq()
            _state.value = PartyState(
                party = key, me = me,
                sharing = prefs.getBoolean("sharing", true),
                onlineEnabled = prefs.getBoolean("online", true),
                radioName = prefs.getString("radio_name", null),
                gateway = prefs.getBoolean("gateway", true),
                mySos = false,
            )
        }
    }

    /**
     * Called when the app's UI starts: if sharing was on when the app was closed, carry on.
     * (Not from Application.onCreate: a foreground service may not be started from the background.)
     */
    fun resumeIfWasActive() {
        if (engine != null && prefs.getBoolean("active", false) && !_state.value.active) setActive(true)
    }

    override fun create(partyName: String, myName: String) = join(PartyKey.create(partyName), myName)

    override fun join(key: PartyKey, myName: String) {
        leaveSilently()
        val me = PartyMe(SecureRandom().nextInt().let { if (it == 0) 1 else it }, myName.trim().ifEmpty { "Me" }, SecureRandom().nextInt(PALETTE_SIZE))
        prefs.edit {
            putString("link", key.toLink())
            putInt("my_id", me.id)
            putString("my_name", me.name)
            putInt("my_color", me.color)
            putLong("seq", 1)
        }
        seq = 1
        reserveSeq()
        engine = PartyEngine(key, me.id)
        lastSent.clear()
        lastHello.clear()
        _state.value = _state.value.copy(party = key, me = me, members = emptyList(), meshNodes = emptyList(), meeting = null, chat = emptyList(), mySos = false)
        setActive(true)
    }

    /** Tells the others, clears our retained state on the broker, stops sharing. */
    override fun leave() {
        val e = engine ?: return
        sendNow(PartyMessage.Leave, transports())
        // Empty retained messages delete our last position/name/alerts from the broker.
        val me = hex(e.myId)
        mqtt?.let { m -> listOf("pos", "hello", "sos", "meet").forEach { m.publish(me, it, ByteArray(0), retained = true) } }
        main.postDelayed({ leaveSilently() }, 1500)
    }

    private fun leaveSilently() {
        setActive(false)
        engine = null
        prefs.edit { remove("link"); putBoolean("active", false) }
        _state.value = PartyState(onlineEnabled = _state.value.onlineEnabled, radioName = _state.value.radioName, gateway = _state.value.gateway)
    }

    /** Starts/stops [PartyService] (sending and receiving). */
    override fun setActive(on: Boolean) {
        if (on && engine == null) return
        prefs.edit { putBoolean("active", on) }
        val intent = Intent(app, PartyService::class.java)
        runCatching { if (on) ContextCompat.startForegroundService(app, intent) else app.stopService(intent) }
    }

    override fun setSharing(on: Boolean) {
        prefs.edit { putBoolean("sharing", on) }
        update { it.copy(sharing = on) }
        if (on) lastSent.clear() // send soon
    }

    override fun setProfile(name: String, color: Int) {
        val me = _state.value.me ?: return
        val updated = me.copy(name = name.trim().ifEmpty { me.name }, color = color)
        prefs.edit { putString("my_name", updated.name); putInt("my_color", updated.color) }
        update { it.copy(me = updated) }
        sendHello(force = true)
    }

    override fun setOnlineEnabled(on: Boolean) {
        prefs.edit { putBoolean("online", on) }
        update { it.copy(onlineEnabled = on) }
        if (_state.value.active) restartTransports()
    }

    override fun setGateway(on: Boolean) {
        prefs.edit { putBoolean("gateway", on) }
        update { it.copy(gateway = on) }
    }

    override fun setRadio(address: String, name: String) {
        prefs.edit { putString("radio_address", address); putString("radio_name", name) }
        update { it.copy(radioName = name) }
        if (_state.value.active) restartTransports()
    }

    override fun forgetRadio() {
        prefs.edit { remove("radio_address"); remove("radio_name") }
        radio?.stop()
        radio = null
        update { it.copy(radioName = null, radio = TransportStatus(TransportStatus.State.OFF)) }
    }

    // ---- actions --------------------------------------------------------------------------------

    override fun sos(active: Boolean, text: String) {
        update { it.copy(mySos = active) }
        sendNow(PartyMessage.Sos(active, text), transports())
        lastSent.clear() // positions with the SOS flag go out right away
        lastLocation?.let { onLocation(it) }
    }

    override fun setMeeting(lat: Double, lon: Double, label: String) = sendNow(PartyMessage.Meet(lat, lon, label), transports())

    override fun clearMeeting() = sendNow(PartyMessage.Meet(0.0, 0.0, ""), transports())

    override fun sendText(text: String) {
        if (text.isBlank()) return
        sendNow(PartyMessage.Text(text.trim()), transports())
    }

    // ---- service hooks ----------------------------------------------------------------------------

    internal fun attach() {
        update { it.copy(active = true) }
        restartTransports()
    }

    internal fun detach() {
        mqtt?.stop()
        mqtt = null
        radio?.stop()
        radio = null
        update {
            it.copy(active = false, online = TransportStatus(TransportStatus.State.OFF), radio = TransportStatus(TransportStatus.State.OFF))
        }
    }

    /** Our own position (from the service). Sends it on each transport when due. */
    internal fun onLocation(location: Location) {
        lastLocation = location
        update { it.copy(myPosition = location.toGeoFix()) }
        if (engine == null) return
        val s = _state.value
        if (!s.sharing && !s.mySos) return
        val now = System.currentTimeMillis()
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        val due = transports().filter { via ->
            SendPolicy.due(via, lastSent[via], now, location.latitude, location.longitude, speedKmh, s.mySos)
        }.toSet()
        if (due.isEmpty()) return
        val msg = PartyMessage.Position(
            lat = location.latitude,
            lon = location.longitude,
            altM = if (location.hasAltitude()) location.altitude.toInt() else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toInt() else null,
            speedKmh = if (location.hasSpeed()) speedKmh.toInt() else null,
            headingDeg = if (location.hasBearing() && speedKmh > 2) location.bearing.toInt() else null,
            batteryPct = battery(),
            sos = s.mySos,
            driving = speedKmh >= SendPolicy.DRIVING_KMH,
        )
        sendNow(msg, due)
        due.forEach { lastSent[it] = SendPolicy.Last(now, location.latitude, location.longitude) }
    }

    /** Every few seconds from the service: hellos, and positions when standing still. */
    internal fun tick() {
        sendHello(force = false)
        lastLocation?.let { onLocation(it) }
        refresh()
    }

    // ---- transports --------------------------------------------------------------------------------

    private fun transports(): Set<Via> = buildSet {
        if (mqtt?.isConnected == true) add(Via.ONLINE)
        if (radio?.isReady == true) add(Via.LORA)
    }

    private fun restartTransports() {
        val e = engine ?: return
        mqtt?.stop()
        mqtt = null
        radio?.stop()
        radio = null
        if (_state.value.onlineEnabled) {
            mqtt = MqttTransport(
                e.key.tagHex,
                onPacket = { bytes -> main.post { onPacket(bytes, Via.ONLINE) } },
                onStatus = { st ->
                    main.post {
                        update { it.copy(online = st) }
                        if (st.state == TransportStatus.State.CONNECTED) {
                            lastHello.remove(Via.ONLINE)
                            lastSent.remove(Via.ONLINE)
                            sendHello(force = false)
                        }
                    }
                },
            ).also { it.start() }
        } else {
            update { it.copy(online = TransportStatus(TransportStatus.State.OFF)) }
        }
        val address = prefs.getString("radio_address", null)
        if (address != null && hasBluetoothPermission()) {
            radio = MeshtasticTransport(app, address, object : MeshtasticTransport.Listener {
                override fun onStatus(status: TransportStatus) {
                    update { it.copy(radio = status) }
                    if (status.state == TransportStatus.State.CONNECTED) {
                        lastHello.remove(Via.LORA)
                        lastSent.remove(Via.LORA)
                        sendHello(force = false)
                    }
                }

                override fun onMyNode(num: Long) {
                    engine?.myMeshNode = num
                }

                override fun onPartyPacket(payload: ByteArray) = onPacket(payload, Via.LORA)

                override fun onNode(num: Long, longName: String?, shortName: String?, position: Mesh.Position?) {
                    engine?.meshNode(num, longName, shortName, position)
                    refresh()
                }
            }).also { it.start() }
        } else if (address != null) {
            update { it.copy(radio = TransportStatus(TransportStatus.State.ERROR, "Bluetooth permission needed")) }
        }
    }

    private fun onPacket(bytes: ByteArray, via: Via) {
        val e = engine ?: return
        val accepted = e.receive(bytes, via) ?: run { refresh(); return }
        // Gateway: relay to the other transport, so radio-only and online-only members meet.
        if (_state.value.gateway) {
            for (to in transports()) {
                if (e.shouldForward(accepted, via, to)) forwardRaw(bytes, accepted, to)
            }
        }
        for (ev in accepted.events) {
            _events.tryEmit(ev)
            notifyIfNeeded(ev)
            if (ev is PartyEvent.Joined) sendHello(force = false, reply = true) // introduce ourselves
        }
        refresh()
    }

    private fun forwardRaw(bytes: ByteArray, accepted: PartyEngine.Accepted, to: Via) {
        when (to) {
            Via.ONLINE -> {
                val (kind, retained) = MqttTransport.kindOf(accepted.received.message)
                mqtt?.publish(hex(accepted.received.sender), kind, bytes, retained)
            }
            Via.LORA -> radio?.send(bytes)
            Via.NEARBY -> Unit
        }
    }

    private fun sendNow(message: PartyMessage, vias: Set<Via>) {
        val e = engine ?: return
        val me = _state.value.me ?: return
        val now = System.currentTimeMillis()
        val bytes = Envelope.encode(e.key, me.id, nextSeq(), now, message)
        e.applyOwn(message, now)
        for (via in vias) {
            when (via) {
                Via.ONLINE -> {
                    val (kind, retained) = MqttTransport.kindOf(message)
                    mqtt?.publish(hex(me.id), kind, bytes, retained)
                }
                Via.LORA -> radio?.send(bytes)
                Via.NEARBY -> Unit
            }
        }
        refresh()
    }

    /**
     * Hello carries name & colour: on connect (retained online), every 10 min online / 30 min over
     * LoRa, and as a [reply] to newcomers at most every 2 min.
     */
    private fun sendHello(force: Boolean, reply: Boolean = false) {
        val e = engine ?: return
        val me = _state.value.me ?: return
        val now = System.currentTimeMillis()
        val due = transports().filter { via ->
            val last = lastHello[via]
            when {
                force || last == null -> true
                reply -> now - last > 120_000
                else -> now - last > if (via == Via.LORA) 30 * 60_000L else 10 * 60_000L
            }
        }.toSet()
        if (due.isEmpty()) return
        due.forEach { lastHello[it] = now }
        sendNow(PartyMessage.Hello(me.name, me.color, e.myMeshNode), due)
    }

    private fun nextSeq(): Long {
        seq++
        if (seq >= seqReserved) reserveSeq()
        return seq
    }

    /** Sequence numbers must never repeat; reserve them in blocks so a crash can't reuse one. */
    private fun reserveSeq() {
        seqReserved = seq + 500
        prefs.edit { putLong("seq", seqReserved) }
    }

    // ---- state & notifications ---------------------------------------------------------------------

    private fun refresh() {
        val e = engine ?: return
        update { s ->
            s.copy(
                members = e.members.values.filterNot { it.left }
                    .sortedWith(compareByDescending<Member> { it.sos }.thenBy { it.displayName.lowercase() }),
                meshNodes = e.meshNodes.values.filter { it.position != null }.sortedBy { it.name },
                meeting = e.meeting,
                chat = e.chat.toList(),
            )
        }
    }

    private inline fun update(f: (PartyState) -> PartyState) {
        _state.value = f(_state.value)
    }

    private fun battery(): Int? =
        app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun hasBluetoothPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun notifyIfNeeded(ev: PartyEvent) {
        val (title, text, urgent) = when (ev) {
            is PartyEvent.SosRaised -> Triple("SOS from ${ev.member.displayName}", ev.text.ifBlank { "Needs help. Tap to see where." }, true)
            is PartyEvent.TextReceived -> Triple(ev.member.displayName, ev.text, false)
            is PartyEvent.MeetingSet -> ev.meeting?.let { Triple("New meeting point", it.label.ifBlank { "Tap to see it on the map" }, false) } ?: return
            else -> return
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        val channel = if (urgent) CHANNEL_SOS else CHANNEL_MESSAGES
        if (nm.getNotificationChannel(channel) == null) {
            nm.createNotificationChannel(
                if (urgent) {
                    NotificationChannel(CHANNEL_SOS, "Party SOS alerts", NotificationManager.IMPORTANCE_HIGH)
                        .apply { enableVibration(true) }
                } else {
                    NotificationChannel(CHANNEL_MESSAGES, "Party messages", NotificationManager.IMPORTANCE_DEFAULT)
                },
            )
        }
        val open = PendingIntent.getActivity(
            app, 2, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(app, channel)
            .setSmallIcon(R.drawable.ic_stat_party)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE)
            .build()
        runCatching { nm.notify((if (urgent) 1000 else 2000) + (ev.hashCode() and 0xFF), n) }
    }

    fun hex(id: Int): String = String.format("%08x", id)

    const val PALETTE_SIZE = 8
    private const val CHANNEL_SOS = "party_sos"
    private const val CHANNEL_MESSAGES = "party_messages"
}
