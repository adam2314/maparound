package app.maparound.party

import app.maparound.i18n.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * A Bluetooth LE connection to a Meshtastic radio, as the platform has it: the radio's "phone API"
 * service and its three characteristics (ToRadio, FromRadio, FromNum). One call at a time.
 */
interface RadioLink {
    /** Writes a ToRadio message. */
    suspend fun write(bytes: ByteArray)

    /** Reads one FromRadio message; empty when the radio has nothing more now. */
    suspend fun read(): ByteArray

    fun close()
}

/** The platform's way to the radios (the web: Web Bluetooth). */
interface RadioLinks {
    /**
     * Connects to the radio [id] (as the platform's picker gave it). [onMore]: the radio has
     * something to read (FromNum); [onLost]: the connection dropped. Throws when it can't connect;
     * [RadioUnknown] when the radio must be picked again.
     */
    suspend fun connect(id: String, onMore: () -> Unit, onLost: () -> Unit): RadioLink
}

/** The radio isn't known to the platform any more (the web: after a reload, picked again). */
class RadioUnknown : Exception()

/**
 * The party's LoRa radio, shared by the apps that reach it through [RadioLinks] (the web app;
 * Android has MeshtasticTransport): starts a session (want_config), reads what the radio says until
 * it's empty, and again when it says there's more; MapAround's encrypted party packets go out as
 * PRIVATE_APP broadcasts on the radio's primary channel, queued while it's away. Reconnects after
 * a growing pause (5 s, 10 s… up to a minute). Runs in [scope], on the main thread.
 */
class MeshRadio(
    private val links: RadioLinks,
    private val id: String,
    private val name: String,
    private val listener: Listener,
    private val scope: CoroutineScope,
) {
    interface Listener {
        fun onStatus(status: TransportStatus)
        fun onMyNode(num: Long)
        fun onPartyPacket(payload: ByteArray)
        fun onNode(num: Long, longName: String?, shortName: String?, position: Mesh.Position?)
    }

    private var link: RadioLink? = null
    private var job: Job? = null
    private val ops = Mutex()
    private var configured = false
    private var stopped = true
    private var retries = 0
    private val outbox = ArrayDeque<ByteArray>()

    val isReady: Boolean get() = configured

    fun start() {
        if (!stopped) return
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        job?.cancel()
        job = null
        close()
        listener.onStatus(TransportStatus(TransportStatus.State.OFF))
    }

    /** Broadcasts a MapAround party packet over LoRa; queued (the last 10) while (re)connecting. */
    fun send(payload: ByteArray) {
        val packet = Mesh.sendPacket(payload, Mesh.PORT_PRIVATE, Random.nextInt())
        if (!configured) {
            if (outbox.size >= 10) outbox.removeFirst()
            outbox.addLast(packet)
            return
        }
        scope.launch { runCatching { op { it.write(packet) } } }
    }

    private fun connect() {
        job?.cancel()
        job = scope.launch {
            status(TransportStatus.State.CONNECTING, T.party.connectingToRadio(name))
            val l = try {
                links.connect(id, onMore = { scope.launch { drain() } }, onLost = { scope.launch { lost() } })
            } catch (e: RadioUnknown) {
                status(TransportStatus.State.ERROR, T.party.pickRadioAgain)
                return@launch
            } catch (e: Exception) {
                status(TransportStatus.State.CONNECTING, T.party.couldntConnectRetrying)
                retryLater()
                return@launch
            }
            if (stopped) {
                l.close()
                return@launch
            }
            link = l
            // A session: the radio answers with our node, its node list and its config.
            runCatching { op { it.write(Mesh.wantConfig(Random.nextInt())) } }
            drain()
        }
    }

    private var draining = false
    private var more = false

    /**
     * Reads FromRadio until the radio returns an empty message; once at a time (what it read is
     * handled in order), again when the radio said there's more meanwhile.
     */
    private suspend fun drain() {
        if (draining) {
            more = true
            return
        }
        draining = true
        try {
            do {
                more = false
                while (!stopped) {
                    val bytes = runCatching { op { it.read() } }.getOrNull() ?: return
                    if (bytes.isEmpty()) break
                    onFromRadio(bytes)
                }
            } while (more && !stopped)
        } finally {
            draining = false
        }
    }

    private suspend fun lost() {
        if (stopped) return
        val was = configured
        close()
        status(TransportStatus.State.CONNECTING, if (was) T.party.radioOutOfRange else T.party.couldntConnectRetrying)
        retryLater()
    }

    private fun retryLater() {
        if (stopped) return
        retries++
        val pause = (5_000L * retries).coerceAtMost(60_000L)
        job = scope.launch {
            delay(pause)
            if (!stopped) connect()
        }
    }

    private fun close() {
        configured = false
        link?.close()
        link = null
    }

    /** One call to the radio at a time (GATT allows one operation in flight). */
    private suspend fun <T> op(f: suspend (RadioLink) -> T): T = ops.withLock {
        val l = link ?: throw IllegalStateException("no radio")
        f(l)
    }

    private suspend fun onFromRadio(bytes: ByteArray) {
        when (val f = runCatching { Mesh.parseFromRadio(bytes) }.getOrNull()) {
            is Mesh.FromRadio.MyInfo -> listener.onMyNode(f.myNodeNum)
            is Mesh.FromRadio.Node -> listener.onNode(f.info.num, f.info.user?.longName, f.info.user?.shortName, f.info.position)
            is Mesh.FromRadio.ConfigComplete -> if (!configured) {
                configured = true
                retries = 0
                status(TransportStatus.State.CONNECTED, name)
                while (outbox.isNotEmpty()) {
                    val p = outbox.removeFirst()
                    runCatching { op { it.write(p) } }
                }
            }
            is Mesh.FromRadio.PacketIn -> {
                val p = f.packet
                when (p.portnum) {
                    Mesh.PORT_PRIVATE -> listener.onPartyPacket(p.payload)
                    Mesh.PORT_POSITION -> listener.onNode(p.from, null, null, runCatching { Mesh.parsePosition(PbReader(p.payload)) }.getOrNull())
                    Mesh.PORT_NODEINFO -> runCatching { Mesh.parseUser(PbReader(p.payload)) }.getOrNull()
                        ?.let { u -> listener.onNode(p.from, u.longName, u.shortName, null) }
                }
            }
            else -> Unit
        }
    }

    private fun status(state: TransportStatus.State, detail: String) = listener.onStatus(TransportStatus(state, detail))
}
