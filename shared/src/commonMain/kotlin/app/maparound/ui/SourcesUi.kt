package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.data.MapPos
import app.maparound.i18n.T
import app.maparound.map.MapRegions
import app.maparound.map.TileSources
import app.maparound.party.LocalParty
import app.maparound.party.PartyRelays
import app.maparound.sources.AddressKind
import app.maparound.sources.RegionalDefaults
import app.maparound.sources.SourceGroup
import app.maparound.sources.SourceKind
import app.maparound.sources.SourceSetting
import app.maparound.sources.Sources
import kotlinx.coroutines.launch

/** "All default", "2 of your own · 1 off": the Sources line of Settings. */
@Composable
internal fun sourcesSummary(): String {
    val prefs = LocalPrefs.current
    val choices by prefs.sources.collectAsState()
    val elevation by prefs.elevationSource.collectAsState()
    val party = LocalParty.current
    val s by party.state.collectAsState()
    val partyOwn = (s.party?.server ?: if (s.party == null) party.defaultServer else null) != null
    val own = choices.values.count { it is SourceSetting.Custom } + (if (elevation != null) 1 else 0) + (if (partyOwn) 1 else 0)
    val off = choices.values.count { it == SourceSetting.Off }
    return if (own == 0 && off == 0) T.settings.allDefault else T.settings.sourcesChanged(own, off)
}

/**
 * Every online service in one place, in groups: what's used now (the app's own, the region's,
 * the user's, or off), a tap to change it. Those kept elsewhere too (the map layer, elevation,
 * the party's server) open the same dialogs as there.
 */
