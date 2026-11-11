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
