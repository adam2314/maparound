package app.maparound.web

import app.maparound.ui.UserActivity
import app.maparound.ui.DataViewLink
import app.maparound.ui.MapAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import app.maparound.map.satelliteSource
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.async
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
    addressParam("trails")?.let { app.prefs.setTrails(it != "0" && it != "false") }
    addressParam("lang")?.let(Language::of)?.let { app.prefs.setLanguage(it.code) }
}

/** A map the address can name: the built-in online ones, the offline maps, or "the one for here". */
private fun addressableLayer(id: String) = id == TileSources.OFFLINE_ID || id == TileSources.AUTO_ID || TileSources.builtIn.any { it.id == id }

/**
 * The map's layers in the page's address, as the app and the embedded map keep them there
 * ([keepStateInAddress]): `layer=<map>` (TileSources' ids, `offline`, `auto`) and `ov=<what's on
 * over it>` ([MapAddress.Overlay]'s words; `ov=-` for none). Applied as the page opens.
 */
private fun layersFromAddress(app: WebApp) {
    addressParam("layer")?.let(TileSources::current)?.takeIf(::addressableLayer)?.let(app.prefs::setBaseLayer)
    addressParam("ov")?.let { value ->
        val on = MapAddress.overlays(value)
        app.prefs.setTrails(MapAddress.Overlay.TRAILS in on)
        app.prefs.setPlacesOnMap(MapAddress.Overlay.PLACES in on)
        app.prefs.setShowGrid(MapAddress.Overlay.GRID in on)
        app.prefs.setPartyOnMap(MapAddress.Overlay.PARTY in on)
        app.prefs.setContours(MapAddress.Overlay.CONTOURS in on)
        app.prefs.setHillshade(MapAddress.Overlay.SHADING in on)
    }
}

/**
 * Sets the address's parameters [names] to [values] after # ("": taken out), taking them out of
 * the part after ? too, the rest kept (`map=`, the pin). Replaces the address in the history:
 * Back still leaves the page.
 */
private fun setAddressParams(names: JsArray<JsString>, values: JsArray<JsString>): Unit = js(
    """{
    const set = new Map();
    for (let i = 0; i < names.length; i++) set.set(names[i], values[i]);
    const keep = (s) => s.split('&').filter((p) => p && !set.has(p.split('=')[0]));
    const search = keep(location.search.replace(/^\?/, '')).join('&');
    const hash = keep(location.hash.replace(/^#/, ''));
    for (const [k, v] of set) {
        if (v !== '') hash.push(k + '=' + encodeURIComponent(v).replace(/%2C/g, ',').replace(/%3A/g, ':'));
    }
    const url = location.pathname + (search ? '?' + search : '') + (hash.length ? '#' + hash.join('&') : '');
    if (url !== location.pathname + location.search + location.hash) {
        try { history.replaceState(history.state, '', url); } catch (e) {}
    }
}""",
)

/**
 * What's on the map, kept in the page's address as it changes, so a link or a reload shows the
 * same: the map (`layer=`), what's over it (`ov=`), the data view and what's tapped in it (`data=`,
 * `sel=`). Where the map looks (`map=`) and the pin are kept there by the map itself (mapview.js).
 */
private fun CoroutineScope.keepStateInAddress(app: WebApp) {
    val p = app.prefs
    val overlays = combine(p.trails, p.placesOnMap, p.showGrid, p.partyOnMap, combine(p.contours, p.hillshade, ::Pair)) { trails, places, grid, party, terrain ->
        buildSet {
            if (trails) add(MapAddress.Overlay.TRAILS)
            if (places) add(MapAddress.Overlay.PLACES)
            if (grid) add(MapAddress.Overlay.GRID)
            if (party) add(MapAddress.Overlay.PARTY)
            if (terrain.first) add(MapAddress.Overlay.CONTOURS)
            if (terrain.second) add(MapAddress.Overlay.SHADING)
        }
    }
    launch {
        combine(p.baseLayer, overlays, DataViewLink.current, DataViewLink.currentSelected) { layer, on, data, selected ->
            listOf(
                "layer" to TileSources.current(layer).takeIf(::addressableLayer).orEmpty(),
                // Kept even with nothing on ("ov=" alone), so a link with none stays so.
                "ov" to MapAddress.overlays(on).ifEmpty { "-" },
                "data" to data.orEmpty(),
                "sel" to selected?.let(MapAddress::selected).orEmpty(),
            )
        }.collect { params ->
            setAddressParams(params.map { it.first.toJsString() }.toJsArray(), params.map { it.second.toJsString() }.toJsArray())
        }
    }
}

