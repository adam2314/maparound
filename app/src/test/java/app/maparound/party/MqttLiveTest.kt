package app.maparound.party

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two members exchanging an encrypted position through a real public broker.
 * Runs only with MQTT_LIVE=1 (needs internet).
 */
class MqttLiveTest {
    @Test
    fun positionTravelsThroughPublicBroker() {
        assumeTrue(System.getenv("MQTT_LIVE") == "1")
        val key = PartyKey.create("live test")
        val received = CountDownLatch(1)
        val ready = CountDownLatch(2)
        var got: Received? = null
        val statuses = mutableListOf<String>()

        val bob = MqttTransport(key.tagHex, onPacket = { bytes ->
            Envelope.decode(key, bytes)?.let { got = it; received.countDown() }
        }, onStatus = { s -> statuses += "bob:${s.state} ${s.detail}"; if (s.state == TransportStatus.State.CONNECTED) ready.countDown() })
        val alice = MqttTransport(key.tagHex, onPacket = {}, onStatus = { s ->
            statuses += "alice:${s.state} ${s.detail}"; if (s.state == TransportStatus.State.CONNECTED) ready.countDown()
        })
        bob.start()
        alice.start()
        assertTrue("both connected: $statuses", ready.await(40, TimeUnit.SECONDS))

        val pos = PartyMessage.Position(31.7681, 35.1584, 700, 6, 45, 90, 80, driving = true)
        val bytes = Envelope.encode(key, 0x0A11CE, 1, System.currentTimeMillis(), pos)
        alice.publish("000a11ce", "pos", bytes, retained = false)
        assertTrue("bob received it: $statuses", received.await(20, TimeUnit.SECONDS))
        assertEquals(0x0A11CE, got!!.sender)
        assertEquals(31.7681, (got!!.message as PartyMessage.Position).lat, 1e-6)
        println("MQTT live OK via ${statuses.filter { "CONNECTED" in it }}")
        alice.stop()
        bob.stop()
    }
}
