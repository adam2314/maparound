package app.maparound.shared.util

/** A fixed-size set of bits (java.util.BitSet is JVM only). */
class Bits(val size: Int) {
    private val words = LongArray((size + 63) ushr 6)

    operator fun get(i: Int): Boolean = (words[i ushr 6] ushr (i and 63)) and 1L != 0L

    fun set(i: Int, value: Boolean = true) {
        val w = i ushr 6
        val m = 1L shl (i and 63)
        words[w] = if (value) words[w] or m else words[w] and m.inv()
    }

    /** Bits [from] until [to]. */
    fun set(from: Int, to: Int, value: Boolean) {
        for (i in from until to) set(i, value)
    }

    val isEmpty: Boolean get() = words.all { it == 0L }

    fun cardinality(): Int = words.sumOf { it.countOneBits() }
}
