package app.maparound.tracks

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.maparound.AppGraph
import app.maparound.MainActivity
import app.maparound.R
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.TrackStats
import app.maparound.sensors.bestAltitude
import app.maparound.ui.Fmt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileWriter
import java.util.Locale

/** Starts and stops the recording service (state in the shared [TrackRecorder]). */
object Recorder {
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }
}

/**
 * Foreground service that records GPS fixes while the screen is off. Every point is appended to
 * a journal file first, so a killed app still keeps the track (see TrackRepository).
 */
class RecordingService : Service() {
    private var listener: LocationListener? = null
    private var journal: FileWriter? = null
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> startRecording(resume = false)
            else -> startRecording(resume = true) // restarted by the system after the app was killed
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(resume: Boolean) {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Waiting for GPS…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // e.g. a restart in the background without location access: give up quietly
            stopSelf()
            return
        }
        val repo = AppGraph.tracks
        // A fresh start keeps any leftover of an older, cut-off recording as its own track.
        if (!resume) repo.recoverInterruptedRecording()
        val restored = if (resume) repo.readJournal() else emptyList()
        TrackRecorder.begin(restored.firstOrNull()?.time ?: System.currentTimeMillis(), restored)
        journal = FileWriter(repo.journalPath, true)

        val lm = getSystemService(LocationManager::class.java)
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = l
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
    }

    private fun onFix(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        // Skip poor fixes and jitter while standing still.
        if (accuracy != null && accuracy > MAX_ACCURACY_M) {
            TrackRecorder.accuracy(accuracy)
            return
        }
        val p = GeoPoint(location.latitude, location.longitude, location.bestAltitude(), location.time)
        val last = TrackRecorder.state.value.points.lastOrNull()
        if (last != null && Geo.distance(last, p) < MIN_STEP_M) {
            TrackRecorder.accuracy(accuracy)
            return
        }
        TrackRecorder.append(p, accuracy)
        journal?.apply {
            write(String.format(Locale.ROOT, "%.7f,%.7f,%s,%d\n", p.lat, p.lon, p.ele?.let { "%.1f".format(Locale.ROOT, it) } ?: "", p.time ?: 0L))
            flush()
        }
        val now = System.currentTimeMillis()
        if (now - lastNotified > 10_000) {
            lastNotified = now
            val s = TrackRecorder.state.value.stats
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("${Fmt.distance(s.distanceM.toFloat())} · ↑ ${s.ascentM?.toInt() ?: 0} m"))
        }
    }

    private fun stopRecording() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        journal?.close()
        journal = null
        val state = TrackRecorder.end()
        val repo = AppGraph.tracks
        if (state.points.size >= 2) {
            val start = state.startedAt ?: state.points.first().time ?: System.currentTimeMillis()
            repo.save(Track(TrackRepository.defaultName(start), TrackKind.RECORDED, state.points, start))
        }
        java.io.File(repo.journalPath).delete()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        journal?.close()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Track recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while MapAround records your track"
                },
            )
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle("Recording track")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop & save", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.maparound.record.START"
        const val ACTION_STOP = "app.maparound.record.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 42
        private const val MAX_ACCURACY_M = 40f
        private const val MIN_STEP_M = 3.0
    }
}
