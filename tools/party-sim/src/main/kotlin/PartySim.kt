// Plays party members for the screenshots: each member is the app's own OnlineParty, as the web app
// runs it, standing at its place and sharing it over the public MQTT brokers. The first one makes the
// party (or joins an invite link) and prints the invite; the phone joins it, and sees the others.
//
//   ./gradlew -p tools/party-sim run --args="tools/shots/party.json [invite link]"
//
// The plan (JSON): {"party": "Andorra hike", "minutes": 20,
//   "members": [{"name": "Marc", "icon": "HIKE", "color": "#43A047", "lat": 42.64, "lon": 1.49, "battery": 76, "speed": 0.8 (m/s)}, …],
//   "meeting": {"lat": 42.64, "lon": 1.48, "label": "Lunch at the dam"}}
// Prints "INVITE <link>" once the party is made, "READY" when every member is online, and "LEFT"
// after leaving (on its own after "minutes", or when a line "leave" comes on standard input).

import app.maparound.data.KeyValueStore
import app.maparound.party.MqttSocket
import app.maparound.party.MqttSockets
import app.maparound.party.OnlineParty
import app.maparound.party.PartyIcon
import app.maparound.party.PartyKey
import app.maparound.sensors.GeoFix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/** One thread for everything, as the party code expects ("the main thread"). */
private val mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "party-main").apply { isDaemon = true } }
private val main = mainExecutor.asCoroutineDispatcher()

/** MQTT over a WebSocket (subprotocol "mqtt"), with the JDK's client; callbacks on [main]. */
private object JdkSockets : MqttSockets {
    private val http = HttpClient.newHttpClient()

    override fun open(url: String, onOpen: () -> Unit, onData: (ByteArray) -> Unit, onClose: (String) -> Unit): MqttSocket? = runCatching {
        val buffer = ByteArrayOutputStream()
        val listener = object : WebSocket.Listener {
            override fun onOpen(ws: WebSocket) {
                ws.request(1)
                mainExecutor.execute(onOpen)
            }

            override fun onBinary(ws: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining()).also { data.get(it) }
                buffer.write(bytes)
                if (last) {
                    val whole = buffer.toByteArray()
                    buffer.reset()
                    mainExecutor.execute { onData(whole) }
                }
                ws.request(1)
                return null
            }

            override fun onClose(ws: WebSocket, code: Int, reason: String): CompletionStage<*>? {
                mainExecutor.execute { onClose("closed $code $reason") }
                return null
            }

            override fun onError(ws: WebSocket, error: Throwable) {
                mainExecutor.execute { onClose(error.message ?: "error") }
            }
        }
        val ws = http.newWebSocketBuilder().subprotocols("mqtt").buildAsync(URI(url), listener).join()
        object : MqttSocket {
            override fun send(bytes: ByteArray) {
                ws.sendBinary(ByteBuffer.wrap(bytes), true)
            }

            override fun close() {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "")
            }
        }
    }.getOrNull()
}

/** Each member's own settings, in memory. */
private class MemoryStore : KeyValueStore {
    private val m = HashMap<String, Any>()
    override fun contains(key: String) = key in m
    override fun getString(key: String) = m[key] as? String
    override fun getBoolean(key: String, default: Boolean) = m[key] as? Boolean ?: default
    override fun getFloat(key: String, default: Float) = m[key] as? Float ?: default
    override fun getInt(key: String, default: Int) = m[key] as? Int ?: default
    override fun putString(key: String, value: String) { m[key] = value }
    override fun putBoolean(key: String, value: Boolean) { m[key] = value }
    override fun putFloat(key: String, value: Float) { m[key] = value }
    override fun putInt(key: String, value: Int) { m[key] = value }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String) = m[key] as? Set<String>
    override fun putStringSet(key: String, value: Set<String>) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}

private class Member(val name: String, val icon: PartyIcon, val color: Int, val lat: Double, val lon: Double, val battery: Int, val speed: Float) {
    /** Where it stands, every few seconds (the party sends it at its own pace). */
    private val here = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(GeoFix(lat, lon, null, 5f, speed, null, null, now, System.nanoTime() / 1_000_000, satellite = true))
            delay(5_000)
        }
    }
    val party = OnlineParty(MemoryStore(), JdkSockets, { here }, { battery }, CoroutineScope(main + SupervisorJob()))
}

private fun colour(hex: String): Int = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()

fun main(args: Array<String>): Unit = runBlocking {
    val plan = Json.parseToJsonElement(File(args.getOrNull(0) ?: "tools/shots/party.json").readText()).jsonObject
    val invite = args.getOrNull(1)
    val members = plan["members"]!!.jsonArray.map { it.jsonObject }.map { m ->
        Member(
            m.str("name"), PartyIcon.valueOf(m.str("icon")), colour(m.str("color")),
            m["lat"]!!.jsonPrimitive.double, m["lon"]!!.jsonPrimitive.double, m["battery"]?.jsonPrimitive?.int ?: 80,
            m["speed"]?.jsonPrimitive?.double?.toFloat() ?: 0f,
        )
    }
    val minutes = plan["minutes"]?.jsonPrimitive?.int ?: 20

    // The first makes the party (or joins the invite); the others join it.
    val key = launchOn { first ->
        val k = invite?.let { PartyKey.parse(it) ?: error("Not an invite: $it") } ?: PartyKey.create(plan.str("party"))
        first.party.join(k, first.name)
        k
    }(members[0])
    for (m in members.drop(1)) launchOn<Unit> { it.party.join(key, it.name) }(m)
    for (m in members) launchOn<Unit> {
        it.party.setProfile(it.name, it.color, it.icon.code)
        it.party.setSharing(true)
    }(m)
    println("INVITE ${key.toLink()}")

    // Everyone online, then the meeting point from the first.
    val online = withTimeoutOrNull(60_000) {
        while (members.any { !it.party.state.value.connected }) delay(500)
    }
    if (online == null) System.err.println("not every member got online: " + members.filter { !it.party.state.value.connected }.joinToString { it.name })
    plan["meeting"]?.jsonObject?.let { meet ->
        launchOn<Unit> { it.party.setMeeting(meet["lat"]!!.jsonPrimitive.double, meet["lon"]!!.jsonPrimitive.double, meet.str("label")) }(members[0])
    }
    // The first sees the others: the broker carries them.
    withTimeoutOrNull(30_000) {
        while (members[0].party.state.value.members.size < members.size - 1) delay(500)
    }
    println("SEES " + members[0].party.state.value.members.joinToString { it.name })
    println("READY")
    System.out.flush()

    // Until told to leave, or the time is up.
    val stdin = Thread {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.trim() == "leave") break
        }
    }.apply { isDaemon = true; start() }
    val until = System.currentTimeMillis() + minutes * 60_000L
    while (stdin.isAlive && System.currentTimeMillis() < until) delay(1_000)
    for (m in members) launchOn<Unit> { it.party.leave() }(m)
    delay(3_000) // the empty retained messages that clear their positions
    println("LEFT")
    exitProcess(0)
}

private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

/** Runs [block] for a member on the party's thread and waits for it. */
private fun <T> launchOn(block: (Member) -> T): (Member) -> T = { m ->
    runBlocking(main) { block(m) }
}

/** Whether the party is talking to a broker. */
private val app.maparound.party.PartyState.connected: Boolean
    get() = online.state == app.maparound.party.TransportStatus.State.CONNECTED
