package app.maparound.shared.net

import app.maparound.shared.binaryStringToBytes
import app.maparound.shared.fileSystem
import app.maparound.shared.toByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.IOException
import okio.Path.Companion.toPath

// The shared code calls HTTP as blocking functions (on Android and iOS from background threads).
// The browser runs the app on one thread, so these are synchronous XMLHttpRequests: the page waits
// for the answer. Fine for small requests (search, routes, lists); the server must allow other
// sites (CORS). The browser sets the User-Agent itself.

private fun xhrGet(url: String, headers: String): JsAny = js(
    """{
    const x = new XMLHttpRequest();
    try {
        x.open('GET', url, false);
        x.overrideMimeType('text/plain; charset=x-user-defined');
        const h = JSON.parse(headers);
        for (const k in h) x.setRequestHeader(k, h[k]);
        x.send();
        return { status: x.status, text: x.responseText || '' };
    } catch (e) {
        return { status: -1, text: String(e) };
    }
}""",
)

private fun status(r: JsAny): Int = js("r.status")

private fun text(r: JsAny): String = js("r.text")

actual fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): ByteArray {
    val r = xhrGet(url, JsonObject(headers.mapValues { JsonPrimitive(it.value) }).toString())
    val status = status(r)
    // 0: no answer (offline, or a server that doesn't allow this site).
    if (status <= 0) throw IOException(if (status < 0) text(r) else "No connection, or the server doesn't allow the web app")
    if (status !in 200..299) throw HttpException(status)
    return binaryStringToBytes(text(r))
}

actual fun httpDownload(url: String, path: String, timeoutMs: Int, isActive: () -> Boolean) {
    val bytes = httpGet(url, emptyMap(), timeoutMs)
    fileSystem.write(path.toPath()) { write(bytes) }
}

/** fetch() with a time limit; [done] gets the status (-1: no answer) and the bytes (a Uint8Array) of a 2xx answer. */
private fun jsFetch(url: String, timeoutMs: Int, done: (Int, JsAny?) -> Unit): Unit = js(
    """{
    const o = (typeof AbortSignal !== 'undefined' && AbortSignal.timeout) ? { signal: AbortSignal.timeout(timeoutMs) } : {};
    fetch(url, o).then(
        r => r.ok ? r.arrayBuffer().then(b => done(r.status, new Uint8Array(b)), () => done(-1, null)) : done(r.status, null),
        () => done(-1, null));
}""",
)

actual suspend fun httpGetAsync(url: String, timeoutMs: Int): ByteArray {
    val (status, bytes) = kotlin.coroutines.suspendCoroutine { c -> jsFetch(url, timeoutMs) { s, b -> c.resumeWith(Result.success(s to b)) } }
    if (status <= 0) throw IOException("No connection, or the server doesn't allow the web app")
    if (status !in 200..299) throw HttpException(status)
    return bytes?.toByteArray() ?: ByteArray(0)
}
