package app.maparound.wear

import android.content.Context
import android.net.Uri
import app.maparound.watch.WatchCommand
import app.maparound.watch.WatchProtocol
import app.maparound.watch.WatchState
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * The watch's side of Google's watch link: the phone's state ([WatchProtocol.STATE_PATH], kept in
 * sync while a screen listens) and the commands sent back to the phone app.
 */
class PhoneLink(context: Context) : DataClient.OnDataChangedListener {
    private val app = context.applicationContext
    private val data = Wearable.getDataClient(app)

    private val _state = MutableStateFlow<WatchState?>(null)

    /** What the phone says; null until it has said anything (no phone app, or not yet). */
    val state: StateFlow<WatchState?> = _state.asStateFlow()

    /** Listens for changes, after reading what's there now. */
    suspend fun listen() {
        data.addListener(this)
        try {
            val items = data.getDataItems(Uri.parse("wear://*${WatchProtocol.STATE_PATH}")).await()
            try {
                items.lastOrNull()?.let(::read)
            } finally {
                items.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No watch link (yet): changes still come to the listener.
        }
    }

    fun stop() {
        data.removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == WatchProtocol.STATE_PATH) read(e.dataItem)
        }
    }

    private fun read(item: DataItem) {
        val bytes = DataMapItem.fromDataItem(item).dataMap.getByteArray(WatchProtocol.STATE_KEY) ?: return
        WatchProtocol.decode(bytes)?.let { _state.value = it }
    }

    /** Sends [command] to the phone app; false when no phone with it is in reach. */
    suspend fun send(command: WatchCommand): Boolean = try {
        val nodes = Wearable.getCapabilityClient(app)
            .getCapability(WatchProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        val messages = Wearable.getMessageClient(app)
        for (node in nodes) messages.sendMessage(node.id, WatchProtocol.COMMAND_PATH, WatchProtocol.encode(command)).await()
        nodes.isNotEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
}
