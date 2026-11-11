package app.maparound.share

import android.content.Context
import app.maparound.share.Incoming.Item
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import app.maparound.AppGraph
import app.maparound.USER_AGENT
import app.maparound.party.PartyKey
import app.maparound.party.PartySession
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        // Map links, and grid references or codes (UTM, MGRS, ITM, Plus Code, deg-min-sec…) sent on their own.
        val near = AppGraph.prefs.lastPosition()?.let { app.maparound.routing.GeoPoint(it.lat, it.lon) }
        Incoming.fromText(text, near)?.let { Incoming.pending.value = it; return }
        val short = SharedLinks.shortLink(text)
        if (short == null) {
            Incoming.pending.value = Item.Message("No location found in what was shared")
            return
        }
        Incoming.pending.value = Item.Message("Opening the shared link…")
        scope.launch {
            val resolved = withContext(Dispatchers.IO) { runCatching { resolve(short) }.getOrNull() }
            // Keep a label from the original text (e.g. the place name above the link).
            val label = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.contains("://") && it.length <= 60 }
            val shared = resolved?.let { SharedLinks.parse(it) }?.let { s ->
                if (s is Shared.Point && s.label == null && label != null) s.copy(label = label) else s
            }
            if (shared != null) Incoming.pending.value = Incoming.of(shared)
            else Incoming.pending.value = Item.Message(
                if (resolved == null) "Couldn't open the link (no internet?)" else "That link has an address but no coordinates",
            )
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
                Incoming.pending.value = Item.Message("Importing map ${name}…")
                val r = AppGraph.mapFiles.import(uri)
                Incoming.pending.value = Item.Message(r.fold({ "Offline map ${it.title} added" }, { "Couldn't add the map: ${it.message}" }))
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Can't open the file")
                    Incoming.importGpx(bytes, name?.substringBeforeLast('.') ?: "Imported", AppGraph.tracks, AppGraph.places)
                }
            }
            Incoming.pending.value = result.getOrElse {
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
