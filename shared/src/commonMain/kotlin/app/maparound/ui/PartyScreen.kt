package app.maparound.ui

import app.maparound.party.PartyRelays
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.filled.Dns
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.History
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import app.maparound.data.LocalPrefs
import app.maparound.sensors.SpeedUnit
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.i18n.Texts
import app.maparound.i18n.ltr
import app.maparound.party.LocalParty
import app.maparound.party.LocalPartyPlatform
import app.maparound.party.Member
import app.maparound.party.PartyKey
import app.maparound.party.PartyState
import app.maparound.party.TransportStatus
import app.maparound.party.Via
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.routing.MapFocus
import app.maparound.routing.PlannerSession
import app.maparound.sensors.GeoFix
import app.maparound.shared.util.nowMillis
import app.maparound.tools.Geodesy
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartyScreen(
    hasLocation: Boolean,
    requestLocation: () -> Unit,
    snackbar: SnackbarHostState,
    onShowMap: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val party = LocalParty.current
    val partyPlatform = LocalPartyPlatform.current
    val s by party.state.collectAsState()
    val pending by party.pendingInvite.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    var radioPicker by remember { mutableStateOf(false) }
    var confirmSos by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var editMe by remember { mutableStateOf(false) }

    // Notifications show SOS alerts; sharing works without them.
    val ensureNotifications = partyPlatform.rememberNotificationPermission()
    val scan = partyPlatform.rememberQrScanner { text ->
        val key = PartyKey.parse(text)
        if (key == null) scope.launch { snackbar.showSnackbar(T.party.notAPartyQr) }
        else party.pendingInvite.value = key
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.party?.name ?: T.party.title) },
            actions = {
                if (s.party != null && !party.watchOnly) {
                    Tip(T.party.invite) { IconButton(onClick = { showQr = true }) { Icon(Icons.Filled.QrCode, contentDescription = T.party.invite) } }
                }
                SettingsButton()
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (s.party == null && party.watchOnly) {
            WatchNoParty(onLink = { text ->
                val key = PartyKey.parse(text)
                if (key == null) scope.launch { snackbar.showSnackbar(T.party.invalidLink) }
                else party.pendingInvite.value = key
            })
        } else if (s.party == null) {
            NoParty(myName = s.look?.name.orEmpty(), onCreate = { partyName, me ->
                if (!hasLocation) requestLocation()
                ensureNotifications()
                party.create(partyName, me)
                showQr = true
            }, onScan = scan.takeIf { partyPlatform.canScanQr }, onLink = { text ->
                val key = PartyKey.parse(text)
                if (key == null) scope.launch { snackbar.showSnackbar(T.party.invalidLink) }
                else party.pendingInvite.value = key
            })
        } else {
            InParty(
                s,
                onToggleActive = {
                    if (it) {
                        if (!hasLocation) requestLocation()
                        ensureNotifications()
                    }
                    party.setActive(it)
                },
                onRadio = { radioPicker = true },
                onSos = { if (s.mySos) party.sos(false) else confirmSos = true },
                onEditMe = { editMe = true },
                onLeave = { confirmLeave = true },
                onShowMember = { p ->
                    MapFocus.show(listOf(p))
                    onShowMap()
                },
                onRouteTo = { p ->
                    val me = s.myPosition
                    PlannerSession.close()
                    PlannerSession.open()
                    if (me != null) PlannerSession.add(GeoPoint(me.lat, me.lon))
                    PlannerSession.add(p)
                    MapFocus.show(listOfNotNull(me?.let { GeoPoint(it.lat, it.lon) }, p))
                    onShowMap()
                },
                // Their way so far, replayed on the map.
                onHistory = { m ->
                    PartyReplay.open(m, party.history(m.id))
                    onShowMap()
                },
            )
        }
    }

    if (party.watchOnly) pending?.let { key ->
        AlertDialog(
            onDismissRequest = { party.pendingInvite.value = null },
            title = { Text(T.party.watchQuestion(key.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.party.watchExplanation)
                    key.server?.let { Text(T.party.onOwnServer(PartyRelays.host(it)), style = MaterialTheme.typography.bodySmall) }
                    if (s.party != null) Text(T.party.stopWatchingWarning(s.party!!.name), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    party.pendingInvite.value = null
                    party.join(key, "")
                }) { Text(T.party.watch) }
            },
            dismissButton = { TextButton(onClick = { party.pendingInvite.value = null }) { Text(T.common.cancel) } },
        )
    } else pending?.let { key ->
        var name by remember(key) { mutableStateOf(s.me?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { party.pendingInvite.value = null },
            title = { Text(T.party.joinQuestion(key.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.party.joinExplanation)
                    key.server?.let { Text(T.party.onOwnServer(PartyRelays.host(it)), style = MaterialTheme.typography.bodySmall) }
                    if (s.party != null) Text(T.party.leaveWarning(s.party!!.name), color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(name, { name = it }, label = { Text(T.party.yourName) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    party.pendingInvite.value = null
                    if (!hasLocation) requestLocation()
                    ensureNotifications()
                    party.join(key, name)
                }) { Text(T.party.join) }
            },
            dismissButton = { TextButton(onClick = { party.pendingInvite.value = null }) { Text(T.common.cancel) } },
        )
    }

    if (showQr) s.party?.let { InviteDialog(it, onDismiss = { showQr = false }) }
    if (radioPicker) partyPlatform.RadioPicker(onPick = { addr, name -> radioPicker = false; party.setRadio(addr, name) }, onDismiss = { radioPicker = false })
    if (editMe) {
        s.me?.let { me ->
            EditMeDialog(me.name, me.color, me.icon, onDismiss = { editMe = false }) { n, c, i ->
                editMe = false
                party.setProfile(n, c, i)
            }
        }
    }

    if (confirmSos) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            title = { Text(T.party.sendSosQuestion) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.party.sosExplanation)
                    OutlinedTextField(text, { text = it }, label = { Text(T.party.sosWhatHappened) }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmSos = false; party.sos(true, text) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(T.party.sendSos) }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text(T.common.cancel) } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (party.watchOnly) T.party.stopWatchingQuestion else T.party.leaveQuestion) },
            text = {
                Text(
                    if (party.watchOnly) T.party.watchAgainHint
                    else T.party.leaveExplanation,
                )
            },
            confirmButton = { TextButton(onClick = { confirmLeave = false; party.leave() }) { Text(if (party.watchOnly) T.common.stop else T.party.leave) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(T.common.cancel) } },
        )
    }
}

