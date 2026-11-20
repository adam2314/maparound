package app.maparound.party.link

import app.maparound.shared.util.hex

// Plain Kotlin: where Link v1 packets go on an MQTT server (docs/LINK_PROTOCOL.md §6, "Online").
// Unit-tested.

/** A packet's place on a server: its [topic], and whether the server keeps it for later subscribers. */
data class LinkTopic(val topic: String, val retained: Boolean)

object LinkTopics {
    const val ROOT = "maparound/link1"

    /** Everything in the party with [tagHex] ([LinkKey.tagHex]): what a member subscribes to. */
    fun all(tagHex: String) = "$ROOT/$tagHex/#"

    /**
     * Where a packet with header [h] goes. [snapshot]: a mark's full state, for its own retained
     * topic (else a MARK is a relay's post). Null for what never goes online (DIGEST, WANT).
     */
    fun of(tagHex: String, h: LinkHeader, snapshot: MarkId? = null): LinkTopic? {
        val member = "$ROOT/$tagHex/${sender(h.sender)}"
        return when (h.type) {
            LinkType.POS -> LinkTopic("$member/pos", true)
            LinkType.HELLO -> LinkTopic("$member/hello", true)
            LinkType.SOS -> LinkTopic("$member/sos", true)
            LinkType.TEXT -> LinkTopic("$member/text", false)
            LinkType.ACK -> LinkTopic("$member/ack", false)
            LinkType.LEAVE -> LinkTopic("$member/leave", false)
            LinkType.MARK -> if (snapshot != null) LinkTopic(mark(tagHex, snapshot), true) else LinkTopic("$member/$RELAY_POST", false)
            LinkType.LIVE -> LinkTopic("$ROOT/$tagHex/live", true)
            LinkType.DIGEST, LinkType.WANT -> null
            // A later version's type: passed on as it is, for whoever reads it.
            else -> LinkTopic("$member/t${h.type}", false)
        }
    }

    /** A mark's own topic, holding its full state. */
    fun mark(tagHex: String, id: MarkId) = "$ROOT/$tagHex/o/${sender(id.creator)}-${id.counter}"

    /** What a LEAVE clears (empty retained messages): the member's kept position, hello and SOS. */
    fun cleared(tagHex: String, sender: Int): List<String> =
        listOf("pos", "hello", "sos").map { "$ROOT/$tagHex/${sender(sender)}/$it" }

    /**
     * A MARK a relay posted as it heard it on the radio (a change, maybe a part), not a mark's
     * kept state: whoever merges it publishes the mark's full state.
     */
    fun isRelayPost(topic: String) = topic.endsWith("/$RELAY_POST")

    private const val RELAY_POST = "mark"

    private fun sender(id: Int) = hex(id).padStart(8, '0')
}
