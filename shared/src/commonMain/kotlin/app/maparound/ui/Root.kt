package app.maparound.ui

import app.maparound.stats.LocalStats
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import app.maparound.i18n.T
import app.maparound.i18n.I18n
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import app.maparound.map.MapSurface
import app.maparound.party.LocalParty
import app.maparound.party.PartyEvent
import app.maparound.share.Incoming
import kotlinx.coroutines.flow.Flow

enum class Tab(val icon: ImageVector) {
    Map(Icons.Filled.Map),
    Routes(Icons.Filled.Bookmarks),
    Party(Icons.Filled.Groups),
    Offline(Icons.Filled.DownloadForOffline),
    Sensors(Icons.Filled.Explore),
    ;

    /** Its name in the app's language. */
    val label: String
        get() = when (this) {
            Map -> T.common.tabMap
            Routes -> T.common.tabLibrary
            Party -> T.common.tabParty
            Offline -> T.common.tabOffline
            Sensors -> T.common.tabSensors
        }
}

/**
 * The app: tabs for the map, library, party, offline maps and sensors. The platform gives the
 * location permission state, its background messages, start-up work and its own views.
 *
 * The screen's size picks the layout, the same on every platform: on a phone the tabs are in a bar
 * along the bottom, each in place of the map; on a big screen (a tablet, an unfolded foldable, a
 * computer's browser) they're in a rail down the side of the map ([SideRail]), each opening in a
 * panel beside the map, which stays in view.
 */
@Composable
fun MapAroundRoot(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    /** Messages for the snackbar (downloads finished or failed…). */
    messages: Flow<String>,
    /** Runs once at start: finish downloads, recover an interrupted recording, resume sharing. */
    onStart: suspend () -> Unit,
    createMap: () -> MapSurface,
    mapView: @Composable (MapSurface, Modifier) -> Unit,
    view3d: @Composable (Scene3DViewArgs) -> Unit,
    /** The Satellites tab (Android only). */
    satellites: (@Composable (hasLocation: Boolean, requestLocation: () -> Unit) -> Unit)?,
    /** The tabs this platform has so far. */
    tabs: List<Tab> = Tab.entries,
    initialTab: Tab = Tab.Map,
    /**
     * The map embedded in another website (the web app's `?embed`): only the map, and this opens it
     * in MapAround itself. Null in the app.
     */
    openFull: (() -> Unit)? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var settingsOpen by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    val navigating = app.maparound.routing.NavigationSession.state.collectAsState().value.active
    val party = LocalParty.current
    // Messages from downloads and other background work.
    LaunchedEffect(Unit) { messages.collect { snackbar.showSnackbar(it) } }
    // Finish what was going on before the app was closed (downloads, recordings, party sharing).
    LaunchedEffect(Unit) { onStart() }
    val incoming by Incoming.pending.collectAsState()
    LaunchedEffect(incoming) {
        when (val i = incoming) {
            null -> Unit
            is Incoming.Item.Message -> {
                Incoming.consume()
                snackbar.showSnackbar(i.text)
            }
            else -> tab = Tab.Map // the map screen shows it
        }
    }
    // Party: open invites, announce joins and alerts.
    val invite by party.pendingInvite.collectAsState()
    LaunchedEffect(invite) { if (invite != null) tab = Tab.Party }
    LaunchedEffect(Unit) {
        party.events.collect { ev ->
            when (ev) {
                is PartyEvent.Joined -> snackbar.showSnackbar(T.party.joinedParty(ev.member.displayName))
                is PartyEvent.SosRaised -> snackbar.showSnackbar(T.party.sosFrom(ev.member.displayName, ev.text))
                is PartyEvent.SosCancelled -> snackbar.showSnackbar(T.party.sosCancelled(ev.member.displayName))
                is PartyEvent.Left -> snackbar.showSnackbar(T.party.leftParty(ev.member.displayName))
                else -> Unit
            }
        }
    }

    // Back on another tab goes to the map (as Android's own apps do); on the map it leaves the app.
    // The screens' own Back (a folder, a settings page, search) comes first: they're composed later.
    PlatformBackHandler(enabled = tab != Tab.Map && Tab.Map in tabs) { tab = Tab.Map }

    // The screen stays on as chosen in Settings (not in another website's page).
    if (openFull == null) ScreenKeeper(navigating)

    // Right to left in a right-to-left language (Hebrew), on every platform: the screens mirror.
    CompositionLocalProvider(LocalLayoutDirection provides if (I18n.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // A big screen: the rail, and the tabs and Settings in a panel beside the map. Navigating, the
    // map has the whole screen (as the bottom bar goes on a phone). One tab (the embedded map): neither.
    val big = tabs.size > 1 && maxWidth >= RAIL_MIN_WIDTH && maxHeight >= RAIL_MIN_HEIGHT
    val rail = big && !navigating
    // Wide enough, the panel goes beside the map; narrower (a tablet upright, a foldable), it lies over the map's side.
    val beside = maxWidth >= PANEL_BESIDE_WIDTH
    // With the map beside the panel, "show on map" leaves the panel open: the map moves next to it.
    val toMap = { if (!big) tab = Tab.Map }
    if (big) {
        // Navigating: the map has the room. A tab asked for (an invite, the map's "Get the map"): Settings gives way.
        LaunchedEffect(navigating) { if (navigating) { tab = Tab.Map; settingsOpen = false } }
        LaunchedEffect(tab) { if (tab != Tab.Map) settingsOpen = false }
    }
    @Composable
    fun TabScreen(t: Tab) {
        when (t) {
            Tab.Map -> Unit
            Tab.Routes -> LibraryScreen(hasLocation, requestLocation, snackbar, onShowMap = toMap)
            Tab.Party -> PartyScreen(hasLocation, requestLocation, snackbar, onShowMap = toMap)
            Tab.Offline -> OfflineMapsScreen(snackbar = snackbar, onShowOnMap = toMap)
            Tab.Sensors -> SensorsScreen(hasLocation, requestLocation, satellites = satellites?.let { s -> { s(hasLocation, requestLocation) } })
        }
    }
    // Each panel keeps its place (a folder open, how far down) while another is shown.
    val panels = rememberSaveableStateHolder()
    @Composable
    fun Panel(floating: Boolean, modifier: Modifier = Modifier) {
        if (settingsOpen) {
            SidePanel("settings", panels, floating, modifier, onClose = { settingsOpen = false }) { SettingsPanel(onClose = { settingsOpen = false }) }
        } else if (tab != Tab.Map) {
            SidePanel(tab.name, panels, floating, modifier, onClose = { tab = Tab.Map }) { TabScreen(tab) }
        }
    }
    Scaffold(
        // Every touch counts for "the screen stays on N minutes after the last touch".
        modifier = Modifier.watchTouches(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // A phone's tabs. Navigating on the map: the map has the whole screen.
            if (!big && !(navigating && tab == Tab.Map) && tabs.size > 1) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) },
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (rail) {
                val dataOn = DataViewLink.current.collectAsState().value != null
                SideRail(
                    tabs = tabs,
                    open = tab.takeIf { it != Tab.Map && !settingsOpen },
                    settingsOpen = settingsOpen,
                    dataOn = dataOn,
                    // The data view (the web app's): on the map, with its explorer.
                    onData = if (LocalStats.current != null) ({
                        settingsOpen = false
                        tab = Tab.Map
                        DataViewLink.showExplorer.value = true
                    }) else null,
                    // The open one again closes it.
                    onTab = { t ->
                        tab = if (tab == t && !settingsOpen) Tab.Map else t
                        settingsOpen = false
                    },
                    onSettings = {
                        settingsOpen = !settingsOpen
                        tab = Tab.Map
                    },
                )
                if (beside) Panel(floating = false)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val shown = if (big) Tab.Map else tab
                if (shown == Tab.Map) MapScreen(
                    hasLocation = hasLocation,
                    requestLocation = requestLocation,
                    // Platforms without the Offline tab yet don't offer to go there.
                    onOpenOffline = if (Tab.Offline in tabs) ({ tab = Tab.Offline }) else null,
                    snackbar = snackbar,
                    createMap = createMap,
                    mapView = mapView,
                    view3d = view3d,
                    openFull = openFull,
                ) else TabScreen(shown)
                if (rail && !beside) Panel(floating = true, Modifier.align(Alignment.TopStart))
            }
        }
    }
    // On a phone, Settings is over the whole screen (from the gear in the tabs' top bars).
    if (settingsOpen && !big) SettingsScreen(onDismiss = { settingsOpen = false })
    }
    }
}

