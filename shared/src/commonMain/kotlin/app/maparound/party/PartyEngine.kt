package app.maparound.party

import app.maparound.routing.Geo
import app.maparound.shared.util.hex
import app.maparound.shared.util.nowMillis

// Plain Kotlin: the party's state machine. Transports hand it raw packets; it decrypts,
// de-duplicates (the same update may arrive over the internet and over LoRa), keeps the newest
// state per member, and decides what to send and what a gateway should relay.

/** How a packet travelled. */
enum class Via(val label: String) { ONLINE("online"), LORA("radio"), NEARBY("nearby") }

data class Member(
    val id: Int,
    val name: String = "",
    val color: Int = 0,
    val meshNode: Long = 0,
    val position: PartyMessage.Position? = null,
    /** When the position was taken (sender's clock). */
    val positionAt: Long = 0,
    /** When we last heard anything from this member (our clock), per transport. */
    val heard: Map<Via, Long> = emptyMap(),
    val sos: Boolean = false,
    val sosText: String = "",
    val left: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { "Member " + hex(id).takeLast(4).uppercase() }
    val lastHeard: Long get() = heard.values.maxOrNull() ?: 0
}

/** A Meshtastic radio in range that is not (known to be) a party member, e.g. a standalone tracker. */
data class MeshNode(val num: Long, val name: String, val shortName: String, val position: Mesh.Position?, val heardAt: Long)

data class Meeting(val lat: Double, val lon: Double, val label: String, val setBy: Int)

data class ChatLine(val from: Int, val text: String, val at: Long)

sealed class PartyEvent {
    data class Joined(val member: Member) : PartyEvent()
    data class SosRaised(val member: Member, val text: String) : PartyEvent()
    data class SosCancelled(val member: Member) : PartyEvent()
    data class MeetingSet(val meeting: Meeting?) : PartyEvent()
    data class TextReceived(val member: Member, val text: String) : PartyEvent()
    data class Left(val member: Member) : PartyEvent()
}

class PartyEngine(val key: PartyKey, val myId: Int, private val clock: () -> Long = ::nowMillis) {
    val members = LinkedHashMap<Int, Member>()
    val meshNodes = LinkedHashMap<Long, MeshNode>()
    var meeting: Meeting? = null
        private set
    val chat = ArrayDeque<ChatLine>()
    var myMeshNode: Long = 0

