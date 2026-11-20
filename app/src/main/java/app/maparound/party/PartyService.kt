package app.maparound.party

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.maparound.MainActivity
import app.maparound.R
import app.maparound.i18n.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps party sharing alive with the screen off: a location foreground service that feeds GPS
 * fixes to [PartySession] (as often as its mode needs) and keeps its connections running.
 */
class PartyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: LocationListener? = null
    private var network: ConnectivityManager.NetworkCallback? = null
    /** The fix interval asked for now (null: none). */
    private var asked: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started again before it stopped (a party left, another joined): connect the new one.
        if (listener != null) {
            PartySession.attach()
            return START_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(T.party.connecting),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        PartySession.attach()
        startLocation()
        watchNetwork()
        // The session ticks itself, when something is due; GPS as often as its mode needs (none when hidden).
        scope.launch { PartySession.fixEveryMs.collect(::askForFixes) }
        scope.launch {
            PartySession.state
                .map { s -> summary(s) }
                .distinctUntilChanged()
                .collect { text -> getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java)
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = PartySession.onLocation(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = l
        askForFixes(PartySession.fixEveryMs.value)
    }

    /**
     * Fixes every [everyMs] (no minimum distance: standing still, a fresh fix still says where I
     * am), or none.
     */
    @SuppressLint("MissingPermission")
    private fun askForFixes(everyMs: Long?) {
        val l = listener ?: return
        if (everyMs == asked) return
        asked = everyMs
        val lm = getSystemService(LocationManager::class.java)
        lm.removeUpdates(l)
        if (everyMs == null) return
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in lm.allProviders) runCatching { lm.requestLocationUpdates(p, everyMs, 0f, l, Looper.getMainLooper()) }
        }
    }

    /** The servers being waited for are tried as soon as the network is back. */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { PartySession.networkBack() }
            }
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) network = cb
    }

    override fun onDestroy() {
        network?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        network = null
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        PartySession.detach()
        super.onDestroy()
    }

    private fun summary(s: PartyState): String {
        val links = listOfNotNull(
            Via.ONLINE.label.takeIf { s.online.state == TransportStatus.State.CONNECTED },
            Via.LORA.label.takeIf { s.radio.state == TransportStatus.State.CONNECTED },
        ).ifEmpty { listOf(T.party.summaryConnecting) }.joinToString(" + ")
        val who = T.party.others(s.members.size)
        val sharing = if (s.sharing) "" else " · ${T.party.hidden}"
        return "$who · $links$sharing" + if (s.mySos) " · ${T.party.sosActiveCaps}" else ""
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Each time, so an existing channel takes the name in the app's language now (only that changes).
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, T.party.channelSharing, NotificationManager.IMPORTANCE_LOW).apply {
                description = T.party.channelSharingDescription
            },
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_party)
            .setContentTitle(T.party.notificationTitle(PartySession.state.value.party?.name ?: ""))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "party"
        private const val NOTIFICATION_ID = 43
    }
}
