package app.maparound.data

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.maparound.AppGraph
import app.maparound.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps terrain and trail downloads going while MapAround is in the background or the screen is
 * off. The downloads themselves run in [TerrainStore] and [TrailRepository]; this foreground
 * service only keeps the app alive (and the CPU awake) while one of them runs, with their
 * progress in its notification, and says how they ended if the app isn't open by then.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var cancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    /** "Contours & hill shading ready for …" and the like, from the downloads that ended meanwhile. */
    private val messages = ArrayList<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            AppGraph.terrain.cancel()
            AppGraph.trails.cancel()
            // At once: a download waiting for a server's answer only ends when it comes.
            if (running) finish() else stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        createChannel()
        try {
            // First thing, as startForegroundService requires (even when we stop again at once).
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, progress(downloads()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            stopSelf() // not allowed now: the downloads go on while the app is open, as before
            return START_NOT_STICKY
        }
        running = true
        cancelled = false
        messages.clear()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MapAround:downloads")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
        scope.launch { merge(AppGraph.terrain.events, AppGraph.trails.events).collect { messages += it } }
        scope.launch {
            combine(AppGraph.terrain.state, AppGraph.trails.state) { _, _ -> downloads() }
                .conflate()
                .collect { list ->
                    if (list.isEmpty()) {
                        finish()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, progress(list))
                        delay(1000) // Android drops notifications updated too often
                    }
                }
        }
        return START_NOT_STICKY
    }

    /** Android 15 limits data-sync services to 6 hours a day: past that, the downloads go on only while the app is open. */
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        if (!running) return
        running = false
        scope.coroutineContext.cancelChildren()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // In the app, the same messages show as snackbars.
        if (messages.isNotEmpty() && !cancelled && !appVisible()) done(messages.toList())
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** The downloads running now, by what they fetch. */
    private fun downloads(): List<Pair<String, DownloadProgress>> = listOfNotNull(
        AppGraph.terrain.state.value.progress?.let { "Terrain" to it },
        AppGraph.trails.state.value.progress?.let { "Trails" to it },
    )

    private fun progress(list: List<Pair<String, DownloadProgress>>): Notification {
        val title = when (list.size) {
            0 -> "Downloading"
            1 -> "${list[0].first} for ${list[0].second.label}"
            else -> list.joinToString(" and ") { it.first.lowercase() }.replaceFirstChar { it.uppercase() }
        }
        val text = list.joinToString(" · ") { (what, p) ->
            val areas = "${p.done} of ${p.total} area${if (p.total == 1) "" else "s"}"
            if (list.size == 1) areas else "$what: $areas"
        }
        val done = list.sumOf { it.second.done }
        val total = list.sumOf { it.second.total }
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun done(lines: List<String>) {
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (lines.size == 1) lines[0] else "Downloads finished")
            .apply { if (lines.size > 1) setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))) }
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Terrain and trail downloads in progress"
                },
            )
        }
    }

    companion object {
        private const val ACTION_CANCEL = "app.maparound.downloads.CANCEL"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 46
        private const val DONE_NOTIFICATION_ID = 47
        /** A safety limit: the lock is released as soon as the downloads end. */
        private const val MAX_WAKE_MS = 3 * 60 * 60 * 1000L

        /** Starts the service if Android allows it now (it does while the app is in use). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException: the downloads go on while the app is open
            }
        }
    }
}
