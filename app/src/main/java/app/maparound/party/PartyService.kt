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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps party sharing alive with the screen off: a location foreground service that feeds GPS
 * fixes to [PartySession] and keeps its transports (MQTT, Bluetooth radio) running.
 */
class PartyService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: LocationListener? = null
    private val ticker = object : Runnable {
        override fun run() {
            PartySession.tick()
            main.postDelayed(this, 15_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (listener != null) return START_STICKY
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Connecting…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        PartySession.attach()
        startLocation()
        main.postDelayed(ticker, 5_000)
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
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (p in lm.allProviders) runCatching { lm.requestLocationUpdates(p, 2_000L, 3f, l, Looper.getMainLooper()) }
        }
    }

    override fun onDestroy() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        PartySession.detach()
        super.onDestroy()
    }

    private fun summary(s: PartyState): String {
        val links = listOfNotNull(
            "online".takeIf { s.online.state == TransportStatus.State.CONNECTED },
            "radio".takeIf { s.radio.state == TransportStatus.State.CONNECTED },
        ).ifEmpty { listOf("connecting…") }.joinToString(" + ")
        val who = "${s.members.size} other" + if (s.members.size == 1) "" else "s"
        val sharing = if (s.sharing) "" else " · hidden"
        return "$who · $links$sharing" + if (s.mySos) " · SOS ACTIVE" else ""
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Party location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while MapAround shares your location with your party"
                },
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_party)
            .setContentTitle("Party: ${PartySession.state.value.party?.name ?: ""}")
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
