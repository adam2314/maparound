package app.maparound.shared

// Bytes between Kotlin/Wasm and JavaScript. A byte array crosses as a Latin-1 string (one char a
// byte), which is copied in one go, rather than one call per byte.

private fun latin1ToUint8Array(s: String): JsAny = js(
    """{
    const a = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
    return a;
}""",
)

private fun uint8ArrayToLatin1(a: JsAny): String = js(
    """{
    const b = a instanceof Uint8Array ? a : new Uint8Array(a.buffer || a);
    let s = '';
    for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode.apply(null, b.subarray(i, Math.min(i + 8192, b.length)));
    return s;
}""",
)

/** These bytes as a JavaScript Uint8Array. */
fun ByteArray.toUint8Array(): JsAny {
    val chars = CharArray(size)
    for (i in indices) chars[i] = (this[i].toInt() and 0xFF).toChar()
    return latin1ToUint8Array(chars.concatToString())
}

/** A JavaScript Uint8Array (or ArrayBuffer view) as bytes. */
fun JsAny.toByteArray(): ByteArray {
    val s = uint8ArrayToLatin1(this)
    return ByteArray(s.length) { s[it].code.toByte() }
}

/** A binary string as XMLHttpRequest returns it with `x-user-defined` (high bytes at U+F780…) as bytes. */
fun binaryStringToBytes(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

/** Calls [f] once after [ms] milliseconds, on the main thread. */
fun setTimeout(ms: Int, f: () -> Unit): Unit = js("{ setTimeout(f, ms); }")
