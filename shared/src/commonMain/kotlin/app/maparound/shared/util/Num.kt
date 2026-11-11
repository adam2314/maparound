package app.maparound.shared.util

import kotlin.math.PI
import kotlin.math.abs

// Number helpers that plain Kotlin lacks on every platform (Java has Math.toRadians and
// String.format; iOS doesn't).

fun toRadians(deg: Double): Double = deg / 180.0 * PI
fun toDegrees(rad: Double): Double = rad * 180.0 / PI

/**
 * [v] with exactly [decimals] digits after the point, like Java's `String.format(Locale.ROOT,
 * "%.nf", v)`: half-up rounding of the shortest decimal form, `-` kept for negative values,
 * no grouping.
 */
fun fixed(v: Double, decimals: Int): String {
    require(decimals >= 0)
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val negative = v < 0 || (v == 0.0 && 1.0 / v < 0)
    // The shortest decimal form: "123.456", "1.0E-5" or "1.23E10".
    val s = abs(v).toString()
    val e = s.indexOfFirst { it == 'E' || it == 'e' }
    val mantissa = if (e >= 0) s.substring(0, e) else s
    val exponent = if (e >= 0) s.substring(e + 1).toInt() else 0
    val dot = mantissa.indexOf('.')
    val intDigits = if (dot >= 0) mantissa.substring(0, dot) else mantissa
    var digits = intDigits + (if (dot >= 0) mantissa.substring(dot + 1) else "")
    var point = intDigits.length + exponent // the decimal point sits before digits[point]
    if (point < 0) {
        digits = "0".repeat(-point) + digits
        point = 0
    }
    val keep = point + decimals
    if (digits.length < keep + 1) digits = digits.padEnd(keep + 1, '0')
    // The kept digits as an integer, rounded half up on the first dropped digit.
    var k = digits.substring(0, keep).ifEmpty { "0" }
    if (digits[keep] >= '5') k = increment(k)
    k = k.padStart(decimals + 1, '0')
    val intPart = k.substring(0, k.length - decimals).trimStart('0').ifEmpty { "0" }
    val fracPart = k.substring(k.length - decimals)
    return (if (negative) "-" else "") + intPart + (if (decimals > 0) ".$fracPart" else "")
}

/** A decimal digit string plus one. */
private fun increment(digits: String): String {
    val c = digits.toCharArray()
    var i = c.size - 1
    while (i >= 0) {
        if (c[i] == '9') {
            c[i] = '0'
            i--
        } else {
            c[i] = c[i] + 1
            return c.concatToString()
        }
    }
    return "1" + c.concatToString()
}

/** [v] with at least [width] digits, zero-padded (`%0nd`). */
fun padded(v: Long, width: Int): String =
    if (v < 0) "-" + (-v).toString().padStart(width - 1, '0') else v.toString().padStart(width, '0')

fun padded(v: Int, width: Int): String = padded(v.toLong(), width)

/** Unsigned hexadecimal, lower case (`Integer.toHexString`). */
fun hex(v: Int): String = v.toUInt().toString(16)

/** Unsigned hexadecimal, lower case (`Long.toHexString`). */
fun hex(v: Long): String = v.toULong().toString(16)
