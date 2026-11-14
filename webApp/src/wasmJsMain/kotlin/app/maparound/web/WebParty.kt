package app.maparound.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.maparound.i18n.T
import app.maparound.party.MqttSocket
import app.maparound.party.MqttSockets
import app.maparound.party.PartyController
import app.maparound.party.PartyEvent
import app.maparound.party.PartyPlatform
import app.maparound.shared.toByteArray
import app.maparound.shared.toUint8Array
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// The party in the web app is the shared OnlineParty: a full member online, and over a LoRa radio
// where the browser has Bluetooth (WebRadio.kt). Here are the browser's parts: its WebSocket, the
// battery level, notifications, the invite's QR code and the radio picker.

// ---- the browser's WebSocket (MQTT over WebSocket: binary messages, subprotocol "mqtt") ---------

private fun wsOpen(url: String, onOpen: () -> Unit, onData: (JsAny) -> Unit, onClose: (String) -> Unit): JsAny? = js(
    """(() => {
    let ws;
    try { ws = new WebSocket(url, 'mqtt'); } catch (e) { return null; }
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => onOpen();
    ws.onmessage = (e) => { if (e.data instanceof ArrayBuffer) onData(new Uint8Array(e.data)); };
    ws.onclose = (e) => onClose(e.reason || '');
    return ws;
})()""",
)

private fun wsSend(ws: JsAny, data: JsAny): Unit = js("{ try { if (ws.readyState === 1) ws.send(data); } catch (e) {} }")

private fun wsClose(ws: JsAny): Unit = js("{ ws.onopen = null; ws.onmessage = null; ws.onclose = null; try { ws.close(); } catch (e) {} }")

/** The party's connections to the brokers: the browser's WebSockets. */
object WebMqttSockets : MqttSockets {
    override fun open(url: String, onOpen: () -> Unit, onData: (ByteArray) -> Unit, onClose: (String) -> Unit): MqttSocket? {
        val ws = wsOpen(url, onOpen, { data -> onData(data.toByteArray()) }, onClose) ?: return null
        return object : MqttSocket {
            override fun send(bytes: ByteArray) = wsSend(ws, bytes.toUint8Array())
            override fun close() = wsClose(ws)
        }
    }
}

// ---- the battery (Chrome and Edge; others don't say) ----------------------------------------------

private fun batteryWatch(onLevel: (Int) -> Unit): Unit = js(
    """{
    if (navigator.getBattery) navigator.getBattery().then((b) => {
        const f = () => onLevel(Math.round(b.level * 100));
        f();
        b.addEventListener('levelchange', f);
    }).catch(() => {});
}""",
)

/** The battery level for the party's positions, null where the browser doesn't tell. */
object WebBattery {
    var level: Int? = null
        private set

    init {
        batteryWatch { level = it }
    }
}

// ---- notifications: SOS alerts and messages while the page is in the background ---------------

private fun notificationsAllowed(): Boolean = js("typeof Notification !== 'undefined' && Notification.permission === 'granted'")

private fun askForNotifications(): Unit = js(
    "{ if (typeof Notification !== 'undefined' && Notification.permission === 'default') Notification.requestPermission().catch(() => {}); }",
)

private fun pageHidden(): Boolean = js("document.visibilityState === 'hidden'")

/** Through the service worker where there is one (phones allow no other way), else directly. */
private fun showNotification(title: String, body: String, urgent: Boolean): Unit = js(
    """{
    const opts = { body: body, icon: 'icon-192.png', tag: urgent ? 'party-sos' : 'party', renotify: true, requireInteraction: urgent };
    if (navigator.serviceWorker && navigator.serviceWorker.controller) {
        navigator.serviceWorker.ready.then((r) => r.showNotification(title, opts)).catch(() => {});
    } else {
        try { const n = new Notification(title, opts); n.onclick = () => { window.focus(); n.close(); }; } catch (e) {}
    }
}""",
)

/** The party's alerts as notifications while the page isn't showing (as Android's). */
fun notifyPartyEvents(party: PartyController) {
    MainScope().launch {
        party.events.collect { ev ->
            if (!pageHidden() || !notificationsAllowed()) return@collect
            when (ev) {
                is PartyEvent.SosRaised -> showNotification(T.party.sosNotificationTitle(ev.member.displayName), ev.text.ifBlank { T.party.sosNeedsHelp }, true)
                is PartyEvent.TextReceived -> showNotification(ev.member.displayName, ev.text, false)
                is PartyEvent.MeetingSet -> ev.meeting?.let { showNotification(T.party.newMeetingPoint, it.label.ifBlank { T.party.tapToSeeOnMap }, false) }
                else -> Unit
            }
        }
    }
}

// ---- the invite's QR code ------------------------------------------------------------------------

/** The QR code of [text] as "n:" and n × n of 0 and 1 (row by row), or "" (qrcode.js not loaded). */
private fun qrBits(text: String): String = js(
    """(() => {
    try {
        if (typeof qrcode !== 'function') return '';
        if (qrcode.stringToBytesFuncs && qrcode.stringToBytesFuncs['UTF-8']) qrcode.stringToBytes = qrcode.stringToBytesFuncs['UTF-8'];
        const q = qrcode(0, 'M');
        q.addData(text);
        q.make();
        const n = q.getModuleCount();
        let s = n + ':';
        for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) s += q.isDark(r, c) ? '1' : '0';
        return s;
    } catch (e) { return ''; }
})()""",
)

/** The party's platform parts in the browser: the invite's QR code, notifications, the radio picker; no camera scanning. */
object WebPartyPlatform : PartyPlatform {
    override val canScanQr: Boolean get() = false

    @Composable
    override fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit = remember { {} }

    @Composable
    override fun QrImage(text: String, modifier: Modifier) {
        val bits = remember(text) { qrBits(text) }
        val n = bits.substringBefore(':', "").toIntOrNull()
        if (n == null) {
            Box(modifier) { Text(text) }
            return
        }
        val cells = bits.substringAfter(':')
        Canvas(modifier.background(Color.White)) {
            // A quiet zone of 4 modules all round, as scanners want.
            val m = size.minDimension / (n + 8)
            val x0 = (size.width - m * n) / 2
            val y0 = (size.height - m * n) / 2
            for (r in 0 until n) for (c in 0 until n) {
                if (cells[r * n + c] == '1') drawRect(Color.Black, Offset(x0 + c * m, y0 + r * m), Size(m + 0.5f, m + 0.5f))
            }
        }
    }

    /** The browser's own chooser of Meshtastic radios (right after the tap on Pair, as it requires). */
    @Composable
    override fun RadioPicker(onPick: (address: String, name: String) -> Unit, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) {
            val picked = if (WebRadios.available) WebRadios.pick() else null
            if (picked != null) onPick(picked.first, picked.second) else onDismiss()
        }
    }

    override val runsInBackground: Boolean get() = false

    override fun connectionsNote(active: Boolean): String? = listOfNotNull(
        T.party.browserNoRadio.takeUnless { WebRadios.available },
        T.party.browserKeepOpen.takeIf { active },
    ).joinToString(" ").ifEmpty { null }

    @Composable
    override fun rememberNotificationPermission(): () -> Unit = remember { { askForNotifications() } }
}
