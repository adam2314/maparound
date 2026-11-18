package app.maparound.watch

import android.app.Application
import android.os.Handler
import android.os.Looper
import app.maparound.routing.NavigationSession
import app.maparound.tracks.Recorder
import app.maparound.tracks.TrackRecorder
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Keeps a paired Wear OS watch up to date: navigation and recording, as the watch shows them
 * ([WatchProtocol.stateOf]), in a data item Google's watch link keeps in sync. Sent when what the
 * watch shows changes, at most once a second (urgently while navigating: turns can't wait). Nothing
 * at all without Google Play services; with no watch paired, the item just waits for one.
 */
object PhoneWatchLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false

    fun start(app: Application) {
        if (started) return
        started = true
        val available = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) }.getOrNull()
        if (available != ConnectionResult.SUCCESS) return
        val data = Wearable.getDataClient(app)
        scope.launch {
            combine(NavigationSession.state, TrackRecorder.state) { nav, rec -> WatchProtocol.stateOf(nav, rec, System.currentTimeMillis()) }
                .distinctUntilChanged()
                .conflate()
                .collect { s ->
                    publish(data, s)
                    delay(SEND_GAP_MS)
                }
        }
        // The route once a route (a new one after recalculating), for the watch's route view.
        scope.launch {
            NavigationSession.state
                // Thinned again only for a new route, not at every fix.
                .distinctUntilChanged { a, b -> a.active == b.active && a.route === b.route && a.maneuvers === b.maneuvers }
                .map { WatchProtocol.routeOf(it) }
                .distinctUntilChanged()
                .collect { r -> put(data, WatchProtocol.ROUTE_PATH, WatchProtocol.encodeRoute(r), urgent = r != null) }
        }
    }

    private suspend fun publish(data: DataClient, s: WatchState) =
        put(data, WatchProtocol.STATE_PATH, WatchProtocol.encode(s), urgent = s.nav != null)

    private suspend fun put(data: DataClient, path: String, bytes: ByteArray, urgent: Boolean) {
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putByteArray(WatchProtocol.STATE_KEY, bytes)
        }.asPutDataRequest()
        if (urgent) request.setUrgent()
        try {
            data.putDataItem(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The watch link isn't there now: the next change tries again.
        }
    }

    private const val SEND_GAP_MS = 1_000L
}

/**
 * Does what the watch asks: stop navigating, start or stop recording. (Starting a recording from
 * the watch needs MapAround to be allowed to start it now: Android lets an app start location
 * work from the background only in some cases; stopping always works.)
 */
class PhoneWatchService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchProtocol.COMMAND_PATH) return
        val command = WatchProtocol.command(event.data) ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                when (command) {
                    WatchCommand.STOP_NAVIGATION -> NavigationSession.stop()
                    WatchCommand.START_RECORDING -> if (!TrackRecorder.state.value.recording) Recorder.start(this)
                    WatchCommand.STOP_RECORDING -> if (TrackRecorder.state.value.recording) Recorder.stop(this)
                }
            } catch (e: Exception) {
                // Not allowed from the background now (Android's limits on starting location work).
            }
        }
    }
}