    private val seen = object : LinkedHashMap<Long, Unit>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?) = size > 20_000
    }
    private val forwardedAt = HashMap<String, Long>()

    data class Accepted(val received: Received, val events: List<PartyEvent>)

    /** Handles a raw packet. Returns null when it's not ours, damaged, a duplicate, or our own echo. */
    fun receive(bytes: ByteArray, via: Via): Accepted? {
        val rec = Envelope.decode(key, bytes) ?: return null
        if (rec.sender == myId) return null
        val now = clock()
        val known = members[rec.sender]
        var m = (known ?: Member(rec.sender)).let { it.copy(heard = it.heard + (via to now)) }
        val dupKey = (rec.sender.toLong() shl 32) or rec.seq
        if (seen.put(dupKey, Unit) != null) {
            members[rec.sender] = m // still worth knowing it also reached us this way
            return null
        }
        val events = ArrayList<PartyEvent>()
        when (val msg = rec.message) {
            is PartyMessage.Position -> if (rec.sentAt >= m.positionAt) {
                if (msg.sos && !m.sos) events += PartyEvent.SosRaised(m, m.sosText)
                m = m.copy(position = msg, positionAt = rec.sentAt, sos = msg.sos || (m.sos && msg.sos), left = false)
            }
            is PartyMessage.Hello -> {
                m = m.copy(name = msg.name, color = msg.color, meshNode = msg.meshNode, left = false)
                if (msg.meshNode != 0L) meshNodes.remove(msg.meshNode)
            }
            is PartyMessage.Sos -> {
                if (msg.active && !m.sos) events += PartyEvent.SosRaised(m.copy(sosText = msg.text), msg.text)
                if (!msg.active && m.sos) events += PartyEvent.SosCancelled(m)
                m = m.copy(sos = msg.active, sosText = msg.text)
            }
            is PartyMessage.Meet -> {
                meeting = if (msg.label.isEmpty() && msg.lat == 0.0 && msg.lon == 0.0) null else Meeting(msg.lat, msg.lon, msg.label, rec.sender)
                events += PartyEvent.MeetingSet(meeting)
            }
            is PartyMessage.Text -> {
                chat.addLast(ChatLine(rec.sender, msg.text, rec.sentAt))
                while (chat.size > 50) chat.removeFirst()
                events += PartyEvent.TextReceived(m, msg.text)
            }
            PartyMessage.Leave -> {
                m = m.copy(left = true, sos = false)
                events += PartyEvent.Left(m)
            }
        }
        if (known == null && !m.left) events.add(0, PartyEvent.Joined(m))
        members[rec.sender] = m
        return Accepted(rec, events)
    }

    /** Applies our own outgoing message locally (chat, meeting point). */
    fun applyOwn(message: PartyMessage, sentAt: Long) {
        when (message) {
            is PartyMessage.Meet -> meeting =
                if (message.label.isEmpty() && message.lat == 0.0 && message.lon == 0.0) null
                else Meeting(message.lat, message.lon, message.label, myId)
            is PartyMessage.Text -> {
                chat.addLast(ChatLine(myId, message.text, sentAt))
                while (chat.size > 50) chat.removeFirst()
            }
            else -> Unit
        }
    }

    /** Positions broadcast by Meshtastic radios themselves (NodeInfo / POSITION_APP). */
    fun meshNode(num: Long, name: String?, shortName: String?, position: Mesh.Position?) {
        if (num == 0L || num == myMeshNode || members.values.any { it.meshNode == num }) return
        val old = meshNodes[num]
        meshNodes[num] = MeshNode(
            num,
            name?.takeIf { it.isNotBlank() } ?: old?.name ?: "!" + hex(num),
            shortName?.takeIf { it.isNotBlank() } ?: old?.shortName ?: "",
            position ?: old?.position,
            clock(),
        )
    }

    /**
     * Gateway: should a packet received over [from] be relayed to [to]? Relays each member's
     * positions at most at [to]'s pace; alerts, hello, meeting points and texts always.
     */
    fun shouldForward(accepted: Accepted, from: Via, to: Via): Boolean {
        if (from == to) return false
        val msg = accepted.received.message
        if (msg !is PartyMessage.Position) return true
        val k = "${accepted.received.sender}:$to"
        val now = clock()
        val minGap = if (to == Via.LORA) (if (msg.sos) 30_000L else 60_000L) else 5_000L
        val last = forwardedAt[k]
        if (last != null && now - last < minGap) return false
        forwardedAt[k] = now
        return true
    }
}

/** When to send our own position on each transport. Speeds above 15 km/h count as driving. */
object SendPolicy {
    data class Last(val at: Long, val lat: Double, val lon: Double)

    const val DRIVING_KMH = 15.0

    fun due(via: Via, last: Last?, now: Long, lat: Double, lon: Double, speedKmh: Double, sos: Boolean): Boolean {
        if (last == null) return true
        val dt = now - last.at
        val moved = Geo.distance(last.lat, last.lon, lat, lon)
        val driving = speedKmh >= DRIVING_KMH
        return when (via) {
            // Internet: near real time while moving, a heartbeat each minute while still.
            Via.ONLINE, Via.NEARBY -> dt >= 60_000 ||
                (dt >= (if (driving) 5_000 else 10_000) && moved >= 10) ||
                (sos && dt >= 10_000)
            // LoRa: airtime is scarce and shared by the whole group.
            Via.LORA -> dt >= 600_000 ||
                (driving && dt >= 45_000 && moved >= 200) ||
                (!driving && dt >= 120_000 && moved >= 40) ||
                (sos && dt >= 30_000)
        }
    }
}
