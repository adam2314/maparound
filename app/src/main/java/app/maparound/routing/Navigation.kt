package app.maparound.routing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.maparound.MainActivity
import app.maparound.R
import app.maparound.i18n.I18n
import app.maparound.i18n.Language
import app.maparound.i18n.T
import app.maparound.sensors.LocationSource
import app.maparound.sensors.toGeoFix
import app.maparound.ui.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Says navigation instructions with the phone's text-to-speech engine, as navigation guidance
 * (music is ducked while it talks; in the car it plays through the car's speakers).
 */
class AndroidSpeaker(context: Context) : NavigationSession.Speaker {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var unavailable = false
    private var pending: String? = null
    private var count = 0

    /** The app's language when the voice was chosen. */
    private var voiceLanguage: Language? = null

    override fun speak(text: String) {
        if (unavailable) return
        if (tts == null) {
            ready = false
            pending = text
            // The listener may run inside the constructor; posting lets `tts` be set first.
            tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
            return
        }
        if (ready) say(text) else pending = text
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            unavailable = true // no speech engine on this phone
            stop()
            return
        }
        chooseVoice(t)
        t.setAudioAttributes(attributes)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post { if (tts?.isSpeaking != true) audio.abandonAudioFocusRequest(focus) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { audio.abandonAudioFocusRequest(focus) }
            }
        })
        ready = true
        pending?.let {
            pending = null
            say(it)
        }
    }

    /**
     * The words are in the app's language, not the phone's: a voice for it even on a phone set to
     * another language. Without one installed, an English voice as before.
     */
    private fun chooseVoice(t: TextToSpeech) {
        val language = I18n.language
        val device = Locale.getDefault()
        val own = when (language) {
            Language.EN -> emptyList<Locale>()
            // Android calls Hebrew "iw" as well as "he" (older versions only "iw").
            Language.HE -> listOf(Locale.forLanguageTag("he-IL"), Locale.forLanguageTag("iw-IL"), Locale.forLanguageTag("he"))
        }
        val voice = (listOfNotNull(device.takeIf { Language.of(it.language) == language }) + own + listOf(Locale.UK, Locale.US, Locale.ENGLISH))
            .firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.ENGLISH
        t.setLanguage(voice)
        voiceLanguage = language
    }

    private fun say(text: String) {
        tts?.let { if (voiceLanguage != I18n.language) chooseVoice(it) } // the app's language was changed
        audio.requestAudioFocus(focus)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav${count++}")
    }

    override fun stop() {
        pending = null
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ready = false
        audio.abandonAudioFocusRequest(focus)
    }
}

/**
 * Keeps navigation going with the screen off or the app in the background: GPS fixes go to
 * [NavigationSession] and the notification shows the next maneuver. Runs while navigation does.
 */
class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var shownTitle = ""
    private var shownAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavigationSession.stop() // the state collector below then stops the service
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        createChannel()
        try {
            // First thing, as startForegroundService requires (even when we stop again at once).
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(T.nav.navigating, T.nav.waitingForGps),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            // Not allowed now (no location access, or started from the background): the open
            // screens still feed navigation.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!NavigationSession.state.value.active) {
            finish()
            return START_NOT_STICKY
        }
        running = true
        scope.launch { LocationSource.updates(this@NavigationService).collect { NavigationSession.onFix(it.toGeoFix()) } }
        scope.launch {
            NavigationSession.state.collect { s ->
                if (!s.active) finish() else show(s)
            }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        running = false
        scope.coroutineContext.cancelChildren()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** The next maneuver in the notification: at once when it changes, otherwise every few seconds. */
    private fun show(s: NavigationSession.State) {
        val p = s.progress
        val m = p?.next
        val title = when {
            s.rerouting -> T.nav.recalculating
            p == null -> T.nav.navigating
            p.arrived -> T.nav.youHaveArrived
            p.offRoute -> T.nav.offRoute
            m != null -> Guidance.instruction(m)
            else -> T.nav.navigating
        }
        val now = System.currentTimeMillis()
        if (title == shownTitle && now - shownAt < 3000) return
        shownTitle = title
        shownAt = now
        val text = when {
            p == null -> T.nav.waitingForGps
            p.arrived -> ""
            else -> listOfNotNull(
                if (m != null && !p.offRoute) T.nav.inDistanceShort(Fmt.distance(Guidance.rounded(p.distanceToNextM).toFloat())) else null,
                T.nav.distanceLeft(Fmt.distance(p.remainingM.toFloat())),
                p.remainingS?.let { T.nav.arrivalInLine(Fmt.timeOfDay(now + it * 1000)) },
            ).joinToString(" · ")
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Also when it exists: that renames it, so its name follows the app's language.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, T.nav.channelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = T.nav.channelDescription
            },
        )
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_navigation)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, T.nav.stopNavigation, stop)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "app.maparound.navigation.STOP"
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 44

        /** Starts the service if Android allows it now (it does while the app or the car screen is in use). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException: the open screens feed navigation instead
            }
        }
    }
}