@Composable
internal fun SourcesPage() {
    val prefs = LocalPrefs.current
    val offline = LocalOfflineData.current
    val party = LocalParty.current
    val choices by prefs.sources.collectAsState()
    val baseLayer by prefs.baseLayer.collectAsState()
    val custom by prefs.customSources.collectAsState()
    val streamMaps by prefs.streamMaps.collectAsState()
    val elevation by prefs.elevationSource.collectAsState()
    val relief by prefs.terrain3dSource.collectAsState()
    val partyState by party.state.collectAsState()
    var editing by remember { mutableStateOf<SourceKind?>(null) }
    // The lines follow where the map is (regional defaults) and the choices (both snapshot state).
    val regions = Sources.regionPaths
    val partyServer = partyState.party?.server ?: if (partyState.party == null) party.defaultServer else null

    Text(
        T.settings.sourcesNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val shown = SourceKind.entries.filter { k ->
        when (k) {
            SourceKind.STREAMED_MAPS -> offline.streamed != null
            SourceKind.ROUTING_DATA -> offline.addOns
            // With elevation: one dialog has both.
            SourceKind.RELIEF_3D -> false
            SourceKind.PARTY -> !party.watchOnly
            // Where there's a data view.
            SourceKind.GPS_TRACES, SourceKind.RAIN_RADAR, SourceKind.WEATHER, SourceKind.STREET_PHOTOS, SourceKind.BUSES, SourceKind.CAMERAS, SourceKind.WEATHER_MAPS ->
                app.maparound.stats.LocalStats.current != null
            else -> true
        }
    }
    for (group in SourceGroup.entries) {
        val kinds = shown.filter { it.group == group }
        if (kinds.isEmpty()) continue
        Group(
            when (group) {
                SourceGroup.MAPS -> T.settings.groupMaps
                SourceGroup.TERRAIN -> T.settings.groupTerrain
                SourceGroup.ROUTING -> T.settings.groupRouting
                SourceGroup.PLACES -> T.settings.groupPlaces
                SourceGroup.DATA -> T.stats.dataView
                SourceGroup.PARTY -> T.settings.party
            },
        )
        for (k in kinds) {
            when (k) {
                SourceKind.STREAMED_MAPS -> OnOff(title(k), T.settings.streamedMapsNote, streamMaps) { prefs.setStreamMaps(it) }
                SourceKind.WIKIPEDIA, SourceKind.RAIN_RADAR -> OnOff(
                    title(k),
                    if (k == SourceKind.WIKIPEDIA) T.settings.wikipediaNote else T.live.radarSourceNote,
                    !Sources.isOff(k),
                ) {
                    prefs.setSource(k, if (it) SourceSetting.Default else SourceSetting.Off)
                }
                else -> Line(title(k), now(k, baseLayer, custom, elevation, relief != null, partyServer, regions)) { editing = k }
            }
        }
    }

    when (val k = editing) {
        null -> Unit
        SourceKind.MAP_LAYER -> MapLayerDialog(baseLayer, onDismiss = { editing = null })
        SourceKind.ELEVATION -> ElevationSourceDialog(
            elevation, relief, offline.webTerrain,
            onSave = { tiles, tiles3d ->
                prefs.setElevationSource(tiles)
                if (offline.webTerrain) prefs.setTerrain3dSource(tiles3d)
                editing = null
            },
            onDismiss = { editing = null },
        )
        SourceKind.PARTY -> ServerDialog(partyServer, onSave = { new -> party.setServer(new); editing = null }, onDismiss = { editing = null })
        else -> SourceDialog(k, Sources.setting(k), onDismiss = { editing = null }) { setting ->
            prefs.setSource(k, setting)
            editing = null
        }
    }
}

private fun title(k: SourceKind): String = when (k) {
    SourceKind.MAP_LIST -> T.settings.mapList
    SourceKind.MAP_LAYER -> T.settings.mapLayer
    SourceKind.STREAMED_MAPS -> T.settings.streamedMaps
    SourceKind.SATELLITE -> T.settings.satellite
    SourceKind.TRAILS_OVERLAY -> T.settings.trailsOverlay
    SourceKind.ELEVATION, SourceKind.RELIEF_3D -> T.settings.elevation
    SourceKind.ROUTING -> T.settings.routing
    SourceKind.ROUTING_DATA -> T.settings.routingData
    SourceKind.SEARCH -> T.settings.search
    SourceKind.WIKIPEDIA -> T.settings.wikipedia
    SourceKind.PARTY -> T.settings.partyServer
    SourceKind.GPS_TRACES -> T.stats.gpsTraces
    SourceKind.RAIN_RADAR -> T.live.rainRadar
    SourceKind.WEATHER -> T.live.weather
    SourceKind.STREET_PHOTOS -> T.live.streetPhotos
    SourceKind.BUSES -> T.live.buses
    SourceKind.CAMERAS -> T.live.camerasLayer
    SourceKind.WEATHER_MAPS -> T.live.weatherMaps
}

/** A server's name from its address ("brouter.de"). */
private fun host(address: String): String = TileSources.hostOf(address).removePrefix("www.")

/** The region's name for a path ("Switzerland"), from the app's regions. */
private fun regionName(path: String): String = MapRegions.bundled?.all?.firstOrNull { it.path == path }?.label ?: path.substringAfterLast('/')

/** What's used for [k] now, in a few words. */
private fun now(
    k: SourceKind,
    baseLayer: String,
    custom: List<app.maparound.map.OnlineSource>,
    elevation: String?,
    ownRelief: Boolean,
    partyServer: String?,
    regions: List<String>,
): String {
    val s = T.settings
    fun withRegion(name: String, kind: SourceKind): String {
        val own = Sources.regional(kind, regions) ?: return s.defaultIs(name)
        val where = regions.firstOrNull { r -> app.maparound.sources.RegionalDefaults.of(kind, listOf(r)) != null } ?: regions.first()
        return s.forRegion(TileSources.title(own), regionName(where))
    }
    return when (k) {
        SourceKind.MAP_LAYER -> when (baseLayer) {
            TileSources.AUTO_ID -> T.settings.automaticNow(TileSources.title(TileSources.automatic(regions)))
            TileSources.OFFLINE_ID -> s.offlineMaps
            else -> TileSources.find(baseLayer, custom)?.let { TileSources.title(it) } ?: s.offlineMaps
        }
        SourceKind.ELEVATION -> if (elevation != null || ownRelief) s.yours(host(elevation ?: SourceKind.RELIEF_3D.builtIn.first())) else s.defaultIs(host(SourceKind.ELEVATION.builtIn.first()))
        SourceKind.PARTY -> partyServer?.let { s.yours(PartyRelays.host(it)) } ?: T.party.publicServers
        else -> when (val c = Sources.setting(k)) {
            SourceSetting.Off -> s.off
            is SourceSetting.Custom -> s.yours(host(c.addresses.first()))
            SourceSetting.Default -> when (k) {
                SourceKind.SATELLITE -> withRegion(host(SourceKind.SATELLITE.builtIn.first()), k)
                else -> s.defaultIs(k.builtIn.joinToString(", ") { host(it) })
            }
        }
    }
}

/** What the default is for [kind] here: the region's own ("swisstopo (for Switzerland)") or the app's servers. */
private fun defaultName(kind: SourceKind, regions: List<String>): String {
    val own = RegionalDefaults.of(kind, regions)
    val where = regions.firstOrNull { r -> RegionalDefaults.of(kind, listOf(r)) != null }
    return if (own != null && where != null) T.settings.forRegion(TileSources.title(own), regionName(where)) else kind.builtIn.joinToString(", ") { host(it) }
}

@Composable
private fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Line(title: String, now: String, onClick: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(now, maxLines = 2) },
    )
}

