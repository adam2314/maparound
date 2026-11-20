package app.maparound.party

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The party's connections to MQTT servers on Android: MQTT over secure WebSockets (subprotocol
 * "mqtt"), as the web app reaches them, through OkHttp. The callbacks come on the main thread,
 * after [open] returns.
 */
object OkHttpMqttSockets : MqttSockets {
    private val main = Handler(Looper.getMainLooper())

    // MQTT keeps the connection alive itself (PINGREQ); OkHttp's own pings stay off.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    override fun open(url: String, onOpen: () -> Unit, onData: (ByteArray) -> Unit, onClose: (String) -> Unit): MqttSocket? {
        val request = runCatching { Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build() }.getOrNull() ?: return null
        var closed = false
        fun closeOnce(why: String) {
            main.post {
                if (closed) return@post
                closed = true
                onClose(why)
            }
        }
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (!closed) onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                main.post { if (!closed) onData(data) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closeOnce(reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closeOnce(reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closeOnce(t.message ?: t.javaClass.simpleName)
        })
        return object : MqttSocket {
            override fun send(bytes: ByteArray) {
                ws.send(bytes.toByteString())
            }

            override fun close() {
                closed = true
                ws.cancel()
            }
        }
    }
}
