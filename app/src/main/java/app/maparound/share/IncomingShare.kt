package app.maparound.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.maparound.AppGraph
import app.maparound.USER_AGENT
import app.maparound.party.PartyKey
import app.maparound.party.PartySession
import app.maparound.tracks.Gpx
import app.maparound.tracks.SavedTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Everything other apps send to MapAround: geo: links, map links (Google, OSM, Apple, BRouter…),
 * shared text with coordinates, GPX files and mapsforge .map files. The result waits in [pending]
 * until the UI shows it.
 */
object IncomingShare {
    sealed class Item {
        data class Point(val point: Shared.Point) : Item()
        data class Route(val route: Shared.Route) : Item()
        data class Track(val saved: SavedTrack, val placesAdded: Int = 0) : Item()
        /** Waypoints from a GPX file, added to the saved places. */
        data class Places(val added: Int, val points: List<app.maparound.routing.GeoPoint>) : Item()
        data class Message(val text: String) : Item()
    }

    private val _pending = MutableStateFlow<Item?>(null)
    val pending: StateFlow<Item?> = _pending.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun consume() {
        _pending.value = null
    }

    /** Returns true if the intent carried something for us. */
    fun handle(context: Context, intent: Intent?): Boolean {
        intent ?: return false
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return false
                return when (data.scheme?.lowercase()) {
                    "content", "file" -> { importFile(app, data, intent.type); true }
                    else -> { handleText(app, data.toString()); true }
                }
            }
            Intent.ACTION_SEND -> {
                val stream = stream(intent)
                if (stream != null) {
                    importFile(app, stream, intent.type)
                    return true
                }
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                    .joinToString("\n").ifBlank { return false }
                handleText(app, text)
                return true
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                streams?.forEach { importFile(app, it, intent.type) }
                return !streams.isNullOrEmpty()
            }
        }
        return false
    }

    /** Text or a link: a party invite, a route, a place, or a short link to resolve first. */
    fun handleText(context: Context, text: String) {
        if (text.contains("maparound://party/")) {
            PartyKey.parse(text.substring(text.indexOf("maparound://party/")).substringBefore(' ').substringBefore('\n'))
                ?.let { PartySession.pendingInvite.value = it; return }
        }
        SharedLinks.parse(text)?.let { deliver(it); return }
        // Grid references and codes (UTM, MGRS, ITM, Plus Code, Maidenhead, deg-min-sec…) sent on their own.
        if (text.trim().length <= 60) {
            val near = AppGraph.prefs.lastPosition()?.let { app.maparound.routing.GeoPoint(it.lat, it.lon) }
            app.maparound.tools.Coords.parse(text, reference = near)?.let {
                deliver(Shared.Point(it.point.lat, it.point.lon, null, null))
                return
            }
        }
        val short = SharedLinks.shortLink(text)
        if (short == null) {
            _pending.value = Item.Message("No location found in what was shared")
            return
        }
        _pending.value = Item.Message("Opening the shared link…")
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { runCatching { resolve(short) }.getOrNull() }
            // Keep a label from the original text (e.g. the place name above the link).
            val label = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.contains("://") && it.length <= 60 }
            val shared = resolved?.let { SharedLinks.parse(it) }?.let { s ->
                if (s is Shared.Point && s.label == null && label != null) s.copy(label = label) else s
            }
            if (shared != null) deliver(shared)
            else _pending.value = Item.Message(
                if (resolved == null) "Couldn't open the link (no internet?)" else "That link has an address but no coordinates",
            )
        }
    }

    private fun deliver(shared: Shared) {
        _pending.value = when (shared) {
            is Shared.Point -> Item.Point(shared)
            is Shared.Route -> Item.Route(shared)
        }
    }

    /**
     * GPX: tracks/routes go to the library and are shown, waypoints are added to the saved places
     * (a single waypoint is just shown as a pin); .map: offline map.
     */
    private fun importFile(context: Context, uri: Uri, mime: String?) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { displayName(context, uri) }
            if (name?.endsWith(".map", ignoreCase = true) == true) {
                _pending.value = Item.Message("Importing map ${name}…")
                val r = AppGraph.mapFiles.import(uri)
                _pending.value = Item.Message(r.fold({ "Offline map ${it.title} added" }, { "Couldn't add the map: ${it.message}" }))
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't open the file")
                    val content = Gpx.parse(bytes)
                    val base = name?.substringBeforeLast('.') ?: "Imported"
                    val hasLine = content.trackPoints.isNotEmpty() || content.routePoints.isNotEmpty()
                    val waypoints = if (hasLine || content.waypoints.size >= 2) app.maparound.places.Places.readGpx(bytes) else emptyList()
                    when {
                        hasLine -> Item.Track(
                            AppGraph.tracks.save(Gpx.toTrack(content, base, System.currentTimeMillis())),
                            placesAdded = AppGraph.places.addAll(waypoints),
                        )
                        waypoints.isNotEmpty() -> Item.Places(AppGraph.places.addAll(waypoints), waypoints.map { it.point })
                        else -> {
                            val w = content.waypoints.firstOrNull() ?: error("No track, route or waypoint in this GPX file")
                            Item.Point(Shared.Point(w.lat, w.lon, w.name ?: base, null))
                        }
                    }
                }
            }
            _pending.value = result.getOrElse {
                Item.Message(if (mime?.contains("gpx") == true || name?.endsWith(".gpx", true) == true) "Couldn't read the GPX: ${it.message}" else "That file isn't a GPX file")
            }
        }
    }

    /** Follows redirects by hand (Google short links), unwrapping consent pages. */
    private fun resolve(start: String): String? {
        var url = start
        repeat(8) {
            if (SharedLinks.parse(url) != null) return url
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code !in 300..399 || location == null) return url
                url = URL(URL(url), location).toString()
                if ("consent.google." in url) {
                    Regex("""[?&]continue=([^&]+)""").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                }
            } finally {
                conn.disconnect()
            }
        }
        return url
    }

    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
