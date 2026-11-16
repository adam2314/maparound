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
import app.maparound.data.ElevationSources
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.map.LocalMapThemes
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.party.LocalParty
import app.maparound.party.PartyKey
import app.maparound.party.LocalPartyPlatform
import app.maparound.stats.LocalStats
import app.maparound.places.LocalPlaces
import app.maparound.raster.LocalRasters
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.share.Incoming
import app.maparound.share.Shared
import app.maparound.share.SharedLinks
import app.maparound.share.TrackLink
import app.maparound.i18n.Language
import app.maparound.i18n.T
import app.maparound.map.TileSources
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

/** The page's address up to its folder, e.g. "https://maparound.app/app/". */
private fun pageBase(): String = js("location.href.split('#')[0].split('?')[0].replace(/[^/]*$/, '')")

private fun pageAddress(): String = js("location.href")

/** A parameter of the page's address (after ? or after #): its value, "" when it has none, null when absent. */
private fun addressParam(name: String): String? = js(
    """(() => {
    for (const part of [location.search, location.hash]) {
        for (const p of part.replace(/^[?#]/, '').split('&')) {
            const i = p.indexOf('=');
            if ((i < 0 ? p : p.substring(0, i)) !== name) continue;
            try { return decodeURIComponent(i < 0 ? '' : p.substring(i + 1)); } catch (e) { return ''; }
        }
    }
    return null;
})()""",
)

/**
 * The map embedded in another website: `?embed` in the address (an iframe's). Only the map, nothing
 * read from or saved to the browser's storage, and a button that opens it in the app.
 */
private fun embeddedFromAddress(): Boolean = addressParam("embed").let { it != null && it != "0" && it != "false" }

/**
 * The embedded map's own options in its address: `layer=<online map>` (TileSources' ids),
 * `trails=0` (without the marked trails, shown otherwise), `lang=<language>` (else the browser's).
 * Kept for this visit only.
 */
private fun embedOptions(app: WebApp) {
    addressParam("layer")?.let(TileSources::current)?.takeIf { id -> TileSources.builtIn.any { it.id == id } }
        ?.let(app.prefs::setBaseLayer)
    addressParam("trails")?.let { app.prefs.setTrails(it != "0" && it != "false") }
    addressParam("lang")?.let(Language::of)?.let { app.prefs.setLanguage(it.code) }
}

/**
 * Opens the embedded map in MapAround's web app, in a new tab: the same view, pin and track (from
 * the address), with the visitor's own settings (the embed's options left out).
 */
private fun openFullApp(): Unit = js(
    """{
    const drop = ['embed', 'layer', 'trails', 'lang'];
    const keep = (s) => s.split('&').filter((p) => p && !drop.includes(p.split('=')[0])).join('&');
    const search = keep(location.search.replace(/^\?/, ''));
    const hash = keep(location.hash.replace(/^#/, ''));
    window.open(location.origin + location.pathname + (search ? '?' + search : '') + (hash ? '#' + hash : ''), '_blank', 'noopener');
}""",
)

/**
 * A pinpoint in the page's address, openstreetmap.org's ?mlat=…&mlon=… (as MapAround shares a
 * point): the map opens with a pin there. (mapview.js keeps the address's pin up to date.)
 */
private fun pinFromAddress() {
    val address = pageAddress().takeIf { "mlat=" in it } ?: return
    (SharedLinks.parse(address) as? Shared.Point)?.let { Incoming.pending.value = Incoming.Item.Point(it) }
}

/**
 * A party invite in the page's address (`#party=<code>?n=<name>`, from the invite page, with
 * `&s=<server>` for a party on its own server), or "". It's taken out of the address: the code is
 * the party's secret.
 */
private fun takeInviteFromAddress(): String = js(
    """(() => {
    const parts = location.hash.replace(/^#/, '').split('&');
    const i = parts.findIndex((p) => p.startsWith('party='));
    if (i < 0) return '';
    let invite = parts[i].substring(6);
    parts.splice(i, 1);
    const s = parts.findIndex((p) => p.startsWith('s='));
    if (s >= 0) { invite += '&' + parts[s]; parts.splice(s, 1); }
    const rest = parts.filter((p) => p).join('&');
    try { history.replaceState(history.state, '', location.pathname + location.search + (rest ? '#' + rest : '')); } catch (e) {}
    return invite;
})()""",
)

