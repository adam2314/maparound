package app.maparound.ui

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

/** Colours members can pick; also used for their map markers. */
object PartyColors {
    val all = listOf(
        Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
        Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF6D4C41),
    )

    fun of(index: Int): Color = all[(index % all.size + all.size) % all.size]
}

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
        if (key == null) scope.launch { snackbar.showSnackbar("That QR code isn't a MapAround party") }
        else party.pendingInvite.value = key
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(s.party?.name ?: "Party") },
            actions = {
                if (s.party != null) {
                    IconButton(onClick = { showQr = true }) { Icon(Icons.Filled.QrCode, contentDescription = "Invite") }
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (s.party == null) {
            NoParty(onCreate = { partyName, me ->
                if (!hasLocation) requestLocation()
                ensureNotifications()
                party.create(partyName, me)
                showQr = true
            }, onScan = scan, onLink = { text ->
                val key = PartyKey.parse(text)
                if (key == null) scope.launch { snackbar.showSnackbar("That isn't a valid party link") }
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
            )
        }
    }

    pending?.let { key ->
        var name by remember(key) { mutableStateOf(s.me?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { party.pendingInvite.value = null },
            title = { Text("Join “${key.name}”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everyone in the party sees your live location while sharing is on.")
                    if (s.party != null) Text("You'll leave “${s.party!!.name}”.", color = MaterialTheme.colorScheme.error)
                    OutlinedTextField(name, { name = it }, label = { Text("Your name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    party.pendingInvite.value = null
                    if (!hasLocation) requestLocation()
                    ensureNotifications()
                    party.join(key, name)
                }) { Text("Join") }
            },
            dismissButton = { TextButton(onClick = { party.pendingInvite.value = null }) { Text("Cancel") } },
        )
    }

    if (showQr) s.party?.let { InviteDialog(it, onDismiss = { showQr = false }) }
    if (radioPicker) partyPlatform.RadioPicker(onPick = { addr, name -> radioPicker = false; party.setRadio(addr, name) }, onDismiss = { radioPicker = false })
    if (editMe) s.me?.let { me -> EditMeDialog(me.name, me.color, onDismiss = { editMe = false }, onSave = { n, c -> editMe = false; party.setProfile(n, c) }) }

    if (confirmSos) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { confirmSos = false },
            title = { Text("Send SOS?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everyone in the party gets an alert with your position, over every connection you have.")
                    OutlinedTextField(text, { text = it }, label = { Text("What happened (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmSos = false; party.sos(true, text) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Send SOS") }
            },
            dismissButton = { TextButton(onClick = { confirmSos = false }) { Text("Cancel") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave the party?") },
            text = { Text("Your position is removed for the others. You can rejoin with the invite.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; party.leave() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NoParty(onCreate: (String, String) -> Unit, onScan: () -> Unit, onLink: (String) -> Unit) {
    var party by remember { mutableStateOf("") }
    var me by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "See your group live on the map, online and off-grid over LoRa radios (Meshtastic). " +
                    "Everything is end-to-end encrypted with the party's key.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start a party", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(party, { party = it }, label = { Text("Party name, e.g. Desert run") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(me, { me = it }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onCreate(party, me) }, enabled = me.isNotBlank()) { Text("Create & invite") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Join a party", style = MaterialTheme.typography.titleMedium)
                    FilledTonalButton(onClick = onScan) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scan invite QR code")
                    }
                    OutlinedTextField(link, { link = it }, label = { Text("…or paste the invite link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { onLink(link) }, enabled = link.isNotBlank()) { Text("Join with link") }
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
) {
    val party = LocalParty.current
    // Re-render every 10 s so "x min ago" stays current.
    val now by produceState(nowMillis()) {
        while (true) {
            delay(10_000)
            value = nowMillis()
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchLine("Party on", if (s.active) "Sending and receiving, also with the screen off" else "Off: not sending or receiving", s.active, onToggleActive)
                    SwitchLine("Share my location", if (s.sharing) "Others see you" else "Hidden: you see them, they don't see you", s.sharing) { party.setSharing(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        s.me?.let { me ->
                            Box(Modifier.size(14.dp).background(PartyColors.of(me.color), CircleShape))
                            Spacer(Modifier.width(8.dp))
                            Text("You: ${me.name}", Modifier.weight(1f))
                        }
                        TextButton(onClick = onEditMe) { Text("Edit") }
                        TextButton(onClick = onLeave) { Text("Leave") }
                    }
                }
            }
        }
        item {
            Button(
                onClick = onSos,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (s.mySos) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.error,
                    contentColor = if (s.mySos) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onError,
                ),
            ) { Text(if (s.mySos) "SOS is active. Tap to cancel" else "SOS", fontWeight = FontWeight.Bold) }
        }
        item { Connections(s, onRadio) }
        item {
            Text(
                if (s.members.isEmpty()) "Waiting for others… share the invite (QR icon at the top)." else "Members (${s.members.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(s.members, key = { it.id }) { m -> MemberRow(m, s.myPosition, now, onShowMember, onRouteTo) }
        s.meeting?.let { meet ->
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    headlineContent = { Text("Meeting point" + if (meet.label.isNotBlank() && meet.label != "Meeting point") ": ${meet.label}" else "") },
                    supportingContent = { Text(distanceText(s.myPosition, meet.lat, meet.lon) ?: Fmt.latLon(meet.lat, meet.lon)) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onRouteTo(GeoPoint(meet.lat, meet.lon)) }) { Icon(Icons.Filled.Route, contentDescription = "Route to meeting point") }
                            TextButton(onClick = { party.clearMeeting() }) { Text("Clear") }
                        }
                    },
                )
            }
        } ?: item {
            Text(
                "Set a meeting point: long-press the map, then “Meet here”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (s.meshNodes.isNotEmpty()) {
            item { Text("Other radios in range (${s.meshNodes.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(s.meshNodes, key = { "mesh-${it.num}" }) { n ->
                val pos = n.position!!
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Router, contentDescription = null) },
                    headlineContent = { Text(n.name) },
                    supportingContent = {
                        Text(listOfNotNull(distanceText(s.myPosition, pos.lat, pos.lon), ago(now, n.heardAt)).joinToString(" · "))
                    },
                    trailingContent = {
                        IconButton(onClick = { onShowMember(GeoPoint(pos.lat, pos.lon)) }) { Icon(Icons.Filled.Map, contentDescription = "Show on map") }
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
            Text("Connections", style = MaterialTheme.typography.titleSmall)
            StatusLine(Icons.Filled.Cloud, "Online", if (s.onlineEnabled) s.online else TransportStatus(TransportStatus.State.OFF, "Off")) {
                Switch(checked = s.onlineEnabled, onCheckedChange = { party.setOnlineEnabled(it) })
            }
            StatusLine(
                Icons.Filled.Bluetooth, "LoRa radio",
                if (s.radioName == null) TransportStatus(TransportStatus.State.OFF, "No radio paired") else s.radio,
            ) {
                if (s.radioName == null) {
                    OutlinedButton(onClick = onRadio) { Text("Pair") }
                } else {
                    TextButton(onClick = { party.forgetRadio() }) { Text("Forget") }
                }
            }
            if (s.radioName != null) {
                SwitchLine(
                    "Gateway",
                    "Relay between radio and online, so off-grid and online members see each other",
                    s.gateway,
                ) { party.setGateway(it) }
            }
            if (!s.active) {
                Text("Turn the party on to connect.", style = MaterialTheme.typography.bodySmall)
            }
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
                    TransportStatus.State.CONNECTED -> "Connected" + if (status.detail.isNotBlank()) " · ${status.detail}" else ""
                    else -> status.detail.ifBlank { status.state.name.lowercase() }
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
private fun MemberRow(m: Member, me: GeoFix?, now: Long, onShow: (GeoPoint) -> Unit, onRoute: (GeoPoint) -> Unit) {
    val p = m.position
    ListItem(
        modifier = Modifier.clickable(enabled = p != null) { p?.let { onShow(GeoPoint(it.lat, it.lon)) } },
        leadingContent = {
            Box(
                Modifier.size(32.dp).background(PartyColors.of(m.color), CircleShape)
                    .then(if (m.sos) Modifier.border(3.dp, MaterialTheme.colorScheme.error, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Text(m.displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold) }
        },
        headlineContent = {
            Text(
                (if (m.sos) "SOS · " else "") + m.displayName,
                color = if (m.sos) MaterialTheme.colorScheme.error else Color.Unspecified,
                fontWeight = if (m.sos) FontWeight.Bold else null,
            )
        },
        supportingContent = {
            val parts = mutableListOf<String>()
            if (m.sos && m.sosText.isNotBlank()) parts += m.sosText
            p?.let { pos ->
                distanceText(me, pos.lat, pos.lon)?.let(parts::add)
                pos.speedKmh?.takeIf { it >= 3 }?.let { parts += "$it km/h" }
                pos.batteryPct?.let { parts += "🔋$it%" }
            } ?: parts.add("no position yet")
            ago(now, m.positionAt.takeIf { it > 0 } ?: m.lastHeard)?.let(parts::add)
            parts += m.heard.keys.joinToString("+") { it.label }
            Text(parts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            if (p != null) {
                IconButton(onClick = { onRoute(GeoPoint(p.lat, p.lon)) }) { Icon(Icons.Filled.Route, contentDescription = "Route to ${m.displayName}") }
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
            Text("Messages", style = MaterialTheme.typography.titleSmall)
            val names = s.members.associate { it.id to it.displayName } + (s.me?.let { mapOf(it.id to "You") } ?: emptyMap())
            if (s.chat.isEmpty()) Text("Short messages reach everyone, also over radio.", style = MaterialTheme.typography.bodySmall)
            s.chat.takeLast(8).forEach { line ->
                Text("${names[line.from] ?: "?"}: ${line.text}", style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it.take(120) }, placeholder = { Text("Message") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { party.sendText(text); text = "" }, enabled = text.isNotBlank() && s.active) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
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
        title = { Text("Invite to “${key.name}”") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                partyPlatform.QrImage(link, Modifier.size(240.dp))
                Text(
                    "Others scan this in MapAround's Party tab, or open the link. Anyone with it can see the party's positions, " +
                        "so share it only with your group.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                platform.shareText("Join my MapAround party “${key.name}”: $link", "Share invite")
            }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Share link")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun EditMeDialog(name: String, color: Int, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var n by remember { mutableStateOf(name) }
    var c by remember { mutableStateOf(color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You in this party") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(n, { n = it.take(24) }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PartyColors.all.forEachIndexed { i, col ->
                        Box(
                            Modifier.size(28.dp).background(col, CircleShape)
                                .then(if (i == c) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { c = i },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n, c) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun distanceText(me: GeoFix?, lat: Double, lon: Double): String? {
    me ?: return null
    val from = GeoPoint(me.lat, me.lon)
    val to = GeoPoint(lat, lon)
    return "${Fmt.distance(Geo.distance(from, to).toFloat())} ${Fmt.cardinal(Geodesy.bearing(from, to).toFloat())}"
}

private fun ago(now: Long, at: Long): String? {
    if (at <= 0) return null
    val s = (now - at) / 1000
    return when {
        s < 45 -> "now"
        s < 3600 -> "${(s / 60.0).roundToInt().coerceAtLeast(1)} min ago"
        else -> Fmt.relativeTime(at, now)
    }
}

@Suppress("unused")
private val viaLabels = Via.entries.map { it.label }