/**
 * A data view in the page's address: `data=<its layers>` ([MapAddress.data]; `data=maps`: how
 * MapAround's maps stand, as the support page embeds it) and `sel=<what's tapped>`. The map opens
 * with it; the address then follows the data view as it changes ([keepStateInAddress]).
 */
private fun dataViewFromAddress() {
    val name = addressParam("data")?.takeIf { it.isNotEmpty() } ?: return
    DataViewLink.pendingSelected = addressParam("sel")?.takeIf { it.isNotEmpty() }?.let(MapAddress::selectedKey)
    DataViewLink.pending.value = name
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

/**
 * Whether the app will start in a right-to-left language (its setting, else the browser's): its
 * letters are fetched at once then. (The settings aren't read yet; the language's key is theirs.)
 */
private fun rtlLikely(embedded: Boolean): Boolean {
    val chosen = if (embedded) "" else runCatching { app.maparound.data.WebStorageStore("prefs").getString("language") }.getOrNull().orEmpty()
    return Language.of(chosen.ifEmpty { app.maparound.shared.util.deviceLanguage() })?.rtl == true
}

/** [f] on every touch, click, wheel or key anywhere on the page (the map's element too). */
private fun onUserActivity(f: () -> Unit): Unit = js(
    """{
    for (const t of ['pointerdown', 'wheel', 'keydown']) window.addEventListener(t, () => f(), { capture: true, passive: true });
}""",
)

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
        // MapLibre GL JS draws online vector maps from their styles (OpenFreeMap's).
        TileSources.vectorMaps = true
        // Embedded in another website: the map alone, its files and settings in memory only.
        val embedded = embeddedFromAddress()
        // The app's own files, all asked for at once (not one after another): the style, the
        // world map and borders (waited for), the regions (set when they come), and in a
        // right-to-left language its letters (waited for before the first text).
        val topo = async { fetchText("themes/topo.xml") }
        val world = async { fetchBytes("world.bin") }
        val borders = async { fetchBytes("borders.bin") }
        val regions = if (embedded) null else async { fetchBytes("regions.bin") }
        val rtlFonts = if (rtlLikely(embedded)) RTL_FONTS.associateWith { async { fetchBytes("uifonts/$it") } } else emptyMap()
        WebFiles.load(keep = !embedded)
        if (!embedded) WebFiles.persist()
        val app = WebApp(
            topoXml = topo.await(),
            world = world.await(),
            borders = borders.await(),
            base = pageBase(),
            embedded = embedded,
        )
        app.startTiles("tiles/tiles-worker.js")
        if (embedded) {
            embedOptions(app)
        } else {
            // Which map is made for where the map looks (the offer of the one for it; regional sources).
            launch { regions?.await()?.let { bytes -> MapRegions.source = { bytes } } }
        }
        pinFromAddress()
        layersFromAddress(app)
        dataViewFromAddress()
        keepStateInAddress(app)
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
        // Its satellite imagery: the user's, the region's own, else Esri's (Settings → Sources).
        launch { snapshotFlow { satelliteSource.urlTemplate }.collect { setImagery3d(it) } }
        onPageHide { app.onHide() }
        onUserActivity { UserActivity.touched() }
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
                    val bytes = rtlFonts[name]?.await() ?: fetchBytes("uifonts/$name")
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
