package app.maparound.web

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import app.maparound.i18n.I18n
import app.maparound.map.MapRegions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.map.LocalMapThemes
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.party.LocalParty
import app.maparound.party.PartyKey
import app.maparound.party.LocalPartyPlatform
import app.maparound.places.LocalPlaces
import app.maparound.raster.LocalRasters
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.share.Incoming
import app.maparound.share.Shared
import app.maparound.share.SharedLinks
import app.maparound.shared.WebFiles
import app.maparound.shared.toByteArray
import app.maparound.tracks.LocalTracks
import app.maparound.ui.LocalPlatform
import app.maparound.ui.LocalRecordingControl
import app.maparound.ui.LocalSearcher
import app.maparound.ui.MapAroundRoot
import app.maparound.ui.Tab
import app.maparound.ui.theme.MapAroundTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private fun jsFetchText(url: String, done: (String?) -> Unit): Unit =
    js("{ fetch(url).then(r => r.ok ? r.text() : null).then(t => done(t), () => done(null)); }")

private fun jsFetchBytes(url: String, done: (JsAny?) -> Unit): Unit =
    js("{ fetch(url).then(r => r.ok ? r.arrayBuffer() : null).then(b => done(b ? new Uint8Array(b) : null), () => done(null)); }")

private suspend fun fetchText(url: String): String? = suspendCoroutine { c -> jsFetchText(url) { c.resume(it) } }

private suspend fun fetchBytes(url: String): ByteArray? = suspendCoroutine { c -> jsFetchBytes(url) { c.resume(it?.toByteArray()) } }

private val UI_FONTS = listOf("NotoSansMath.ttf", "NotoSansSymbols2.ttf", "NotoSansHebrew.ttf", "NotoSansArabic.ttf")

/** The right-to-left languages' fonts, loaded before the screens in such a language. */
private val RTL_FONTS = listOf("NotoSansHebrew.ttf", "NotoSansArabic.ttf")

/** The page's address up to its folder, e.g. "https://adam2314.github.io/maparound/app/". */
private fun pageBase(): String = js("location.href.split('#')[0].split('?')[0].replace(/[^/]*$/, '')")

private fun pageAddress(): String = js("location.href")

/**
 * A pinpoint in the page's address, openstreetmap.org's ?mlat=…&mlon=… (as MapAround shares a
 * point): the map opens with a pin there. (mapview.js keeps the address's pin up to date.)
 */
private fun pinFromAddress() {
    val address = pageAddress().takeIf { "mlat=" in it } ?: return
    (SharedLinks.parse(address) as? Shared.Point)?.let { Incoming.pending.value = Incoming.Item.Point(it) }
}

/**
 * A party invite in the page's address (`#party=<code>?n=<name>`, from the invite page), or "".
 * It's taken out of the address: the code is the party's secret.
 */
private fun takeInviteFromAddress(): String = js(
    """(() => {
    const parts = location.hash.replace(/^#/, '').split('&');
    const i = parts.findIndex((p) => p.startsWith('party='));
    if (i < 0) return '';
    const invite = parts[i].substring(6);
    parts.splice(i, 1);
    const rest = parts.filter((p) => p).join('&');
    try { history.replaceState(history.state, '', location.pathname + location.search + (rest ? '#' + rest : '')); } catch (e) {}
    return invite;
})()""",
)

/** [f] when another address is typed or opened in this page (the part after #). */
private fun onAddressChange(f: () -> Unit): Unit = js("{ window.addEventListener('hashchange', () => f()); }")

/** An invite in the address opens the party screen, which asks before joining. */
private fun inviteFromAddress(app: WebApp) {
    takeInviteFromAddress().takeIf { it.isNotEmpty() }?.let(PartyKey::parse)?.let { app.party.pendingInvite.value = it }
}

private fun removeLoading(): Unit = js("{ const l = document.getElementById('loading'); if (l) l.remove(); }")

/** [f] when the page is hidden (another tab, the phone locked) or closed. */
private fun onPageHide(f: () -> Unit): Unit = js(
    """{
    window.addEventListener('pagehide', () => f());
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') f(); });
}""",
)

/** Starts the app: saved files, the style and world map, then the screens. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        WebFiles.load()
        WebFiles.persist()
        val app = WebApp(
            topoXml = fetchText("themes/topo.xml"),
            world = fetchBytes("world.bin"),
            borders = fetchBytes("borders.bin"),
            base = pageBase(),
        )
        app.startTiles("tiles/tiles-worker.js")
        // Which map is made for where the map looks (the offer of the one for it).
        val regions = fetchBytes("regions.bin")
        MapRegions.source = { regions }
        pinFromAddress()
        inviteFromAddress(app)
        onAddressChange { inviteFromAddress(app) }
        notifyPartyEvents(app.party)
        onPageHide { app.onHide() }
        removeLoading()
        ComposeViewport("app") {
            // Fallback fonts for what the built-in one lacks (arrows, symbols, Hebrew, Arabic), loaded after the first frame.
            // In a right-to-left language its letters come first, before any text: text laid out
            // before its font arrives keeps empty boxes.
            val fonts = LocalFontFamilyResolver.current
            val first = if (I18n.rtl) RTL_FONTS else emptyList()
            var ready by remember { mutableStateOf(first.isEmpty()) }
            LaunchedEffect(Unit) {
                for (name in first + UI_FONTS.filterNot { it in first }) {
                    val bytes = fetchBytes("uifonts/$name")
                    if (bytes != null) runCatching { fonts.preload(FontFamily(Font(name, bytes))) }
                    if (name == first.lastOrNull()) ready = true
                }
                ready = true
            }
            if (ready) WebRoot(app)
        }
    }
}

/** The whole app: the shared screens with the browser's services. */
@Composable
fun WebRoot(app: WebApp) {
    val hasLocation by app.sensors.permission.collectAsState()
    CompositionLocalProvider(
        LocalPrefs provides app.prefs,
        LocalDeviceSensors provides app.sensors,
        LocalPlaces provides app.places,
        LocalPlatform provides WebPlatformActions,
        LocalSearcher provides app.searcher,
        LocalTracks provides app.tracks,
        LocalRecordingControl provides PendingRecording,
        LocalRasters provides PendingRasters,
        LocalMapThemes provides app.themes,
        LocalParty provides app.party,
        LocalPartyPlatform provides WebPartyPlatform,
        LocalOfflineData provides app.offline,
    ) {
        MapAroundTheme {
            MapAroundRoot(
                hasLocation = hasLocation,
                requestLocation = app.sensors::requestPermission,
                messages = emptyFlow(),
                onStart = {},
                createMap = { app.newMap() },
                mapView = { map, modifier -> WebMapView((map as MapLibreSurface).native as WebNativeMap, modifier) },
                view3d = { args -> WebScene3DView(args) },
                satellites = null,
                // No Sensors tab: the browser's position is for the map ("my location").
                tabs = listOf(Tab.Map, Tab.Routes, Tab.Party, Tab.Offline),
            )
        }
    }
}