/** No party watched (the web app): an invite link opens one. */
@Composable
private fun WatchNoParty(onLink: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                T.party.watchIntro,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.party.watchAParty, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(link, { link = it }, label = { Text(T.party.inviteLink) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onLink(link) }, enabled = link.isNotBlank()) { Text(T.party.watch) }
                }
            }
        }
    }
}

@Composable
private fun NoParty(myName: String, onCreate: (String, String) -> Unit, onScan: (() -> Unit)?, onLink: (String) -> Unit) {
    var party by remember { mutableStateOf("") }
    var me by remember(myName) { mutableStateOf(myName) }
    var link by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                T.party.intro,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.party.startParty, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(party, { party = it }, label = { Text(T.party.partyNameHint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(me, { me = it }, label = { Text(T.party.yourName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onCreate(party, me) }, enabled = me.isNotBlank()) { Text(T.party.createAndInvite) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(T.party.joinAParty, style = MaterialTheme.typography.titleMedium)
                    if (onScan != null) {
                        FilledTonalButton(onClick = onScan) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(T.party.scanInviteQr)
                        }
                    }
                    OutlinedTextField(
                        link, { link = it },
                        label = { Text(if (onScan != null) T.party.orPasteInviteLink else T.party.inviteLink) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { onLink(link) }, enabled = link.isNotBlank()) { Text(T.party.joinWithLink) }
                }
            }
        }
    }
}

@Composable
private fun InParty(
    s: PartyState,
    onToggleActive: (Boolean) -> Unit,
    onRadio: () -> Unit,
    onSos: () -> Unit,
    onEditMe: () -> Unit,
    onLeave: () -> Unit,
    onShowMember: (GeoPoint) -> Unit,
    onRouteTo: (GeoPoint) -> Unit,
    onHistory: (Member) -> Unit,
) {
    val party = LocalParty.current
    val speedUnit by LocalPrefs.current.speedUnit.collectAsState()
    // Re-render every 10 s so "x min ago" stays current.
    val now by produceState(nowMillis()) {
        while (true) {
            delay(10_000)
            value = nowMillis()
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (party.watchOnly) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchLine(T.party.watching, if (s.active) T.party.watchingOnSubtitle else T.party.watchingOffSubtitle, s.active, onToggleActive)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            T.party.onlyWatching,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onLeave) { Text(T.party.stopWatching) }
                    }
                }
            }
        } else item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchLine(
                        T.party.partyOn,
                        when {
                            !s.active -> T.party.partyOffSubtitle
                            LocalPartyPlatform.current.runsInBackground -> T.party.partyOnSubtitle
                            else -> T.party.partyOnBrowserSubtitle
                        },
                        s.active, onToggleActive,
                    )
                    SwitchLine(T.party.shareMyLocation, if (s.sharing) T.party.sharingOnSubtitle else T.party.sharingOffSubtitle, s.sharing) { party.setSharing(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        s.me?.let { me ->
                            PartyAvatar(me.name, me.color, me.icon, 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(T.party.youName(me.name), Modifier.weight(1f))
                        }
                        TextButton(onClick = onEditMe) { Text(T.common.edit) }
                        TextButton(onClick = onLeave) { Text(T.party.leave) }
                    }
                }
            }
        }
        if (!party.watchOnly) item {
            Button(
                onClick = onSos,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (s.mySos) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error,
                    contentColor = if (s.mySos) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onError,
                ),
            ) { Text(if (s.mySos) T.party.sosActiveTapToCancel else "SOS", fontWeight = FontWeight.Bold) }
        }
        item { Connections(s, onRadio) }
        item {
            Text(
                when {
                    s.members.isNotEmpty() -> T.party.members(s.members.size)
                    party.watchOnly -> if (s.active) T.party.nothingYet else T.party.turnWatchingOnToSee
                    else -> T.party.waitingForOthers
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(s.members, key = { it.id }) { m ->
            MemberRow(m, s.myPosition, now, speedUnit, s.historyCounts[m.id] ?: 0, onShowMember, onRouteTo, onHistory)
        }
        s.meeting?.let { meet ->
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = {
                        // A label that only says "Meeting point" (in any language the app speaks) isn't repeated.
                        val named = meet.label.isNotBlank() && meet.label !in listOf(Texts.English, Texts.Hebrew).map { it.party.meetingPoint }
                        Text(if (named) T.party.meetingPointNamed(meet.label) else T.party.meetingPoint)
                    },
                    supportingContent = { Text(distanceText(s.myPosition, meet.lat, meet.lon) ?: ltr(Fmt.latLon(meet.lat, meet.lon))) },
                    trailingContent = {
                        Row {
                            Tip(T.party.routeToMeetingPoint) { IconButton(onClick = { onRouteTo(GeoPoint(meet.lat, meet.lon)) }) { Icon(Icons.Filled.Route, contentDescription = T.party.routeToMeetingPoint) } }
                            if (!party.watchOnly) TextButton(onClick = { party.clearMeeting() }) { Text(T.party.clearMeetingPoint) }
                        }
                    },
                )
            }
        } ?: if (party.watchOnly) Unit else item {
            Text(
                T.party.setMeetingPointHint(T.map.meetHere),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.meshNodes.isNotEmpty()) {
            item { Text(T.party.otherRadios(s.meshNodes.size), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(s.meshNodes, key = { "mesh-${it.num}" }) { n ->
                val pos = n.position!!
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Router, contentDescription = null) },
                    headlineContent = { Text(n.name) },
                    supportingContent = {
                        Text(listOfNotNull(distanceText(s.myPosition, pos.lat, pos.lon), ago(now, n.heardAt)).joinToString(" · "))
                    },
                    trailingContent = {
                        Tip(T.common.showOnMap) { IconButton(onClick = { onShowMember(GeoPoint(pos.lat, pos.lon)) }) { Icon(Icons.Filled.Map, contentDescription = T.common.showOnMap) } }
                    },
                )
            }
        }
        item { Chat(s) }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Connections(s: PartyState, onRadio: () -> Unit) {
    val party = LocalParty.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(T.party.connections, style = MaterialTheme.typography.titleSmall)
            if (party.watchOnly) {
                StatusLine(Icons.Filled.Cloud, T.party.online, s.online) {}
                if (!s.active) Text(T.party.turnWatchingOnToConnect, style = MaterialTheme.typography.bodySmall)
            } else if (!party.hasRadio) {
                // Online only (a browser without Bluetooth): no switch, no radio.
                StatusLine(Icons.Filled.Cloud, T.party.online, s.online) {}
                if (!s.active) Text(T.party.turnPartyOnToConnect, style = MaterialTheme.typography.bodySmall)
            } else {
                AppConnections(s, onRadio)
            }
            LocalPartyPlatform.current.connectionsNote(s.active)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!party.watchOnly) ServerLine(s)
        }
    }
}

