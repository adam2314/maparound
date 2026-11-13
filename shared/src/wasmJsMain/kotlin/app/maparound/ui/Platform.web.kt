package app.maparound.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.maparound.shared.toByteArray

/** The browser's file chooser for [accept] (".gpx,application/gpx+xml"…); [done] gets the name and bytes, or nulls. */
private fun chooseFile(accept: String, done: (String?, JsAny?) -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    let answered = false;
    const answer = (name, bytes) => { if (!answered) { answered = true; done(name, bytes); } };
    input.addEventListener('change', () => {
        const f = input.files && input.files[0];
        if (!f) { answer(null, null); return; }
        f.arrayBuffer().then(b => answer(f.name, new Uint8Array(b)), () => answer(f.name, null));
    });
    input.addEventListener('cancel', () => answer(null, null));
    input.click();
}""",
)

/** MIME types and the file endings that go with them (browsers match either). */
private fun accept(mimeTypes: List<String>): String {
    if (mimeTypes.any { it == "*/*" || it == "application/octet-stream" }) return ""
    val endings = mimeTypes.flatMap { m ->
        when {
            "gpx" in m -> listOf(".gpx")
            "zip" in m -> listOf(".zip")
            "xml" in m -> listOf(".xml", ".gpx")
            "json" in m -> listOf(".json")
            else -> emptyList()
        }
    }
    return (mimeTypes + endings).distinct().joinToString(",")
}

@Composable
actual fun rememberFilePicker(mimeTypes: List<String>, onPicked: (PickedFile?) -> Unit): () -> Unit {
    val callback = rememberUpdatedState(onPicked)
    return remember(mimeTypes) {
        {
            chooseFile(accept(mimeTypes)) { name, bytes ->
                callback.value(bytes?.let { PickedFile(name, it.toByteArray()) })
            }
        }
    }
}

/** The browser's back button isn't tied to the screens yet; they close with their own controls. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

private fun requestWakeLock(): JsAny? = js(
    """{
    const holder = { lock: null, released: false };
    if (navigator.wakeLock) navigator.wakeLock.request('screen').then(l => {
        if (holder.released) l.release(); else holder.lock = l;
    }).catch(() => {});
    return holder;
}""",
)

private fun releaseWakeLock(holder: JsAny): Unit = js("{ holder.released = true; if (holder.lock) holder.lock.release(); }")

/** The Screen Wake Lock API, where the browser has it. */
@Composable
actual fun KeepScreenOn() {
    DisposableEffect(Unit) {
        val holder = requestWakeLock()
        onDispose { holder?.let(::releaseWakeLock) }
    }
}
