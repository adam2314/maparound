package app.maparound.shared.net

import app.maparound.shared.toByteArray
import okio.IOException
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

actual fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray {
    val nsUrl = NSURL(string = url)
    val request = NSMutableURLRequest(uRL = nsUrl)
    request.timeoutInterval = timeoutMs / 1000.0
    request.setValue(userAgent, forHTTPHeaderField = "User-Agent")
    for ((k, v) in headers) request.setValue(v, forHTTPHeaderField = k)
    val done = dispatch_semaphore_create(0)
    var body: NSData? = null
    var status = 0
    var failure: String? = null
    NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
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
