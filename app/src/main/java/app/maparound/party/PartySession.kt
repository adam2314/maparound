package app.maparound.party

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.maparound.MainActivity
import app.maparound.R
import app.maparound.data.SharedPrefsStore
import app.maparound.i18n.T
import app.maparound.party.link.LinkSession
import app.maparound.sensors.toGeoFix
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * The party on Android: the shared [LinkSession] (Link v1), with Android's parts. MQTT over
 * OkHttp's WebSockets, the Meshtastic radio over Bluetooth ([MeshtasticTransport]), and the
 * battery. [PartyService] runs it with the screen off. It also posts the notifications (SOS,
 * messages, meeting point). Lives for the whole process; everything happens on the main thread.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object PartySession : PartyController {
    private lateinit var app: Context
    private lateinit var session: LinkSession
    private val scope = MainScope()

    override val state: StateFlow<PartyState> get() = session.state
    override val parties: StateFlow<List<PartyState>> get() = session.parties
    private val _events = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<PartyEvent> = _events.asSharedFlow()
    override val pendingInvite: MutableStateFlow<PartyKey?> get() = session.pendingInvite

    fun init(context: Context) {
        app = context.applicationContext
        val store = SharedPrefsStore(app, "party")
        upgrade(store.sp)
        session = LinkSession(
            store,
            OkHttpMqttSockets,
            radios = { address, _, listener -> if (hasBluetoothPermission()) MeshtasticTransport(app, address, listener) else null },
            battery = ::battery,
            charging = ::charging,
            service = { on ->
                val intent = Intent(app, PartyService::class.java)
                runCatching { if (on) ContextCompat.startForegroundService(app, intent) else app.stopService(intent) }
            },
            scope = scope,
        )
        scope.launch {
            session.events.collect {
                _events.emit(it)
                notifyIfNeeded(it)
            }
        }
    }

    /** The settings as the versions before Link v1 kept them, as they're kept now. */
    private fun upgrade(sp: SharedPreferences) = sp.edit {
        if (sp.contains("radio_address") && !sp.contains("radio_id")) putString("radio_id", sp.getString("radio_address", null))
        // The first versions kept an index into PartyPalette.LEGACY.
        if (sp.contains("my_color") && !sp.contains("my_rgb")) putInt("my_rgb", PartyPalette.legacy(sp.getInt("my_color", 0)))
        remove("radio_address")
        remove("my_color")
        remove("seq")
    }

    /**
     * Called when the app's UI starts: if the party was on when the app was closed, carry on.
     * (Not from Application.onCreate: a foreground service may not be started from the background.)
     */
    fun resumeIfWasActive() = session.resumeIfWasActive()

    override val hasRadio: Boolean get() = true
    override val defaultServer: String? get() = session.defaultServer

    override fun create(partyName: String, myName: String) = session.create(partyName, myName)
    override fun join(key: PartyKey, myName: String) = session.join(key, myName)
    override fun select(party: PartyKey) = session.select(party)
    override fun setServer(server: String?) = session.setServer(server)
    override fun leave() = session.leave()
    override fun setActive(on: Boolean) = session.setActive(on)
    override fun setSharing(on: Boolean) = session.setSharing(on)
    override fun setProfile(name: String, color: Int, icon: Int) = session.setProfile(name, color, icon)
    override fun setOnlineEnabled(on: Boolean) = session.setOnlineEnabled(on)
    override fun setGateway(on: Boolean) = session.setGateway(on)
    override fun setRadio(address: String, name: String) = session.setRadio(address, name)
    override fun forgetRadio() = session.forgetRadio()
    override fun sos(active: Boolean, text: String) = session.sos(active, text)
    override fun setMeeting(lat: Double, lon: Double, label: String) = session.setMeeting(lat, lon, label)
    override fun clearMeeting() = session.clearMeeting()
    override fun sendText(text: String) = session.sendText(text)
    override fun history(member: Int): List<HistoryPoint> = session.history(member)

    // ---- service hooks ----------------------------------------------------------------------------

    internal fun attach() = session.attach()

    internal fun detach() = session.detach()

    internal fun onLocation(location: Location) = session.onFix(location.toGeoFix())

    /** How often the service should take a fix (null: none needed). */
    internal val fixEveryMs: StateFlow<Long?> get() = session.fixEveryMs

    internal fun networkBack() = session.networkBack()

    // ---- the phone ------------------------------------------------------------------------------------

    private fun battery(): Int? =
        app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun charging(): Boolean =
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true

    private fun hasBluetoothPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ---- notifications ------------------------------------------------------------------------------

    private fun notifyIfNeeded(ev: PartyEvent) {
        val (title, text, urgent) = when (ev) {
            is PartyEvent.SosRaised -> Triple(T.party.sosNotificationTitle(ev.member.displayName), ev.text.ifBlank { T.party.sosNeedsHelp }, true)
            is PartyEvent.TextReceived -> Triple(ev.member.displayName, ev.text, false)
            is PartyEvent.MeetingSet -> ev.meeting?.let { Triple(T.party.newMeetingPoint, it.label.ifBlank { T.party.tapToSeeOnMap }, false) } ?: return
            else -> return
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        val channel = if (urgent) CHANNEL_SOS else CHANNEL_MESSAGES
        // Each time, so an existing channel takes the name in the app's language now (only that changes).
        nm.createNotificationChannel(
            if (urgent) {
                NotificationChannel(CHANNEL_SOS, T.party.channelSos, NotificationManager.IMPORTANCE_HIGH)
                    .apply { enableVibration(true) }
            } else {
                NotificationChannel(CHANNEL_MESSAGES, T.party.channelMessages, NotificationManager.IMPORTANCE_DEFAULT)
            },
        )
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

    private const val CHANNEL_SOS = "party_sos"
    private const val CHANNEL_MESSAGES = "party_messages"
}
