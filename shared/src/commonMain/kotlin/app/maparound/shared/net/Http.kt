package app.maparound.shared.net

/** Sent with every request, e.g. "MapAround/1.4 (Android; offline OSM map viewer)". Set by the app at start. */
var userAgent: String = "MapAround (offline OSM map viewer)"

/** The server answered, but not with 2xx. */
class HttpException(val status: Int) : Exception("Server returned HTTP $status")

/**
 * A plain HTTP GET (Android: HttpURLConnection; iOS: NSURLSession). Blocking: call off the main
 * thread. Throws [HttpException] for other statuses than 2xx and an IOException when offline.
 */
expect fun httpGet(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): ByteArray

fun httpGetText(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): String =
    httpGet(url, headers, timeoutMs).decodeToString()

/**
 * Downloads [url] into the file [path] (streamed, replacing it). Blocking: call off the main
 * thread; stops soon after [isActive] turns false. Throws like [httpGet].
 */
expect fun httpDownload(url: String, path: String, timeoutMs: Int = 30_000, isActive: () -> Boolean = { true })

/**
 * [httpGet] without blocking anything: on a background thread on the phones, the browser's own
 * fetch on the web (which doesn't hold the page up). Throws like [httpGet].
 */
expect suspend fun httpGetAsync(url: String, timeoutMs: Int = 15_000): ByteArray
