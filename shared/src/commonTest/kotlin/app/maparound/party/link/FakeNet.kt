package app.maparound.party.link

import app.maparound.party.MqttSocket
import app.maparound.party.MqttSockets
import app.maparound.party.MqttWire

// A fake network for the party tests: MQTT brokers that speak enough MQTT 3.1.1 (retained
// messages, wildcards, a publisher getting its own messages back), and a LoRa mesh where every
// other radio hears what one sends. Callbacks come later, from one event loop, as on the platforms.

internal class FakeLoop {
    private val q = ArrayDeque<() -> Unit>()

    fun post(f: () -> Unit) {
        q += f
    }

    fun drain() {
        var n = 0
        while (q.isNotEmpty()) {
            q.removeFirst()()
            check(++n < 1_000_000) { "the network never goes quiet" }
        }
    }
}

internal class FakeBroker(val loop: FakeLoop) {
    var up = true

    /** Takes packets and answers nothing (a server gone quiet). */
    var mute = false
    val retained = LinkedHashMap<String, ByteArray>()
    val clients = ArrayList<Client>()

    /** What clients published: topic, payload, retained. */
    val published = ArrayList<Triple<String, ByteArray, Boolean>>()
    var pings = 0

    inner class Client(val owner: String, val onData: (ByteArray) -> Unit, val onClose: (String) -> Unit) : MqttSocket {
        val subs = LinkedHashSet<String>()
        var open = true

        override fun send(bytes: ByteArray) {
            if (!open) return
            val copy = bytes.copyOf()
            loop.post { if (open && !mute) handle(this, copy) }
        }

        override fun close() {
            open = false
            clients.remove(this)
        }

        fun deliver(bytes: ByteArray) = loop.post { if (open) onData(bytes) }

        fun kill(why: String) {
            close()
            loop.post { onClose(why) }
        }
    }

    fun crash(wipe: Boolean = false) {
        up = false
        clients.toList().forEach { it.kill("gone") }
        if (wipe) retained.clear()
    }

    fun kick(owner: String) = clients.filter { it.owner == owner }.forEach { it.kill("kicked") }

    /** A message from outside the parties (a blind relay's post). */
    fun inject(topic: String, payload: ByteArray) = publish(topic, payload, false)

    private fun handle(c: Client, bytes: ByteArray) {
        var i = 1
        while (bytes[i].toInt() and 0x80 != 0) i++
        val body = bytes.copyOfRange(i + 1, bytes.size)
        fun u16(at: Int) = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
        fun str(at: Int) = body.decodeToString(at + 2, at + 2 + u16(at))
        when ((bytes[0].toInt() and 0xFF) ushr 4) {
            1 -> c.deliver(byteArrayOf(0x20, 2, 0, 0))
            3 -> {
                val topic = str(0)
                val qos = (bytes[0].toInt() ushr 1) and 3
                val at = 2 + u16(0) + if (qos > 0) 2 else 0
                published += Triple(topic, body.copyOfRange(at, body.size), bytes[0].toInt() and 1 == 1)
                publish(topic, body.copyOfRange(at, body.size), bytes[0].toInt() and 1 == 1)
            }
            8 -> {
                val filter = str(2)
                c.subs += filter
                c.deliver(byteArrayOf(0x90.toByte(), 3, body[0], body[1], 0))
                retained.filterKeys { matches(filter, it) }.forEach { (t, p) -> c.deliver(MqttWire.publish(t, p, retained = true)) }
            }
            10 -> {
                c.subs -= str(2)
                c.deliver(byteArrayOf(0xB0.toByte(), 2, body[0], body[1]))
            }
            12 -> {
                pings++
                c.deliver(byteArrayOf(0xD0.toByte(), 0))
            }
            14 -> c.close()
        }
    }

    private fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        if (retain) if (payload.isEmpty()) retained.remove(topic) else retained[topic] = payload
        for (other in clients.toList()) if (other.subs.any { matches(it, topic) }) other.deliver(MqttWire.publish(topic, payload, retained = false))
    }

    fun matches(filter: String, topic: String): Boolean {
        val f = filter.split('/')
        val t = topic.split('/')
        for (i in f.indices) {
            if (f[i] == "#") return true
            if (i >= t.size || (f[i] != "+" && f[i] != t[i])) return false
        }
        return f.size == t.size
    }
}

internal class FakeMesh(val loop: FakeLoop) {
    val radios = ArrayList<Pair<LinkHub, ConnectionId>>()

    /** What went on the air, in order. */
    val aired = ArrayList<ByteArray>()

    fun radio(hub: LinkHub, id: ConnectionId): LinkRadio {
        radios += hub to id
        return LinkRadio { bytes ->
            aired += bytes
            loop.post { radios.filter { it.first !== hub }.forEach { (h, i) -> h.fromRadio(i, bytes) } }
        }
    }
}

/** The brokers by address, and each device's way to them. */
internal class FakeInternet(val loop: FakeLoop) {
    val brokers = HashMap<String, FakeBroker>()

    fun broker(url: String) = brokers.getOrPut(url) { FakeBroker(loop) }

    /** [owner]'s way to the brokers: unknown hosts can't even start, a broker that's down (or [online] off) closes. */
    fun sockets(owner: String, online: () -> Boolean = { true }) = MqttSockets { url, onOpen, onData, onClose ->
        val b = brokers[url]
        when {
            b == null -> null
            !b.up || !online() -> object : MqttSocket {
                override fun send(bytes: ByteArray) = Unit
                override fun close() = Unit
            }.also { loop.post { onClose("unreachable") } }
            else -> b.Client(owner, onData, onClose).also {
                b.clients += it
                loop.post { onOpen() }
            }
        }
    }
}
