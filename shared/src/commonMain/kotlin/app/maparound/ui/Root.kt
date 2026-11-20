package app.maparound.ui

import app.maparound.stats.LocalStats
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import app.maparound.i18n.T
import app.maparound.i18n.I18n
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
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
    /**
     * The tabs in a side drawer instead of the bar at the bottom (the web app): its ≡ is on the
     * map, with the data view first, then the other tabs and Settings; each other tab goes back to
     * the map with its top bar's arrow.
     */
    sideMenu: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
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
    CompositionLocalProvider(
        LocalLayoutDirection provides if (I18n.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalBackToMap provides if (sideMenu && tab != Tab.Map) ({ tab = Tab.Map }) else null,
    ) {
    MenuDrawer(
        enabled = sideMenu,
        state = drawer,
        tabs = tabs,
        current = tab,
        onData = {
            DataViewLink.showExplorer.value = true
            tab = Tab.Map
        },
        onTab = { tab = it },
        onSettings = { settingsOpen = true },
    ) {
    Scaffold(
        // Every touch counts for "the screen stays on N minutes after the last touch".
        modifier = Modifier.watchTouches(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Navigating on the map: the map has the whole screen. One tab (the embedded map): no bar.
            if (!sideMenu && !(navigating && tab == Tab.Map) && tabs.size > 1) NavigationBar {
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Map -> MapScreen(
                    hasLocation = hasLocation,
                    requestLocation = requestLocation,
                    // Platforms without the Offline tab yet (the web app) don't offer to go there.
                    onOpenOffline = if (Tab.Offline in tabs) ({ tab = Tab.Offline }) else null,
                    snackbar = snackbar,
                    createMap = createMap,
                    mapView = mapView,
                    view3d = view3d,
                    openFull = openFull,
                    onMenu = if (sideMenu) ({ drawerScope.launch { drawer.open() } }) else null,
                )
                Tab.Routes -> LibraryScreen(hasLocation, requestLocation, snackbar, onShowMap = { tab = Tab.Map })
                Tab.Party -> PartyScreen(hasLocation, requestLocation, snackbar, onShowMap = { tab = Tab.Map })
                Tab.Offline -> OfflineMapsScreen(snackbar = snackbar, onShowOnMap = { tab = Tab.Map })
                Tab.Sensors -> SensorsScreen(hasLocation, requestLocation, satellites = satellites?.let { s -> { s(hasLocation, requestLocation) } })
            }
        }
    }
    }
    if (settingsOpen) SettingsScreen(onDismiss = { settingsOpen = false })
    }
}

/** On the other tabs, with the side menu (the web app): back to the map, for their top bar's arrow ([BackToMapButton]). */
val LocalBackToMap = compositionLocalOf<(() -> Unit)?> { null }

/** The arrow back to the map, in a tab's top bar, where the tabs are in a side menu. */
@Composable
fun BackToMapButton() {
    val back = LocalBackToMap.current ?: return
    Tip(T.common.backToMap) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = T.common.backToMap) } }
}

/**
 * The side menu ([enabled]: else just [content]): MapAround's name, the data view, the tabs but
 * the map ([current] marked), and Settings. It opens from the map's ≡ only (a swipe on the map
 * moves the map).
 */
@Composable
private fun MenuDrawer(
    enabled: Boolean,
    state: DrawerState,
    tabs: List<Tab>,
    current: Tab,
    onData: () -> Unit,
    onTab: (Tab) -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) return content()
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        action()
        scope.launch { state.close() }
    }
    ModalNavigationDrawer(
        drawerState = state,
        gesturesEnabled = state.isOpen,
        drawerContent = {
            ModalDrawerSheet(Modifier.widthIn(max = 300.dp)) {
                Text(
                    "MapAround",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 16.dp),
                )
                if (LocalStats.current != null) {
                    MenuItem(Icons.Filled.Layers, T.stats.dataView, selected = false) { pick(onData) }
                }
                tabs.filter { it != Tab.Map }.forEach { t -> MenuItem(t.icon, t.label, selected = t == current) { pick { onTab(t) } } }
                HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                MenuItem(Icons.Filled.Settings, T.common.settings, selected = false) { pick(onSettings) }
            }
        },
        content = content,
    )
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
