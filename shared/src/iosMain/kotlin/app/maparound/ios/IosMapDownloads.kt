@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.ios

import app.maparound.data.CatalogEntry
import app.maparound.data.DownloadInfo
import app.maparound.data.MapDownloads
import app.maparound.data.MapNames
import app.maparound.data.mapBounds
import app.maparound.shared.fileSystem
import app.maparound.shared.net.userAgent
import app.maparound.ui.Fmt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okio.Path.Companion.toPath
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDownloadDelegateProtocol
import platform.Foundation.NSURLSessionDownloadTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.concurrent.Volatile

/**
 * Country maps downloaded with a background URLSession: iOS carries on while the app is in the
 * background or closed, and hands the file over the next time the app runs. Files are checked
 * and renamed to `.map` only when complete, so the map never opens a half-written file.
 */
class IosMapDownloads(private val maps: IosInstalledMaps) : MapDownloads {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** "… map installed" and failures, for snackbars. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** In-flight downloads by task id (the delegate updates them on the main queue). */
    @Volatile
    private var running: Map<Long, DownloadInfo> = emptyMap()
    private val tasks = HashMap<Long, NSURLSessionDownloadTask>()

    private val delegate = DownloadDelegate(
        onFinished = ::finished,
        onProgress = { task, done, total ->
            val name = task.taskDescription
            if (name != null) {
                val id = task.taskIdentifier.toLong()
                running = running + (id to info(id, name, done, total))
            }
        },
        onCompleted = ::completed,
    )
    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier("app.maparound.maps"),
        delegate,
        NSOperationQueue.mainQueue,
    )

    init {
        // Downloads that ran on while the app was closed.
        session.getAllTasksWithCompletionHandler { list ->
            list?.filterIsInstance<NSURLSessionDownloadTask>()?.forEach { task ->
                val name = task.taskDescription ?: return@forEach
                track(task, name)
            }
        }
    }

    override fun enqueue(entry: CatalogEntry, url: String, wifiOnly: Boolean): String? {
        val fileName = entry.localFileName
        if (running.values.any { it.fileName == fileName }) return "${entry.title} is already downloading"
        val needed = entry.sizeBytes ?: 0L
        val free = maps.freeBytes()
        if (needed > 0 && free < needed + (64L shl 20)) {
            return "Not enough storage: ${Fmt.fileSize(needed)} needed, ${Fmt.fileSize(free)} free"
        }
        val request = NSMutableURLRequest(uRL = NSURL(string = url))
        request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
        request.setAllowsCellularAccess(!wifiOnly)
        val task = session.downloadTaskWithRequest(request)
        task.taskDescription = fileName
        track(task, fileName)
        task.resume()
        return null
    }

    override fun cancel(id: Long) {
        tasks.remove(id)?.cancel()
        running = running - id
    }

    override fun poll(): List<DownloadInfo> = running.values.toList()

    private fun track(task: NSURLSessionDownloadTask, fileName: String) {
        val id = task.taskIdentifier.toLong()
        tasks[id] = task
        running = running + (id to info(id, fileName, task.countOfBytesReceived, task.countOfBytesExpectedToReceive))
    }

    private fun info(id: Long, fileName: String, done: Long, total: Long) =
        DownloadInfo(id, fileName, done, total.coerceAtLeast(0), if (done > 0) "Downloading" else "Queued")

    private fun finished(task: NSURLSessionTask, location: NSURL) {
        val id = task.taskIdentifier.toLong()
        val fileName = task.taskDescription ?: return
        val title = MapNames.title(fileName)
        val status = (task.response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        if (status !in 200..299) {
            _events.tryEmit("$title download failed (HTTP $status)")
            return
        }
        // The temporary file goes away when the delegate call returns: move it now.
        val part = "${maps.dir}/$fileName.part"
        val files = NSFileManager.defaultManager
        files.removeItemAtPath(part, null)
        if (!files.moveItemAtURL(location, NSURL.fileURLWithPath(part), null)) {
            _events.tryEmit("$title: could not store the download")
            return
        }
        if (mapBounds(part) == null) {
            fileSystem.delete(part.toPath(), mustExist = false)
            _events.tryEmit("$title: downloaded file is not a valid map")
            return
        }
        fileSystem.atomicMove(part.toPath(), "${maps.dir}/$fileName".toPath())
        maps.refresh()
        running = running - id
        _events.tryEmit("$title map installed")
    }

    private fun completed(task: NSURLSessionTask, error: NSError?) {
        val id = task.taskIdentifier.toLong()
        tasks.remove(id)
        running = running - id
        if (error != null && error.code != NSURLErrorCancelled) {
            val title = task.taskDescription?.let(MapNames::title) ?: "Map"
            _events.tryEmit("$title download failed: ${error.localizedDescription}")
        }
    }
}

/** URLSession's download callbacks as lambdas (Kotlin subclasses of NSObject are kept simple). */
private class DownloadDelegate(
    private val onFinished: (NSURLSessionTask, NSURL) -> Unit,
    private val onProgress: (NSURLSessionDownloadTask, Long, Long) -> Unit,
    private val onCompleted: (NSURLSessionTask, NSError?) -> Unit,
) : NSObject(), NSURLSessionDownloadDelegateProtocol {
    override fun URLSession(session: NSURLSession, downloadTask: NSURLSessionDownloadTask, didFinishDownloadingToURL: NSURL) {
        onFinished(downloadTask, didFinishDownloadingToURL)
    }

    override fun URLSession(
        session: NSURLSession,
        downloadTask: NSURLSessionDownloadTask,
        didWriteData: Long,
        totalBytesWritten: Long,
        totalBytesExpectedToWrite: Long,
    ) {
        onProgress(downloadTask, totalBytesWritten, totalBytesExpectedToWrite)
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        onCompleted(task, didCompleteWithError)
    }
}