/** Takes the address's parameters [names] out of it (after # and after ?), keeping the rest. */
private fun removeFromAddress(names: JsArray<JsString>): Unit = js(
    """{
    const drop = (s) => s.split('&').filter((p) => p && !names.includes(p.split('=')[0])).join('&');
    const hash = drop(location.hash.replace(/^#/, ''));
    const search = drop(location.search.replace(/^\?/, ''));
    try { history.replaceState(history.state, '', location.pathname + (search ? '?' + search : '') + (hash ? '#' + hash : '')); } catch (e) {}
}""",
)

private fun removeFromAddress(vararg names: String) = removeFromAddress(names.map { it.toJsString() }.toJsArray())

/** The embedded map's track or GPX link last shown (its address keeps it). */
private var embeddedLink: String? = null

/**
 * A route or track in the page's address (a MapAround track link, `#track=…&n=…`) or a GPX file
 * (`?gpx=<link>`): into Library and onto the map, like an imported file, then out of the address.
 * The GPX file is fetched by the browser, so only from websites that allow that (CORS).
 * The embedded map keeps it in the address (for "Open in MapAround") and nothing is saved; another
 * view in its address doesn't open the same link again.
 */
private suspend fun linkFromAddress(app: WebApp) {
    val address = pageAddress()
    if (app.embedded) {
        val link = listOf("track", "n", "gpx").joinToString("&") { addressParam(it).orEmpty() }
        if (link == embeddedLink) return
        embeddedLink = link
    }
    TrackLink.parse(address)?.let { track ->
        if (!app.embedded) removeFromAddress("track", "n")
        Incoming.pending.value = Incoming.Item.LinkedTrack(track)
        return
    }
    val url = SharedLinks.gpxLink(address)?.takeIf { "gpx=" in address } ?: return
    if (!app.embedded) removeFromAddress("gpx")
    Incoming.pending.value = Incoming.Item.Message(T.map.gpxLinkOpening)
    val bytes = fetchBytes(url)
    Incoming.pending.value = if (bytes == null) {
        Incoming.Item.Message(T.map.gpxLinkFailed(if (app.embedded) T.map.gpxLinkBlockedEmbed else T.map.gpxLinkBlocked))
    } else {
        runCatching { Incoming.importGpx(bytes, SharedLinks.gpxName(url), app.tracks, app.places) }
            .getOrElse { Incoming.Item.Message(T.map.couldntReadGpx(it.message)) }
    }
}

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
        // Embedded in another website: the map alone, its files and settings in memory only.
        val embedded = embeddedFromAddress()
        WebFiles.load(keep = !embedded)
        if (!embedded) WebFiles.persist()
        val app = WebApp(
            topoXml = fetchText("themes/topo.xml"),
            world = fetchBytes("world.bin"),
            borders = fetchBytes("borders.bin"),
            base = pageBase(),
            embedded = embedded,
        )
        app.startTiles("tiles/tiles-worker.js")
        if (embedded) {
            embedOptions(app)
        } else {
            // Which map is made for where the map looks (the offer of the one for it).
            val regions = fetchBytes("regions.bin")
            MapRegions.source = { regions }
        }
        pinFromAddress()
        if (!embedded) inviteFromAddress(app)
        linkFromAddress(app)
        onAddressChange {
            if (!embedded) inviteFromAddress(app)
            launch { linkFromAddress(app) }
        }
        if (!embedded) notifyPartyEvents(app.party)
        // The 3D view's terrain tiles: the default, or the ones chosen in Offline maps.
        launch {
            app.prefs.terrain3dSource.collect { s ->
                setTerrain3dSource(s?.template ?: ElevationSources.DEFAULT_3D, s?.mapbox == true)
            }
        }
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
        LocalStats provides app.stats,
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
                // No Sensors tab: the browser's position is for the map ("my location"). Embedded: the map alone.
                tabs = if (app.embedded) listOf(Tab.Map) else listOf(Tab.Map, Tab.Routes, Tab.Party, Tab.Offline),
                openFull = if (app.embedded) ::openFullApp else null,
            )
        }
    }
}
