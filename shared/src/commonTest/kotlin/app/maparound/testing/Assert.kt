package app.maparound.testing

import kotlin.math.abs
import kotlin.test.fail

// JUnit 4's Assert functions on top of kotlin.test, with the same argument order (message first)
// and overloads, so tests moved from the Android app into common code mean exactly the same.

private fun prefix(message: String?) = if (message.isNullOrEmpty()) "" else "$message "

fun assertTrue(condition: Boolean) = assertTrue(null, condition)

fun assertTrue(message: String?, condition: Boolean) {
    if (!condition) fail(message ?: "expected true")
}

fun assertFalse(condition: Boolean) = assertFalse(null, condition)

fun assertFalse(message: String?, condition: Boolean) {
    if (condition) fail(message ?: "expected false")
}

fun assertNull(value: Any?) = assertNull(null, value)

fun assertNull(message: String?, value: Any?) {
    if (value != null) fail(prefix(message) + "expected null, but was:<$value>")
}

fun assertNotNull(value: Any?) = assertNotNull(null, value)

fun assertNotNull(message: String?, value: Any?) {
    if (value == null) fail(message ?: "expected not null")
}

fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

fun assertEquals(message: String?, expected: Any?, actual: Any?) {
    if (expected != actual) fail(prefix(message) + "expected:<$expected> but was:<$actual>")
}

fun assertEquals(expected: Long, actual: Long) = assertEquals(null, expected, actual)

fun assertEquals(message: String?, expected: Long, actual: Long) {
    if (expected != actual) fail(prefix(message) + "expected:<$expected> but was:<$actual>")
}

fun assertEquals(expected: Double, actual: Double, delta: Double) = assertEquals(null, expected, actual, delta)

fun assertEquals(message: String?, expected: Double, actual: Double, delta: Double) {
    if (expected.compareTo(actual) != 0 && !(abs(expected - actual) <= delta)) {
        fail(prefix(message) + "expected:<$expected> but was:<$actual>")
    }
}

fun assertEquals(expected: Float, actual: Float, delta: Float) = assertEquals(null, expected, actual, delta)

fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
    if (expected.compareTo(actual) != 0 && !(abs(expected - actual) <= delta)) {
        fail(prefix(message) + "expected:<$expected> but was:<$actual>")
    }
}

fun assertArrayEquals(expected: ByteArray?, actual: ByteArray?) = assertArrayEquals(null, expected, actual)

fun assertArrayEquals(message: String?, expected: ByteArray?, actual: ByteArray?) {
    if (!(expected contentEquals actual)) {
        fail(prefix(message) + "arrays differ: expected:<${expected?.contentToString()}> but was:<${actual?.contentToString()}>")
    }
}
