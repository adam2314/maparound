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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.maparound.AppGraph
import app.maparound.MainActivity
import app.maparound.R
import app.maparound.i18n.T
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.sensors.bestAltitude
import app.maparound.share.Incoming
import app.maparound.ui.Fmt
import java.util.concurrent.Executors

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
 * Foreground service that records GPS fixes while the screen is off. Every point goes to a journal
 * file as it comes, forced onto the storage every half minute ([RecordingJournal]), so a killed app
 * or a phone that dies keeps the track (see TrackRepository).
 */
class RecordingService : Service() {
    private var listener: LocationListener? = null
    private var journal: RecordingJournal? = null
    private var lastNotified = 0L

    /** The journal's writes, its forcing onto the storage and the saving at Stop: in order, off the main thread. */
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Saving after Stop; a Record meanwhile starts when that's done. */
    private var stopping = false
    private var startAfterStop = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> if (stopping) startAfterStop = true else startRecording(resume = false)
            // Restarted by the system after the app was killed.
            else -> if (!stopping) startRecording(resume = true)
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
                this, NOTIFICATION_ID, notification(T.library.waitingForGps),
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
        journal = RecordingJournal(repo.journalPath)

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
        val j = journal
        // Skip poor fixes and jitter while standing still.
        if (accuracy != null && accuracy > MAX_ACCURACY_M) {
            TrackRecorder.accuracy(accuracy)
            // Standing still or waiting for the sky: the last points still go onto the storage in time.
            j?.let { io.execute { it.syncIfDue() } }
            return
        }
        val p = GeoPoint(location.latitude, location.longitude, location.bestAltitude(), location.time)
        val last = TrackRecorder.state.value.points.lastOrNull()
        if (last != null && Geo.distance(last, p) < MIN_STEP_M) {
            TrackRecorder.accuracy(accuracy)
            j?.let { io.execute { it.syncIfDue() } }
            return
        }
        TrackRecorder.append(p, accuracy)
        j?.let { io.execute { if (!it.append(p)) Log.w(TAG, "Recording journal not written") } }
        val now = System.currentTimeMillis()
        if (now - lastNotified > 10_000) {
            lastNotified = now
            val s = TrackRecorder.state.value.stats
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("${Fmt.distance(s.distanceM.toFloat())} · ↑ ${Fmt.height(s.ascentM ?: 0.0)}"))
        }
    }

    /**
     * Saves the track (off the main thread) and stops. The recording shows as going on until it's
     * saved, so the app's start-up recovery leaves the journal alone meanwhile.
     */
    private fun stopRecording() {
        if (stopping) return
        stopping = true
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        val j = journal
        journal = null
        val state = TrackRecorder.state.value
        val repo = AppGraph.tracks
        io.execute {
            j?.close()
            // Memory's points; after the app was killed (Stop from the notification or the watch), the journal's.
            val saved = repo.saveRecording(state.points, state.startedAt)
            saved.exceptionOrNull()?.let { Log.w(TAG, "Recording not saved", it) }
            main.post {
                TrackRecorder.end()
                if (saved.isFailure) Incoming.pending.value = Incoming.Item.Message(T.library.recordingNotSaved)
                stopping = false
                if (startAfterStop) {
                    startAfterStop = false
                    startRecording(resume = false)
                } else {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        journal?.let { j -> io.execute { j.close() } }
        io.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Created again each time: for an existing channel this only updates its name and
        // description, to the app's language now.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, T.library.recordingChannel, NotificationManager.IMPORTANCE_LOW).apply {
                description = T.library.recordingChannelDescription
            },
        )
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
            .setContentTitle(T.library.recordingTrack)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, T.library.stopAndSave, stop)
            .build()
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START = "app.maparound.record.START"
        const val ACTION_STOP = "app.maparound.record.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 42
        private const val MAX_ACCURACY_M = 40f
        private const val MIN_STEP_M = 3.0
    }
}
