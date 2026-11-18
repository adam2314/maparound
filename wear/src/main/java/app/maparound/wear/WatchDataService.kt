package app.maparound.wear

import app.maparound.watch.WatchProtocol
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * The phone's state while the watch app isn't on screen: kept for the tile and shown on the watch
 * face (navigating, recording), as soon as the phone sends it.
 */
class WatchDataService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED) continue
            val bytes = DataMapItem.fromDataItem(e.dataItem).dataMap.getByteArray(WatchProtocol.STATE_KEY) ?: continue
            when (e.dataItem.uri.path) {
                WatchProtocol.STATE_PATH -> WatchProtocol.decode(bytes)?.let { WatchStore.update(this, it) }
                WatchProtocol.ROUTE_PATH -> WatchStore.updateRoute(this, WatchProtocol.decodeRoute(bytes))
            }
        }
    }
}
