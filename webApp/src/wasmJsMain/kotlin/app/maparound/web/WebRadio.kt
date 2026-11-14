package app.maparound.web

import app.maparound.party.Mesh
import app.maparound.party.RadioLink
import app.maparound.party.RadioLinks
import app.maparound.party.RadioUnknown
import app.maparound.shared.toByteArray
import app.maparound.shared.toUint8Array
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Meshtastic radios over Web Bluetooth (Chrome and Edge; not Safari, so not on iPhone, nor Firefox),
// as Meshtastic's own web client reaches them. The radios the browser has let the page use are
// kept by id in window.__mrRadios; after a reload, navigator.bluetooth.getDevices() finds them
// where the browser remembers them.

private fun btAvailable(): Boolean = js("!!(navigator.bluetooth && navigator.bluetooth.requestDevice)")

/** The browser's chooser of Meshtastic radios; [done] gets its id and name, or "" when none was picked. */
private fun btPick(service: String, done: (String, String) -> Unit): Unit = js(
    """{
    navigator.bluetooth.requestDevice({ filters: [{ services: [service] }], optionalServices: [service] })
        .then((d) => { (window.__mrRadios = window.__mrRadios || new Map()).set(d.id, d); done(d.id, d.name || d.id); })
        .catch(() => done('', ''));
}""",
)

/** Connects to the radio [id]: [ok] gets a handle, [fail] why ("unknown": the browser doesn't know it now). */
private fun btConnect(
    id: String, service: String, toRadio: String, fromRadio: String, fromNum: String,
    onMore: () -> Unit, onLost: () -> Unit, ok: (JsAny) -> Unit, fail: (String) -> Unit,
): Unit = js(
    """{
    const known = (window.__mrRadios = window.__mrRadios || new Map());
    const found = known.has(id) ? Promise.resolve(known.get(id))
        : (navigator.bluetooth.getDevices ? navigator.bluetooth.getDevices().then((ds) => ds.find((d) => d.id === id)) : Promise.resolve(undefined));
    found.then((d) => {
        if (!d) { fail('unknown'); return; }
        known.set(id, d);
        const lost = () => onLost();
        d.addEventListener('gattserverdisconnected', lost);
        return d.gatt.connect()
            .then((server) => server.getPrimaryService(service))
            .then((s) => Promise.all([s.getCharacteristic(toRadio), s.getCharacteristic(fromRadio), s.getCharacteristic(fromNum)]))
            .then(([to, from, num]) => {
                num.addEventListener('characteristicvaluechanged', () => onMore());
                return num.startNotifications().catch(() => {}).then(() => ok({ d: d, to: to, from: from, lost: lost }));
            })
            .catch((e) => { d.removeEventListener('gattserverdisconnected', lost); throw e; });
    }).catch((e) => fail(String((e && e.message) || e)));
}""",
)

private fun btWrite(h: JsAny, data: JsAny, ok: () -> Unit, fail: (String) -> Unit): Unit = js(
    """{
    const c = h.to;
    (c.writeValueWithResponse ? c.writeValueWithResponse(data) : c.writeValue(data))
        .then(() => ok(), (e) => fail(String((e && e.message) || e)));
}""",
)

private fun btRead(h: JsAny, ok: (JsAny) -> Unit, fail: (String) -> Unit): Unit = js(
    """{
    h.from.readValue().then((v) => ok(new Uint8Array(v.buffer, v.byteOffset, v.byteLength)), (e) => fail(String((e && e.message) || e)));
}""",
)

private fun btClose(h: JsAny): Unit = js(
    "{ try { h.d.removeEventListener('gattserverdisconnected', h.lost); if (h.d.gatt.connected) h.d.gatt.disconnect(); } catch (e) {} }",
)

/** The party's way to Meshtastic radios in the browser. */
object WebRadios : RadioLinks {
    /** Whether this browser has Web Bluetooth. */
    val available: Boolean = btAvailable()

    /** The browser's chooser: the radio picked (its id and name), or null. Must follow a tap. */
    suspend fun pick(): Pair<String, String>? = suspendCancellableCoroutine { c ->
        btPick(Mesh.SERVICE) { id, name -> c.resume(if (id.isEmpty()) null else id to name) }
    }

    override suspend fun connect(id: String, onMore: () -> Unit, onLost: () -> Unit): RadioLink {
        val h = suspendCancellableCoroutine<JsAny> { c ->
            btConnect(
                id, Mesh.SERVICE, Mesh.TO_RADIO, Mesh.FROM_RADIO, Mesh.FROM_NUM, onMore, onLost,
                ok = { c.resume(it) },
                fail = { why -> c.resumeWithException(if (why == "unknown") RadioUnknown() else Exception(why)) },
            )
        }
        return object : RadioLink {
            override suspend fun write(bytes: ByteArray) = suspendCancellableCoroutine<Unit> { c ->
                btWrite(h, bytes.toUint8Array(), ok = { c.resume(Unit) }, fail = { c.resumeWithException(Exception(it)) })
            }

            override suspend fun read(): ByteArray = suspendCancellableCoroutine { c ->
                btRead(h, ok = { c.resume(it.toByteArray()) }, fail = { c.resumeWithException(Exception(it)) })
            }

            override fun close() = btClose(h)
        }
    }
}
