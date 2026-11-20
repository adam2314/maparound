package app.maparound.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.party.LocalParty
import app.maparound.party.PartyConnection
import app.maparound.party.TransportStatus
import app.maparound.shared.util.nowMillis

/**
 * Under the Party layer in Layers → Overlays, while it's on: each party (its members; its meeting
 * point and marks) and each connection, shared by the parties on it, on and off
 * (docs/LINK_PROTOCOL.md §7). An SOS shows whatever is off; a hidden connection keeps running.
 */
@Composable
internal fun PartyLayerRows() {
    val control = LocalParty.current
    val parties by control.parties.collectAsState()
    if (parties.isEmpty()) return
    val common = parties.first()
    Column(Modifier.fillMaxWidth().padding(start = 64.dp)) {
        for (p in parties) {
            val key = p.party ?: continue
            SubLayer(
                lead = { Box(Modifier.size(14.dp).background(Color(p.color), CircleShape)) },
                title = key.name,
                subtitle = T.map.partyMembersLine(p.members.size, p.members.any { it.sos }),
                checked = p.layers.members,
            ) { control.setLayers(key, p.layers.copy(members = it)) }
            SubLayer(
                lead = { Icon(Icons.Filled.Timeline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = T.map.partyTrails,
                subtitle = T.map.partyTrailsHint,
                checked = p.layers.trails,
                indent = true,
            ) { control.setLayers(key, p.layers.copy(trails = it)) }
            SubLayer(
                lead = { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                title = T.map.partyMeetAndMarks,
                subtitle = null,
                checked = p.layers.marks,
                indent = true,
            ) { control.setLayers(key, p.layers.copy(marks = it)) }
        }
        if (common.connections.isNotEmpty()) {
            Text(
                T.map.partyConnections, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, end = 16.dp),
            )
            Text(
                T.map.partyConnectionsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp, bottom = 2.dp),
            )
            for (c in common.connections) {
                SubLayer(
                    lead = {
                        Icon(
                            if (c.radio) Icons.Filled.Bluetooth else Icons.Filled.Cloud, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = if (c.status.state == TransportStatus.State.CONNECTED) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    title = c.label,
                    subtitle = connectionLine(c),
                    checked = c.id !in common.hiddenConnections,
                ) { control.setConnectionShown(c.id, it) }
                if (c.radio && c.nodes > 0) {
                    SubLayer(
                        lead = { Icon(Icons.Filled.Router, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        title = T.map.meshNodes(c.nodes),
                        subtitle = null,
                        checked = common.nodesShown,
                        indent = true,
                    ) { control.setNodesShown(it) }
                }
            }
        }
    }
}

/** How a connection is doing, how many members came through it, and when it last heard one. */
private fun connectionLine(c: PartyConnection): String {
    val now = nowMillis()
    val state = when (c.status.state) {
        TransportStatus.State.OFF -> T.party.stateOff
        TransportStatus.State.CONNECTING -> T.party.stateConnecting
        TransportStatus.State.CONNECTED -> T.party.stateConnected
        TransportStatus.State.ERROR -> c.status.detail.ifBlank { T.party.stateError }
    }
    return listOfNotNull(
        state,
        T.map.partyMembersLine(c.members, false).takeIf { c.members > 0 },
        c.lastHeard.takeIf { it > 0 }?.let { at ->
            if (now - at < 60_000) T.party.heardJustNow else T.party.heard(Fmt.relativeTime(at, now).replaceFirstChar { it.lowercase() })
        },
    ).joinToString(" · ")
}

/** One row under the Party layer: what it is, a line about it, and its switch; the whole row toggles it. */
@Composable
private fun SubLayer(
    lead: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    checked: Boolean,
    indent: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = if (indent) 24.dp else 0.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
