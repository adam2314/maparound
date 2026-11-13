package app.maparound.shared.net

import java.net.HttpURLConnection
import java.net.URL

actual fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val status = conn.responseCode
        if (status !in 200..299) throw HttpException(status)
        return conn.inputStream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

actual fun httpDownload(url: String, path: String, timeoutMs: Int, isActive: () -> Boolean) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("User-Agent", userAgent)
        val status = conn.responseCode
        if (status !in 200..299) throw HttpException(status)
        conn.inputStream.buffered(1 shl 16).use { input ->
            java.io.File(path).outputStream().buffered(1 shl 16).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (!isActive()) throw java.io.IOException("Stopped")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    } finally {
        conn.disconnect()
    }
}

actual suspend fun httpGetAsync(url: String, timeoutMs: Int): ByteArray =
    kotlinx.coroutines.withContext(app.maparound.shared.ioDispatcher) { httpGet(url, emptyMap(), timeoutMs) }