/** In the side panel beside the map (a big screen): closes it, for its top bar's ✕ ([ClosePanelButton]). */
val LocalClosePanel = compositionLocalOf<(() -> Unit)?> { null }

/** The ✕ that closes a tab's side panel, in its top bar; nothing on a phone, where the tab is the whole screen. */
@Composable
fun ClosePanelButton() {
    val close = LocalClosePanel.current ?: return
    Tip(T.common.close) { IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = T.common.close) } }
}

/**
 * From this size the tabs are in a rail at the map's side ([SideRail]), not a bar along the bottom:
 * a tablet, an unfolded foldable, a computer's browser window. A phone, also turned sideways (it's
 * low then), keeps the bar.
 */
private val RAIL_MIN_WIDTH = 600.dp
private val RAIL_MIN_HEIGHT = 480.dp

/** From this width the side panel goes beside the map (which keeps 360 dp or more); narrower, over its side. */
private val PANEL_BESIDE_WIDTH = 840.dp

/** The side panel's width: about a phone's, which the tabs are made for. */
private val PANEL_WIDTH = 400.dp

/**
 * The tabs on a big screen, down the side of the map: the data view (where there is one), the tabs
 * but the map ([open] marked) and, at the foot, Settings.
 */
@Composable
private fun SideRail(
    tabs: List<Tab>,
    open: Tab?,
    settingsOpen: Boolean,
    dataOn: Boolean,
    onData: (() -> Unit)?,
    onTab: (Tab) -> Unit,
    onSettings: () -> Unit,
) {
    // The Scaffold's padding already keeps it clear of the system bars.
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer, windowInsets = WindowInsets(0, 0, 0, 0)) {
        Spacer(Modifier.height(8.dp))
        if (onData != null) RailItem(Icons.Filled.Layers, T.stats.dataView, dataOn, onData)
        tabs.filter { it != Tab.Map }.forEach { t -> RailItem(t.icon, t.label, t == open) { onTab(t) } }
        Spacer(Modifier.weight(1f))
        RailItem(Icons.Filled.Settings, T.common.settings, settingsOpen, onSettings)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * A tab (or Settings) at the map's side, next to the rail: beside the map, or [floating] over its
 * side (the map keeps its width). [key] keeps each one's place.
 */
@Composable
private fun SidePanel(
    key: String,
    holder: SaveableStateHolder,
    floating: Boolean,
    modifier: Modifier,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(modifier.width(PANEL_WIDTH).fillMaxHeight(), shadowElevation = if (floating) 8.dp else 0.dp) {
        CompositionLocalProvider(LocalClosePanel provides onClose) {
            holder.SaveableStateProvider(key) { content() }
        }
    }
    if (!floating) VerticalDivider()
}