@Composable
private fun OnOff(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!on) },
        headlineContent = { Text(title) },
        supportingContent = { Text(note) },
        trailingContent = { Switch(checked = on, onCheckedChange = onChange) },
    )
}

/**
 * One service: the app's own (or the region's), your address (with a check: one request to it),
 * or off where it can be.
 */
@Composable
private fun SourceDialog(kind: SourceKind, setting: SourceSetting, onDismiss: () -> Unit, onSave: (SourceSetting) -> Unit) {
    val prefs = LocalPrefs.current
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(setting) }
    var text by remember { mutableStateOf((setting as? SourceSetting.Custom)?.addresses?.first() ?: "https://") }
    var result by remember { mutableStateOf<String?>(null) }
    val ok = Sources.validate(kind, text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title(kind)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Pick(choice == SourceSetting.Default, T.settings.default, defaultName(kind, Sources.regionPaths)) {
                    choice = SourceSetting.Default
                }
                Pick(choice is SourceSetting.Custom, T.settings.yourAddress, if (kind.customFirst) T.settings.triedFirst else null) {
                    choice = SourceSetting.Custom(listOfNotNull(ok))
                }
                if (choice is SourceSetting.Custom) {
                    // Addresses read left to right, also in a right-to-left language.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it; result = null },
                            placeholder = { Text(example(kind)) },
                            isError = ok == null && text.length > 8,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = ok != null, onClick = {
                            val address = ok ?: return@TextButton
                            result = T.settings.checking
                            scope.launch {
                                val at = prefs.lastPosition() ?: MapPos(46.6, 8.0, 10)
                                val c = Sources.check(kind, address, at.lat, at.lon)
                                result = if (c.ok) T.settings.works(c.ms) else T.settings.didntWork(c.problem.orEmpty())
                            }
                        }) { Text(T.settings.check) }
                        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                if (kind.canBeOff) Pick(choice == SourceSetting.Off, T.settings.off, null) { choice = SourceSetting.Off }
            }
        },
        confirmButton = {
            TextButton(
                enabled = choice !is SourceSetting.Custom || ok != null,
                onClick = { onSave(if (choice is SourceSetting.Custom) SourceSetting.Custom(listOf(ok!!)) else choice) },
            ) { Text(T.common.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(T.common.cancel) } },
    )
}

private fun example(kind: SourceKind): String = when (kind.address) {
    AddressKind.TILES -> "https://tiles.example.org/{z}/{x}/{y}.png"
    else -> when (kind) {
        SourceKind.MAP_LIST -> "https://maps.example.org/catalog.json"
        SourceKind.ROUTING -> "https://brouter.example.org/brouter"
        SourceKind.ROUTING_DATA -> "https://brouter.example.org/segments4/"
        SourceKind.SEARCH -> "https://photon.example.org/api/"
        else -> "https://"
    }
}

@Composable
private fun Pick(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The online map, as in Layers: automatic (the region's own), the offline maps, the built-in
 * services and the user's, or a new one of their own.
 */
@Composable
private fun MapLayerDialog(selected: String, onDismiss: () -> Unit) {
    val prefs = LocalPrefs.current
    val custom by prefs.customSources.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val pick: (String) -> Unit = { prefs.setBaseLayer(it); onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.settings.mapLayer) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Pick(selected == TileSources.AUTO_ID, T.settings.automaticNow(TileSources.title(TileSources.automatic())), T.map.srcAutomatic) {
                    pick(TileSources.AUTO_ID)
                }
                Pick(selected == TileSources.OFFLINE_ID, T.settings.offlineMaps, null) { pick(TileSources.OFFLINE_ID) }
                for (src in TileSources.builtIn + custom) {
                    Pick(selected == src.id, TileSources.title(src), if (src.custom) src.urlTemplate else null) { pick(src.id) }
                }
                TextButton(onClick = { adding = true }) { Text(T.map.addCustomSource) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(T.common.close) } },
    )
    if (adding) {
        AddSourceDialog(onDismiss = { adding = false }) {
            adding = false
            prefs.addCustomSource(it)
            pick(it.id)
        }
    }
}
