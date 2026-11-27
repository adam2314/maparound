package app.maparound.data

import kotlin.math.ceil

/**
 * How fast a download goes and how long it has left, from how much is done as time goes by (bytes,
 * or terrain's 1° cells): the rate over the last [windowMs], steadier than from one look to the next,
 * and none until it has [minSpanMs] to go on.
 */
class TransferRate(private val windowMs: Long = 10_000, private val minSpanMs: Long = 3_000) {
    /** How fast (per second) and how long still (seconds); null where not known. */
    data class Estimate(val perSecond: Double?, val secondsLeft: Long?)

    private val times = ArrayList<Long>()
    private val amounts = ArrayList<Long>()

    /** How much is [done] at [nowMs]. Less than before: it started over, and is measured afresh. */
    fun add(nowMs: Long, done: Long) {
        if (amounts.isNotEmpty() && done < amounts.last()) {
            times.clear()
            amounts.clear()
        }
        if (times.isNotEmpty() && nowMs <= times.last()) {
            amounts[amounts.lastIndex] = done
            return
        }
        times += nowMs
        amounts += done
        // The window, and the last look before it as where it starts.
        while (times.size > 2 && nowMs - times[1] >= windowMs) {
            times.removeAt(0)
            amounts.removeAt(0)
        }
    }

    /** Per second over the window; null until it covers [minSpanMs]. */
    val perSecond: Double?
        get() {
            if (times.size < 2) return null
            val span = times.last() - times.first()
            if (span < minSpanMs) return null
            return (amounts.last() - amounts.first()) * 1000.0 / span
        }

    /** Seconds until [total] at the rate now; null when unknown, or when nothing comes (waiting, stalled). */
    fun secondsLeft(total: Long): Long? {
        val rate = perSecond ?: return null
        if (rate <= 0 || total <= 0) return null
        return ceil((total - amounts.last()).coerceAtLeast(0) / rate).toLong()
    }

    fun estimate(total: Long) = Estimate(perSecond, secondsLeft(total))
}
