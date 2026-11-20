package app.maparound.party.link

import kotlin.math.ceil

// Plain Kotlin: one radio's airtime (docs/LINK_PROTOCOL.md §6, "LoRa"): a budget shared by every
// party on it, and the queue of what waits for it, urgent first. Unit-tested.

/**
 * A token bucket: on average one packet every [secondsPerPacket], bursts of up to [burst].
 * The default is the spec's 1 packet per 20 s, 3 at a time.
 */
class AirtimeBudget(private val secondsPerPacket: Double = 20.0, private val burst: Int = 3) {
    private var tokens = burst.toDouble()
    private var at: Long? = null

    private fun refill(nowMs: Long) {
        val last = at
        if (last != null && nowMs > last) tokens = minOf(burst.toDouble(), tokens + (nowMs - last) / 1000.0 / secondsPerPacket)
        at = nowMs
    }

    fun available(nowMs: Long): Boolean {
        refill(nowMs)
        return tokens >= 1
    }

    /** When the next packet's worth is there (now, if it is). */
    fun nextMs(nowMs: Long): Long {
        refill(nowMs)
        return if (tokens >= 1) nowMs else nowMs + ceil((1 - tokens) * secondsPerPacket * 1000).toLong()
    }

    /** Takes one packet's worth: false (and nothing taken) when there isn't one. */
    fun take(nowMs: Long): Boolean {
        if (!available(nowMs)) return false
        tokens -= 1
        return true
    }
}

/**
 * What waits for one radio, in the spec's order: urgent (SOS, ACK, LIVE), then LEAVE, HELLO,
 * TEXT, MARK, positions, then bulk (DIGEST, WANT, marks sent when catching up); first come first
 * served within each. SOS and ACK go outside the [budget]. A newer position from the same member
 * of the same party replaces a queued one. At most [capacity] packets: past that, the least
 * important and oldest go.
 */
class RadioQueue(private val budget: AirtimeBudget = AirtimeBudget(), private val capacity: Int = 50) {
    private class Item(val bytes: ByteArray, val header: LinkHeader, val rank: Int, val order: Long)

    private val items = ArrayList<Item>()
    private var counter = 0L

    val size: Int get() = items.size

    /**
     * Queues a sealed packet. [bulk]: sent when catching up (a MARK answering a WANT), behind
     * everything else. Packets that aren't Link v1 are refused.
     */
    fun add(bytes: ByteArray, bulk: Boolean = false): Boolean {
        val h = LinkEnvelope.header(bytes) ?: return false
        if (h.type == LinkType.POS) items.removeAll { it.header.type == LinkType.POS && it.header.tag == h.tag && it.header.sender == h.sender }
        items += Item(bytes, h, if (bulk) BULK else rank(h.type), counter++)
        while (items.size > capacity) items.remove(items.maxWith(compareBy<Item> { it.rank }.thenByDescending { it.order }))
        return true
    }

    /** Whether a packet that counts against the budget could go now. */
    fun canSend(nowMs: Long): Boolean = budget.available(nowMs)

    /** When [poll] next has something to send; null while nothing waits. */
    fun nextMs(nowMs: Long): Long? {
        val next = items.minWithOrNull(compareBy<Item> { it.rank }.thenBy { it.order }) ?: return null
        val free = next.header.type == LinkType.SOS || next.header.type == LinkType.ACK
        return if (free) nowMs else budget.nextMs(nowMs)
    }

    /** The next packet to send now, or null (nothing waits, or the budget is spent). */
    fun poll(nowMs: Long): ByteArray? {
        val next = items.minWithOrNull(compareBy<Item> { it.rank }.thenBy { it.order }) ?: return null
        val free = next.header.type == LinkType.SOS || next.header.type == LinkType.ACK
        if (!free && !budget.take(nowMs)) return null
        items.remove(next)
        return next.bytes
    }

    private companion object {
        const val BULK = 9

        fun rank(type: Int): Int = when (type) {
            LinkType.SOS, LinkType.ACK, LinkType.LIVE -> 0
            LinkType.LEAVE -> 1
            LinkType.HELLO -> 2
            LinkType.TEXT -> 3
            LinkType.MARK -> 4
            LinkType.POS -> 5
            LinkType.DIGEST, LinkType.WANT -> BULK
            else -> 6
        }
    }
}
