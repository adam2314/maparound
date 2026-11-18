package app.maparound.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import app.maparound.i18n.I18n
import app.maparound.i18n.T
import app.maparound.watch.WatchState

/**
 * While the phone navigates or records, an Ongoing Activity on the watch: its icon on the watch
 * face and at the top of the app list, with a line of status (the next turn and its distance; the
 * recording's time, ticking, and distance); a tap opens the app. Silent: the turn alerts buzz.
 */
object WatchOngoing {
    private const val CHANNEL = "ongoing"
    private const val ID = 1

    fun show(context: Context, s: WatchState?) {
        val nm = NotificationManagerCompat.from(context)
        if (s == null || (s.nav == null && s.recording == null)) {
            nm.cancel(ID)
            return
        }
        val nav = s.nav
        val rec = s.recording
        I18n.apply(s.language)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, T.nav.channelName, NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, WearActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = if (nav != null) R.drawable.ic_stat_navigation else R.drawable.ic_stat_record
        val text = if (nav != null) listOfNotNull(nav.distance, nav.title).joinToString(" · ") else rec!!.distance
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(if (nav != null) nav.title else T.watch.recording)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(if (nav != null) NotificationCompat.CATEGORY_NAVIGATION else NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        val status = if (nav != null) {
            Status.Builder().addTemplate(text).build()
        } else {
            // The recording's time ticks on the watch face by itself (counted from its start).
            val started = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - rec!!.startedAt)
            Status.Builder()
                .addTemplate("#time# · #distance#")
                .addPart("time", Status.StopwatchPart(started))
                .addPart("distance", Status.TextPart(rec.distance))
                .build()
        }
        OngoingActivity.Builder(context, ID, builder)
            .setStaticIcon(icon)
            .setTouchIntent(open)
            .setStatus(status)
            .build()
            .apply(context)
        try {
            nm.notify(ID, builder.build())
        } catch (e: SecurityException) {
            // Notifications not allowed on the watch: the app and the tile still show it.
        }
    }
}
