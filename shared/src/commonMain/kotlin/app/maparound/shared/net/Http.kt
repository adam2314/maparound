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
