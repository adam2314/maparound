package app.maparound.routing

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import androidx.core.content.edit
import app.maparound.USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mapsforge.core.model.BoundingBox
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * BRouter routing data: 5°×5° `.rd5` files from brouter.de, downloaded with the system
 * DownloadManager (they are 20–300 MB) into `files/brouter/segments4/`.
 */
class RoutingDataRepository(private val context: Context) {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val segmentsDir: File = File(base, "brouter/segments4").apply { mkdirs() }
    private val profilesDir: File = File(context.filesDir, "brouter/profiles2")
    private val sp = context.getSharedPreferences("routing_data", Context.MODE_PRIVATE)
    private val dm = context.getSystemService(DownloadManager::class.java)

    val offlineRouter: OfflineRouter by lazy {
        extractProfiles()
        OfflineRouter(segmentsDir, profilesDir)
    }

    data class Segment(val name: String, val bytes: Long)
    data class Download(val id: Long, val name: String, val done: Long, val total: Long) {
        val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
    }
    data class State(val installed: List<Segment> = emptyList(), val downloads: List<Download> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var remoteSizes: Map<String, Long>? = null

    init {
        refresh()
    }

    fun refresh() {
        val installed = segmentsDir.listFiles { f -> f.name.endsWith(".rd5") }.orEmpty()
            .map { Segment(it.name.removeSuffix(".rd5"), it.length()) }.sortedBy { it.name }
        _state.value = _state.value.copy(installed = installed)
    }

    fun isInstalled(name: String) = File(segmentsDir, "$name.rd5").isFile

    fun segmentsFor(bbox: BoundingBox): List<String> =
        Segments.namesFor(bbox.minLatitude, bbox.minLongitude, bbox.maxLatitude, bbox.maxLongitude)

    /** File sizes from the server's directory listing (cached). Missing names have no data (sea). */
    suspend fun remoteSizes(): Map<String, Long> = remoteSizes ?: withContext(Dispatchers.IO) {
        val conn = URL(SOURCE).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("""href="([EW]\d+_[NS]\d+)\.rd5">[^<]*</a>\s+\S+\s+\S+\s+(\d+)""").findAll(html)
                .associate { it.groupValues[1] to it.groupValues[2].toLong() }
        } finally {
            conn.disconnect()
        }
    }.also { remoteSizes = it }

    /** Queues downloads. Returns an error message, or null. */
    fun download(names: List<String>, wifiOnly: Boolean): String? {
        val pending = pendingIds()
        val todo = names.filterNot { isInstalled(it) || it in pending.values }
        if (todo.isEmpty()) return null
        for (name in todo) {
            File(segmentsDir, "$name.rd5.part").delete()
            val request = DownloadManager.Request(Uri.parse("$SOURCE$name.rd5"))
                .setTitle("Routing data $name")
                .setDescription("MapAround offline routing")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, "brouter", "segments4/$name.rd5.part")
                .setAllowedOverMetered(!wifiOnly)
                .setAllowedOverRoaming(false)
                .addRequestHeader("User-Agent", USER_AGENT)
            try {
                remember(dm.enqueue(request), name)
            } catch (e: Exception) {
                return "Could not start download: ${e.message}"
            }
        }
        return null
    }

    fun cancel(id: Long) {
        dm.remove(id)
        forget(id)
    }

    fun delete(name: String) {
        File(segmentsDir, "$name.rd5").delete()
        refresh()
    }

    /** Updates download progress; finishes completed ones. Call periodically while visible. */
    @Synchronized
    fun poll() {
        val list = ArrayList<Download>()
        for ((id, name) in pendingIds()) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c == null || !c.moveToFirst()) {
                    forget(id)
                    return@use
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED -> finish(id, name, status)
                    else -> list += Download(
                        id, name,
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                }
            }
        }
        _state.value = _state.value.copy(downloads = list)
    }

    /** Called from the shared DOWNLOAD_COMPLETE receiver; ignores ids that aren't ours. */
    @Synchronized
    fun onDownloadComplete(id: Long) {
        val name = pendingIds()[id] ?: return
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c != null && c.moveToFirst()) {
                finish(id, name, c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
            } else {
                forget(id)
            }
        }
    }

    private fun finish(id: Long, name: String, status: Int) {
        forget(id)
        val part = File(segmentsDir, "$name.rd5.part")
        if (status == DownloadManager.STATUS_SUCCESSFUL && part.length() > 1000 && part.renameTo(File(segmentsDir, "$name.rd5"))) {
            _events.tryEmit("Offline routing ready for $name")
        } else {
            part.delete()
            _events.tryEmit("Routing data $name failed to download")
        }
        dm.remove(id)
        refresh()
    }

    private fun pendingIds(): Map<Long, String> {
        val json = JSONObject(sp.getString(KEY, "{}")!!)
        return json.keys().asSequence().associate { it.toLong() to json.getString(it) }
    }

    private fun remember(id: Long, name: String) = sp.edit {
        putString(KEY, JSONObject(sp.getString(KEY, "{}")!!).put(id.toString(), name).toString())
    }

    private fun forget(id: Long) = sp.edit {
        val json = JSONObject(sp.getString(KEY, "{}")!!)
        json.remove(id.toString())
        putString(KEY, json.toString())
    }

    /** BRouter reads profiles from files; copy them out of the APK once per app version. */
    private fun extractProfiles() {
        val stamp = File(profilesDir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return
        profilesDir.mkdirs()
        val assets = context.assets
        for (name in assets.list("brouter/profiles2").orEmpty()) {
            assets.open("brouter/profiles2/$name").use { input ->
                File(profilesDir, name).outputStream().use { input.copyTo(it) }
            }
        }
        stamp.writeText(version)
    }

    fun format(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    companion object {
        const val SOURCE = "https://brouter.de/brouter/segments4/"
        private const val KEY = "pending"
    }
}