/**
 * Where the party meets online: the public servers or its own (a tap changes it, also for new
 * parties), what that is, MQTT's site and the guide.
 */
@Composable
private fun ServerLine(s: PartyState) {
    val party = LocalParty.current
    var edit by remember { mutableStateOf(false) }
    var reshare by remember { mutableStateOf(false) }
    val server = s.party?.server ?: if (s.party == null) party.defaultServer else null
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Dns, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(T.party.server, style = MaterialTheme.typography.bodyMedium)
            Text(server?.let(PartyRelays::host) ?: T.party.publicServers, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { edit = true }) { Text(T.offline.change) }
    }
    // The card has its own padding: the explanation lines up with it.
    SectionInfo(T.party.serverInfo, SourceSite("MQTT", "https://mqtt.org/"), Guides.PARTY_SERVER, sidePadding = 0.dp)
    if (reshare) Text(T.party.shareInviteAgain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (edit) {
        ServerDialog(
            server,
            onSave = { new ->
                edit = false
                if (new != server) {
                    party.setServer(new)
                    reshare = s.party != null
                }
            },
            onDismiss = { edit = false },
        )
    }
}

/** The party's own MQTT server: a wss:// address, or (null) the public servers. */
@Composable
internal fun ServerDialog(server: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(server ?: "wss://") }
    val ok = PartyRelays.server(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.party.serverTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.party.serverText, style = MaterialTheme.typography.bodySmall)
                // Addresses read left to right, also in a right-to-left language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(T.party.serverAddress) },
                        placeholder = { Text("wss://mqtt.example.org:8084/mqtt") },
                        isError = ok == null && text.length > 6,
                        supportingText = if (ok == null) ({ Text(T.party.needsServer) }) else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = { TextButton(enabled = ok != null, onClick = { onSave(ok) }) { Text(T.common.save) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text(T.party.usePublicServers) }
                TextButton(onClick = onDismiss) { Text(T.common.cancel) }
            }
        },
    )
}

