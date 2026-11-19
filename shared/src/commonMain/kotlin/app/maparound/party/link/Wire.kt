package app.maparound.party.link

import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import kotlin.math.roundToLong

// Plain Kotlin: the building blocks of Link v1's bytes (docs/LINK_PROTOCOL.md §1). Unit-tested.

/**
 * A point on Link v1's grid: [lat] and [lon] are 24-bit integers, about 1.2 m of latitude and
 * 2.4 m of longitude at the equator per step. Every position and every mark vertex is one.
 */
data class GridPoint(val lat: Int, val lon: Int) {
    val latDeg: Double get() = lat * 90.0 / Grid.SCALE
    val lonDeg: Double get() = lon * 180.0 / Grid.SCALE

    companion object {
        fun of(latDeg: Double, lonDeg: Double) = GridPoint(Grid.lat(latDeg), Grid.lon(lonDeg))
    }
}

object Grid {
    /** 2^23: the grid's steps per 90° of latitude and per 180° of longitude. */
    const val SCALE = 1 shl 23
    private const val MAX = SCALE - 1

    /** Latitude in degrees to grid steps, clamped to the 24-bit range (90° is one step short). */
    fun lat(deg: Double): Int = (deg / 90.0 * SCALE).roundToLong().coerceIn(-SCALE.toLong(), MAX.toLong()).toInt()

    /** Longitude in degrees to grid steps, wrapped into the 24-bit range (180° is −180°). */
    fun lon(deg: Double): Int = wrap((deg / 180.0 * SCALE).roundToLong())

    /** [v] wrapped into −2^23 … 2^23 − 1: longitudes, and the change between two of them. */
    fun wrap(v: Long): Int = ((v + SCALE).mod(2L * SCALE) - SCALE).toInt()
}

/** Unsigned LEB128, as in Protocol Buffers. */
internal fun ByteWriter.varint(value: Long) = apply {
    var v = value
    while (v and 0x7FL.inv() != 0L) {
        byte(((v and 0x7F) or 0x80).toInt())
        v = v ushr 7
    }
    byte(v.toInt())
}

/** Zigzag, then varint: small numbers of either sign take one byte. */
internal fun ByteWriter.zz(value: Long) = varint((value shl 1) xor (value shr 63))

internal fun ByteWriter.i24(v: Int) = byte(v shr 16).byte(v shr 8).byte(v)

internal fun ByteWriter.u32(v: Long) = int(v.toInt())

internal fun ByteWriter.vertex(p: GridPoint) = i24(p.lat).i24(p.lon)

/** The change from [from] to [to], longitude wrapped, so a line may cross the antimeridian. */
internal fun ByteWriter.delta(from: GridPoint, to: GridPoint) =
    zz((to.lat - from.lat).toLong()).zz(Grid.wrap(to.lon.toLong() - from.lon).toLong())

/** A `u8` length and UTF-8, cut on a character boundary to at most [maxBytes]. */
internal fun ByteWriter.string(s: String, maxBytes: Int) = apply {
    val bytes = utf8Cut(s, maxBytes)
    byte(bytes.size).bytes(bytes)
}

/** [s] as UTF-8, at most [maxBytes], never ending in the middle of a character. */
internal fun utf8Cut(s: String, maxBytes: Int): ByteArray {
    val bytes = s.encodeToByteArray()
    if (bytes.size <= maxBytes) return bytes
    var cut = maxBytes
    // Back off over continuation bytes (10xxxxxx) to the start of the character cut in two.
    while (cut > 0 && bytes[cut].toInt() and 0xC0 == 0x80) cut--
    return bytes.copyOf(cut)
}

internal fun ByteReader.varint(): Long {
    var result = 0L
    var shift = 0
    while (true) {
        if (shift > 63) throw IllegalStateException("Varint too long")
        val b = u8()
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
    }
}

internal fun ByteReader.zz(): Long {
    val v = varint()
    return (v ushr 1) xor -(v and 1)
}

/** A varint that has to fit an Int (counts, sizes): throws on a larger one. */
internal fun ByteReader.count(max: Int): Int {
    val v = varint()
    if (v < 0 || v > max) throw IllegalStateException("Count $v over $max")
    return v.toInt()
}

internal fun ByteReader.i24(): Int = (u8() shl 24 or (u8() shl 16) or (u8() shl 8)) shr 8

internal fun ByteReader.u16(): Int = short().toInt() and 0xFFFF

internal fun ByteReader.u32(): Long = int().toLong() and 0xFFFFFFFFL

internal fun ByteReader.vertex() = GridPoint(i24(), i24())

internal fun ByteReader.delta(from: GridPoint): GridPoint {
    val lat = from.lat.toLong() + zz()
    if (lat < -Grid.SCALE || lat >= Grid.SCALE) throw IllegalStateException("Latitude off the grid")
    return GridPoint(lat.toInt(), Grid.wrap(from.lon + zz()))
}

internal fun ByteReader.string(): String = bytes(u8()).decodeToString()

/** FNV-1a, 32 bits: the marks' fingerprints and summary (§5). */
internal class Fnv {
    var hash: Int = OFFSET
        private set

    fun bytes(b: ByteArray) = apply {
        for (x in b) hash = (hash xor (x.toInt() and 0xFF)) * PRIME
    }

    fun u32(v: Long) = bytes(ByteWriter(4).u32(v).toByteArray())

    private companion object {
        const val OFFSET = 0x811C9DC5.toInt()
        const val PRIME = 0x01000193
    }
}
