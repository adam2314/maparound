package app.maparound.wear

import android.content.Context
import androidx.wear.tiles.TileService
import app.maparound.watch.WatchProtocol
import app.maparound.watch.WatchRoute
import app.maparound.watch.WatchState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's latest state on the watch, for the app's screen, the tile and the watch face's
 * display (Ongoing Activity). Kept across restarts, so the tile has something to show at once.
 */
object WatchStore {
    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state.asStateFlow()

    /** The route being navigated, for the route view. */
    private val _route = MutableStateFlow<WatchRoute?>(null)
    val route: StateFlow<WatchRoute?> = _route.asStateFlow()
    private var loaded = false

    /** The state now, read from storage the first time. */
    fun current(context: Context): WatchState? {
        if (!loaded) {
            loaded = true
            prefs(context).getString(KEY, null)?.let { _state.value = WatchProtocol.decode(it.encodeToByteArray()) }
            prefs(context).getString(ROUTE, null)?.let { _route.value = WatchProtocol.decodeRoute(it.encodeToByteArray()) }
        }
        return _state.value
    }

    /** A new state from the phone (or a sample): shown everywhere it's shown. */
    fun update(context: Context, s: WatchState?) {
        current(context)
        if (s == _state.value) return
        _state.value = s
        prefs(context).edit().apply {
            if (s == null) remove(KEY) else putString(KEY, WatchProtocol.encode(s).decodeToString())
        }.apply()
        WatchOngoing.show(context, s)
        TileService.getUpdater(context).requestUpdate(MapAroundTile::class.java)
    }

    /** A new route from the phone (null: none). */
    fun updateRoute(context: Context, r: WatchRoute?) {
        current(context)
        if (r == _route.value) return
        _route.value = r
        prefs(context).edit().apply {
            if (r == null) remove(ROUTE) else putString(ROUTE, WatchProtocol.encodeRoute(r).decodeToString())
        }.apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("watch", Context.MODE_PRIVATE)

    private const val KEY = "state"
    private const val ROUTE = "route"
}