/** The app's connections: online and the LoRa radio. */
@Composable
private fun AppConnections(s: PartyState, onRadio: () -> Unit) {
    val party = LocalParty.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusLine(Icons.Filled.Cloud, T.party.online, if (s.onlineEnabled) s.online else TransportStatus(TransportStatus.State.OFF, T.party.off)) {
            Switch(checked = s.onlineEnabled, onCheckedChange = { party.setOnlineEnabled(it) })
        }
        StatusLine(
            Icons.Filled.Bluetooth, T.party.loraRadio,
            if (s.radioName == null) TransportStatus(TransportStatus.State.OFF, T.party.noRadioPaired) else s.radio,
        ) {
            if (s.radioName == null) {
                OutlinedButton(onClick = onRadio) { Text(T.party.pair) }
            } else {
                TextButton(onClick = { party.forgetRadio() }) { Text(T.party.forget) }
            }
        }
        if (s.radioName != null) {
            SwitchLine(
                T.party.gateway,
                T.party.gatewaySubtitle,
                s.gateway,
            ) { party.setGateway(it) }
        }
        if (!s.active) {
            Text(T.party.turnPartyOnToConnect, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, status: TransportStatus, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = when (status.state) {
            TransportStatus.State.CONNECTED -> Color(0xFF2E7D32)
            TransportStatus.State.ERROR -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                when (status.state) {
                    TransportStatus.State.CONNECTED -> if (status.detail.isNotBlank()) T.party.connectedTo(status.detail) else T.party.connected
                    else -> status.detail.ifBlank {
                        when (status.state) {
                            TransportStatus.State.OFF -> T.party.stateOff
                            TransportStatus.State.CONNECTING -> T.party.stateConnecting
                            TransportStatus.State.CONNECTED -> T.party.stateConnected
                            TransportStatus.State.ERROR -> T.party.stateError
                        }
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status.state == TransportStatus.State.CONNECTING) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        trailing()
    }
}

@Composable
private fun MemberRow(
    m: Member,
    me: GeoFix?,
    now: Long,
    speedUnit: SpeedUnit,
    /** How many of their positions are in their history. */
    historyCount: Int,
    onShow: (GeoPoint) -> Unit,
    onRoute: (GeoPoint) -> Unit,
    onHistory: (Member) -> Unit,
) {
    val p = m.position
    ListItem(
        modifier = Modifier.clickable(enabled = p != null) { p?.let { onShow(GeoPoint(it.lat, it.lon)) } },
        leadingContent = { PartyAvatar(m.displayName, m.color, m.icon, 36.dp, sos = m.sos) },
        headlineContent = {
            Text(
                if (m.sos) T.party.sosName(m.displayName) else m.displayName,
                color = if (m.sos) MaterialTheme.colorScheme.error else Color.Unspecified,
                fontWeight = if (m.sos) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            Column {
                // Where, when (how long ago, and the time it was taken) and how it came.
                val parts = mutableListOf<String>()
                if (m.sos && m.sosText.isNotBlank()) parts += m.sosText
                if (p != null) {
                    distanceText(me, p.lat, p.lon)?.let(parts::add)
                    ago(now, m.positionAt)?.let { parts += "$it (${Fmt.moment(m.positionAt, seconds = true, nowMs = now)})" }
                } else if (!m.noFixNow) {
                    parts += T.party.noPositionYet
                    ago(now, m.lastHeard)?.let(parts::add)
                }
                parts += m.heard.keys.joinToString("+") { it.label }
                Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                // No GPS fix now: not plotted, but there (when they were last heard).
                noFixLine(m, now)?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.tertiary) }
                // Speed and heading, height, how exact, battery.
                memberDetails(m, speedUnit).takeIf { it.isNotEmpty() }?.let { details ->
                    Text(details.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        trailingContent = {
            Row {
                Tip(T.party.history) { IconButton(onClick = { onHistory(m) }, enabled = historyCount > 0) {
                    Icon(Icons.Filled.History, contentDescription = T.party.history)
                } }
                if (p != null) {
                    Tip(T.party.routeTo(m.displayName)) { IconButton(onClick = { onRoute(GeoPoint(p.lat, p.lon)) }) { Icon(Icons.Filled.Route, contentDescription = T.party.routeTo(m.displayName)) } }
                }
            }
        },
    )
}

@Composable
private fun Chat(s: PartyState) {
    val party = LocalParty.current
    var text by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(T.party.messages, style = MaterialTheme.typography.titleSmall)
            val names = s.members.associate { it.id to it.displayName } + (s.me?.let { mapOf(it.id to T.party.you) } ?: emptyMap())
            if (s.chat.isEmpty()) {
                Text(
                    if (party.watchOnly) T.party.messagesShowHere else T.party.messagesHint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // In the order they were written, each with its time (the writer's clock).
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            s.chat.takeLast(8).forEach { line ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = muted)) { append(ltr(Fmt.moment(line.at))) }
                        append("  ${names[line.from] ?: "?"}: ${line.text}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!party.watchOnly) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it.take(120) }, placeholder = { Text(T.party.messagePlaceholder) }, singleLine = true, modifier = Modifier.weight(1f))
                    Tip(T.party.send) { IconButton(onClick = { party.sendText(text); text = "" }, enabled = text.isNotBlank() && s.active) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = T.party.send)
                    } }
                }
            }
        }
    }
}

@Composable
private fun InviteDialog(key: PartyKey, onDismiss: () -> Unit) {
    val platform = LocalPlatform.current
    val partyPlatform = LocalPartyPlatform.current
    val link = remember(key) { key.toLink() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.party.inviteTo(key.name)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                partyPlatform.QrImage(link, Modifier.size(240.dp))
                Text(
                    T.party.inviteExplanation,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                platform.shareText(T.party.inviteShareText(key.name, link), T.party.shareInvite)
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(T.party.shareLink)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.done) } },
    )
}

