package app.maparound.data

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.text.format.Formatter
import app.maparound.AppGraph
import app.maparound.USER_AGENT
import app.maparound.i18n.T
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.io.File

/**
 * Downloads country maps with the system DownloadManager (survives app exit, resumes, shows a
 * notification). Files land as `<name>.map.part` and are renamed to `.map` once complete and
 * verified, so a half-written file is never opened by the map renderer.
 */
class MapDownloader(
    private val context: Context,
    private val repo: MapFilesRepository,
) : MapDownloads {
    private val prefs = DownloadIds(context)
    private val dm = context.getSystemService(DownloadManager::class.java)

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Human-readable "installed" / "failed" messages for snackbars. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Returns an error message, or null if the download was queued. */
    override fun enqueue(entry: CatalogEntry, url: String, wifiOnly: Boolean): String? {
        val fileName = entry.localFileName
        if (prefs.downloads().containsValue(fileName)) return T.offline.alreadyDownloading(entry.title)
        val needed = entry.sizeBytes ?: 0L
        val free = repo.dir.usableSpace
        if (needed > 0 && free < needed + (64L shl 20)) {
            return T.offline.notEnoughStorage(
                Formatter.formatShortFileSize(context, needed),
                Formatter.formatShortFileSize(context, free),
            )
        }
        File(repo.dir, "$fileName.part").delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(T.offline.mapDownloadTitle(entry.title))
            .setDescription(T.offline.mapDownloadDescription)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, "maps", "$fileName.part")
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
            .addRequestHeader("User-Agent", USER_AGENT)
        return try {
            val id = dm.enqueue(request)
            prefs.putDownload(id, fileName)
            null
        } catch (e: Exception) {
            T.offline.couldNotStartDownload(e.message)
        }
    }

    override fun cancel(id: Long) {
        dm.remove(id)
        prefs.removeDownload(id)
    }

    /** Current state of all in-flight downloads. Also finalizes any that have finished. */
    @Synchronized
    override fun poll(): List<DownloadInfo> {
        val result = mutableListOf<DownloadInfo>()
        for ((id, fileName) in prefs.downloads()) {
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c == null || !c.moveToFirst()) {
                    prefs.removeDownload(id) // cancelled from the notification shade
                    return@use
                }
                val status = c.int(DownloadManager.COLUMN_STATUS)
                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    finish(id, fileName, c)
                } else {
                    result += DownloadInfo(
                        id = id,
                        fileName = fileName,
                        downloadedBytes = c.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        totalBytes = c.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        statusText = statusText(status, c.int(DownloadManager.COLUMN_REASON)),
                    )
                }
            }
        }
        return result
    }

    private fun statusText(status: Int, reason: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> T.offline.queued
        DownloadManager.STATUS_PAUSED -> when (reason) {
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> T.offline.waitingForWifi
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> T.offline.waitingForNetwork
            DownloadManager.PAUSED_WAITING_TO_RETRY -> T.offline.retryingSoon
            else -> T.offline.paused
        }
        else -> T.offline.downloading
    }

    @Synchronized
    fun onDownloadComplete(id: Long) {
        val fileName = prefs.downloads()[id] ?: return
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (c != null && c.moveToFirst()) finish(id, fileName, c) else prefs.removeDownload(id)
        }
    }

    private fun finish(id: Long, fileName: String, c: Cursor) {
        val title = MapNames.title(fileName)
        val part = c.string(DownloadManager.COLUMN_LOCAL_URI)?.let { Uri.parse(it).path }?.let(::File)
            ?: File(repo.dir, "$fileName.part")
        prefs.removeDownload(id)
        if (c.int(DownloadManager.COLUMN_STATUS) == DownloadManager.STATUS_SUCCESSFUL) {
            val target = File(repo.dir, fileName)
            val ok = part.exists() && isValidMapFile(part) && part.renameTo(target)
            if (ok) {
                dm.remove(id) // drop the system record; the file has already been moved away
                repo.refresh()
                _events.tryEmit(T.offline.mapInstalled(title))
            } else {
                part.delete()
                dm.remove(id)
                _events.tryEmit(T.offline.notValidMap(title))
            }
        } else {
            part.delete()
            dm.remove(id)
            _events.tryEmit(T.offline.downloadFailedCode(title, c.int(DownloadManager.COLUMN_REASON)))
        }
    }

    private fun Cursor.int(col: String) = getInt(getColumnIndexOrThrow(col))
    private fun Cursor.long(col: String) = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.string(col: String): String? = getString(getColumnIndexOrThrow(col))
}

/** DownloadManager delivers this broadcast explicitly to our package, so a manifest receiver works. */
class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        // Both downloaders share the broadcast; each ignores ids that aren't its own.
        AppGraph.downloader.onDownloadComplete(id)
        AppGraph.routing.onDownloadComplete(id)
    }
}

/**
 * DownloadManager id -> target local file name, for downloads still in flight. Kept where the
 * settings always kept it (preference file "maparound", key "downloads"), so downloads that were
 * running during an app update are still recognised.
 */
private class DownloadIds(context: Context) {
    private val sp = context.getSharedPreferences("maparound", Context.MODE_PRIVATE)

    @Synchronized
    fun downloads(): Map<Long, String> {
        val json = JSONObject(sp.getString(KEY, "{}")!!)
        return json.keys().asSequence().associate { it.toLong() to json.getString(it) }
    }

    @Synchronized
    fun putDownload(id: Long, fileName: String) = write(downloads() + (id to fileName))

    @Synchronized
    fun removeDownload(id: Long) = write(downloads() - id)

    private fun write(map: Map<Long, String>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k.toString(), v) }
        sp.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "downloads"
    }
}
