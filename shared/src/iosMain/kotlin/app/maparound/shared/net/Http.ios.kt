@file:OptIn(ExperimentalForeignApi::class)

package app.maparound.shared.net

import app.maparound.shared.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import okio.IOException
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.downloadTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

private fun request(url: String, timeoutMs: Int, headers: Map<String, String> = emptyMap()): NSMutableURLRequest {
    val request = NSMutableURLRequest(uRL = NSURL(string = url))
    request.setTimeoutInterval(timeoutMs / 1000.0)
    request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
    for ((k, v) in headers) request.setValue(v, forHTTPHeaderField = k)
    return request
}

actual fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray {
    val done = dispatch_semaphore_create(0)
    var body: NSData? = null
    var status = 0
    var failure: String? = null
    NSURLSession.sharedSession.dataTaskWithRequest(request(url, timeoutMs, headers)) { data, response, error ->
        body = data
        status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        failure = error?.localizedDescription
        dispatch_semaphore_signal(done)
    }.resume()
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER)
    failure?.let { throw IOException(it) }
    if (status !in 200..299) throw HttpException(status)
    return body?.toByteArray() ?: ByteArray(0)
}

actual fun httpDownload(url: String, path: String, timeoutMs: Int, isActive: () -> Boolean) {
    val done = dispatch_semaphore_create(0)
    var status = 0
    var failure: String? = null
    val task = NSURLSession.sharedSession.downloadTaskWithRequest(request(url, timeoutMs)) { location, response, error ->
        status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        failure = error?.localizedDescription
        if (error == null && location != null && status in 200..299) {
            // The temporary file goes away when this returns: move it now.
            val files = NSFileManager.defaultManager
            files.removeItemAtPath(path, null)
            if (!files.moveItemAtURL(location, NSURL.fileURLWithPath(path), null)) failure = "Could not store the download"
        }
        dispatch_semaphore_signal(done)
    }
    task.resume()
    // Wake up four times a second to stop the download when it's no longer wanted.
    while (dispatch_semaphore_wait(done, dispatch_time(0uL, 250_000_000L)) != 0L) {
        if (!isActive()) task.cancel()
    }
    failure?.let { throw IOException(it) }
    if (status !in 200..299) throw HttpException(status)
}