@Composable
internal fun EditMeDialog(
    name: String,
    color: Int,
    icon: Int,
    onDismiss: () -> Unit,
    title: String = T.party.youInThisParty,
    onSave: (String, Int, Int) -> Unit,
) {
    var n by remember { mutableStateOf(name) }
    var c by remember { mutableStateOf(color) }
    var i by remember { mutableStateOf(icon) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PartyAvatar(n, c, i, 48.dp)
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(n, { n = it.take(24) }, label = { Text(T.party.nameLabel) }, singleLine = true)
                }
                Text(T.party.howOthersSeeYou, style = MaterialTheme.typography.bodySmall)
                PartyLookPicker(n, c, i, onColor = { c = it }, onIcon = { i = it })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n, c, i) }) { Text(T.common.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.cancel) } },
    )
}

private fun distanceText(me: GeoFix?, lat: Double, lon: Double): String? {
    me ?: return null
    val from = GeoPoint(me.lat, me.lon)
    val to = GeoPoint(lat, lon)
    return T.party.distanceDirection(Fmt.distance(Geo.distance(from, to).toFloat()), Fmt.heading(Geodesy.bearing(from, to).toFloat()))
}

private fun ago(now: Long, at: Long): String? {
    if (at <= 0) return null
    val s = (now - at) / 1000
    return when {
        s < 45 -> T.party.agoNow
        s < 3600 -> T.common.minutesAgo((s / 60.0).roundToInt().coerceAtLeast(1).toLong())
        else -> Fmt.relativeTime(at, now)
    }
}

@Suppress("unused")
private val viaLabels = Via.entries.map { it.label }
