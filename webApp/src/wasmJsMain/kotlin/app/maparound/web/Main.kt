package app.maparound.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.map.LocalMapThemes
import app.maparound.map.maplibre.MapLibreSurface
import app.maparound.party.LocalParty
import app.maparound.party.LocalPartyPlatform
import app.maparound.places.LocalPlaces
import app.maparound.raster.LocalRasters
import app.maparound.sensors.LocalDeviceSensors
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

/** The page's address up to its folder, e.g. "https://adam2314.github.io/maparound/app/". */
private fun pageBase(): String = js("location.href.split('#')[0].split('?')[0].replace(/[^/]*$/, '')")

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
        onPageHide { app.onHide() }
        removeLoading()
        ComposeViewport("app") {
            // Fallback fonts for what the built-in one lacks (arrows, symbols, Hebrew, Arabic), loaded after the first frame.
            val fonts = LocalFontFamilyResolver.current
            LaunchedEffect(Unit) {
                for (name in UI_FONTS) {
                    val bytes = fetchBytes("uifonts/$name") ?: continue
                    runCatching { fonts.preload(FontFamily(Font(name, bytes))) }
                }
            }
            WebRoot(app)
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
        LocalParty provides PendingParty,
        LocalPartyPlatform provides PendingPartyPlatform,
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
                view3d = { args ->
                    Box(args.modifier, contentAlignment = Alignment.Center) {
                        Text(
                            "The 3D view comes to the web app in a later version",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                },
                satellites = null,
                // Offline map downloads and the party come in later steps.
                tabs = listOf(Tab.Map, Tab.Routes, Tab.Sensors),
            )
        }
    }
}
