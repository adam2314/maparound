package app.maparound.party

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** State of one transport, for the UI. */

/**
 * Party packets over MQTT (TLS). Topics: `maparound/v1/<party tag>/<sender>/<kind>`.
 * Position, hello, SOS and meeting point are published *retained*, so whoever (re)joins gets
 * everyone's latest state at once. Payloads are end-to-end encrypted; the broker only relays.
 *
 * Plain Java networking (Eclipse Paho), no Android APIs, so it is also tested on the desktop.
 */
class MqttTransport(
    private val tagHex: String,
    private val brokers: List<String> = PUBLIC_BROKERS,
    private val onPacket: (ByteArray) -> Unit,
    private val onStatus: (TransportStatus) -> Unit,
) {
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "party-mqtt").apply { isDaemon = true } }
    @Volatile private var client: MqttClient? = null
    @Volatile private var stopped = false
    private var brokerIndex = 0

    private val base get() = "$ROOT/$tagHex"

    fun start() {
        stopped = false
        io.execute { connect() }
    }

    fun stop() {
        stopped = true
        io.execute {
            runCatching { client?.disconnect(2_000) }
            runCatching { client?.close() }
            client = null
        }
        io.shutdown()
        onStatus(TransportStatus(TransportStatus.State.OFF))
    }

    val isConnected: Boolean get() = client?.isConnected == true

    /** [kind] is part of the topic: pos, hello, sos, meet (retained) or msg (not retained). */
    fun publish(senderHex: String, kind: String, payload: ByteArray, retained: Boolean) {
        io.execute {
            val c = client ?: return@execute
            if (!c.isConnected) return@execute
            runCatching { c.publish("$base/$senderHex/$kind", payload, 0, retained) }
        }
    }

    private fun connect() {
        if (stopped) return
        val uri = brokers[brokerIndex % brokers.size]
        onStatus(TransportStatus(TransportStatus.State.CONNECTING, host(uri)))
        try {
            val c = MqttClient(uri, "maparound-" + java.util.UUID.randomUUID().toString().take(12), MemoryPersistence())
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    // Paho must not be called back from its own callback thread.
                    io.execute { subscribe(c, serverURI) }
                }

                override fun connectionLost(cause: Throwable?) {
                    onStatus(TransportStatus(TransportStatus.State.CONNECTING, "Reconnecting to ${host(uri)}…"))
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (message.payload.isNotEmpty()) onPacket(message.payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            c.connect(
                MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = true
                    connectionTimeout = 15
                    keepAliveInterval = 45
                    maxInflight = 50
                },
            )
            client = c
        } catch (e: Exception) {
            if (stopped) return
            brokerIndex++ // try the next public broker
            onStatus(TransportStatus(TransportStatus.State.ERROR, "${host(uri)}: ${e.message ?: e.javaClass.simpleName}"))
            // (stop() may have shut the executor down meanwhile)
            runCatching { io.schedule({ connect() }, if (brokerIndex % brokers.size == 0) 30L else 2L, TimeUnit.SECONDS) }
        }
    }

    private fun subscribe(c: MqttClient, serverUri: String) {
        try {
            c.subscribe("$base/+/+", 0)
            onStatus(TransportStatus(TransportStatus.State.CONNECTED, host(serverUri)))
        } catch (e: Exception) {
            onStatus(TransportStatus(TransportStatus.State.ERROR, "Subscribe failed: ${e.message}"))
        }
    }

    private fun host(uri: String) = uri.substringAfter("://").substringBefore(':')

    companion object {
        const val ROOT = "maparound/v1"

        /** Free public brokers with TLS; fine for testing, use your own server for real groups. */
        val PUBLIC_BROKERS = listOf(
            "ssl://broker.emqx.io:8883",
            "ssl://test.mosquitto.org:8886",
            "ssl://broker.hivemq.com:8883",
        )

        fun kindOf(message: PartyMessage): Pair<String, Boolean> = when (message) {
            is PartyMessage.Position -> "pos" to true
            is PartyMessage.Hello -> "hello" to true
            is PartyMessage.Sos -> "sos" to true
            is PartyMessage.Meet -> "meet" to true
            is PartyMessage.Text -> "msg" to false
            PartyMessage.Leave -> "msg" to false
        }
    }
}
