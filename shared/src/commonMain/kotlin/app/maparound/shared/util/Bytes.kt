package app.maparound.shared.util

// Byte buffers for binary formats (java.nio.ByteBuffer and ByteArrayOutputStream are JVM only).

/** A growable byte array to write into. */
class ByteWriter(capacity: Int = 64) {
    private var buf = ByteArray(capacity.coerceAtLeast(16))
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun byte(v: Int) = apply {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun bytes(v: ByteArray, from: Int = 0, to: Int = v.size) = apply {
        ensure(to - from)
        v.copyInto(buf, size, from, to)
        size += to - from
    }

    /** Big-endian (network order). */
    fun short(v: Int) = byte(v shr 8).byte(v)
    fun int(v: Int) = byte(v ushr 24).byte(v ushr 16).byte(v ushr 8).byte(v)

    fun intLe(v: Int) = byte(v).byte(v ushr 8).byte(v ushr 16).byte(v ushr 24)
    fun longLe(v: Long) = intLe(v.toInt()).intLe((v ushr 32).toInt())

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Reads numbers from a byte array, big-endian unless [littleEndian]. Throws at the end of the data. */
class ByteReader(private val buf: ByteArray, var position: Int = 0, private val littleEndian: Boolean = false) {
    val remaining: Int get() = buf.size - position

    private fun next(): Int {
        if (position >= buf.size) throw IndexOutOfBoundsException("End of data")
        return buf[position++].toInt() and 0xFF
    }

    fun byte(): Byte = next().toByte()
    fun u8(): Int = next()

    fun short(): Short {
        val a = next()
        val b = next()
        return (if (littleEndian) a or (b shl 8) else (a shl 8) or b).toShort()
    }

    fun int(): Int {
        val a = next()
        val b = next()
        val c = next()
        val d = next()
        return if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    fun long(): Long {
        val a = int().toLong() and 0xFFFFFFFFL
        val b = int().toLong() and 0xFFFFFFFFL
        return if (littleEndian) a or (b shl 32) else (a shl 32) or b
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw IndexOutOfBoundsException("End of data")
        return buf.copyOfRange(position, position + n).also { position += n }
    }

    companion object {
        /** Big-endian int at [offset]. */
        fun intAt(b: ByteArray, offset: Int): Int = ByteReader(b, offset).int()
    }
}

/** Bytes as ISO 8859-1 (Latin-1) text: every byte is one character. */
fun latin1(bytes: ByteArray): String = CharArray(bytes.size) { (bytes[it].toInt() and 0xFF).toChar() }.concatToString()
