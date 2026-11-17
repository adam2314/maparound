package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.maparound.data.LocalPrefs
import app.maparound.i18n.DateOrder
import app.maparound.i18n.DirectionFormat
import app.maparound.i18n.DistanceUnit
import app.maparound.i18n.HeightUnit
import app.maparound.i18n.I18n
import app.maparound.i18n.Language
import app.maparound.i18n.T
import app.maparound.i18n.TimeFormat
import app.maparound.i18n.UnitChoice
import app.maparound.i18n.Units
import app.maparound.i18n.ltr
import app.maparound.party.LocalParty
import app.maparound.party.PartyPalette
import app.maparound.party.PartyRelays
import app.maparound.sensors.SpeedUnit
import app.maparound.shared.util.deviceLanguage
import app.maparound.shared.util.nowMillis
import app.maparound.tools.CoordFormat
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** The gear for the app's settings (in the Library's top bar), and its sheet. */
@Composable
fun SettingsButton() {
    var open by remember { mutableStateOf(false) }
    Tip(T.common.settings) { IconButton(onClick = { open = true }) { Icon(Icons.Filled.Settings, contentDescription = T.common.settings) } }
    if (open) SettingsSheet(onDismiss = { open = false })
}

private enum class SettingsPage { LANGUAGE, UNITS, SCREEN, PARTY, SOURCES }

/**
 * The app's settings in one place: a list (the language, units and formats, the party, the online
 * sources; supporting MapAround), each opening its page; Back goes to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PlatformBackHandler(enabled = page != null) { page = null }
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            when (val p = page) {
                null -> SettingsList { page = it }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Tip(T.common.back) { IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = T.common.back) } }
                        Text(
                            when (p) {
                                SettingsPage.LANGUAGE -> T.common.language
                                SettingsPage.UNITS -> T.settings.unitsAndFormats
                                SettingsPage.SCREEN -> T.settings.screen
                                SettingsPage.PARTY -> T.settings.party
                                SettingsPage.SOURCES -> T.settings.sources
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    when (p) {
                        SettingsPage.LANGUAGE -> LanguagePage()
                        SettingsPage.UNITS -> UnitsPage()
                        SettingsPage.SCREEN -> ScreenPage()
                        SettingsPage.PARTY -> PartyPage()
                        SettingsPage.SOURCES -> SourcesPage()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsList(onOpen: (SettingsPage) -> Unit) {
    val platform = LocalPlatform.current
    val party = LocalParty.current
    val s by party.state.collectAsState()
    val choice by LocalPrefs.current.language.collectAsState()
    Text(T.common.settings, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
    Row_(Icons.Filled.Language, T.common.language, if (choice.isEmpty()) "${T.common.deviceLanguage} · ${I18n.language.nativeName}" else I18n.language.nativeName) {
        onOpen(SettingsPage.LANGUAGE)
    }
    Row_(Icons.Filled.SquareFoot, T.settings.unitsAndFormats, unitsSummary()) { onOpen(SettingsPage.UNITS) }
    val prefs = LocalPrefs.current
    val screenOpen by prefs.screenOnOpen.collectAsState()
    val screenNav by prefs.screenOnNav.collectAsState()
    Row_(Icons.Filled.LightMode, T.settings.screen, T.settings.screenSummary(screenLabel(screenOpen), screenLabel(screenNav))) {
        onOpen(SettingsPage.SCREEN)
    }
    val server = s.party?.server ?: if (s.party == null) party.defaultServer else null
    Row_(
        Icons.Filled.Groups, T.settings.party,
        T.settings.partySummary(
            (s.me ?: s.look)?.name?.ifBlank { null }.takeIf { !party.watchOnly } ?: T.settings.noNameYet.takeIf { !party.watchOnly },
            server?.let(PartyRelays::host) ?: T.party.publicServers,
        ),
    ) { onOpen(SettingsPage.PARTY) }
    Row_(Icons.Filled.Hub, T.settings.sources, sourcesSummary()) { onOpen(SettingsPage.SOURCES) }
    // What donations pay for, on the website.
    HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp))
    ListItem(
        colors = clear(),
        modifier = Modifier.clickable { platform.openUrl(Guides.page(Guides.SUPPORT)) },
        leadingContent = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(T.common.supportMapAround) },
        supportingContent = { Text(T.common.supportSubtitle) },
    )
}

/** A line of the list: what it is, how it is now; a tap opens its page. */
@Composable
private fun Row_(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, now: String, onClick: () -> Unit) {
    ListItem(
        colors = clear(),
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(now, maxLines = 1) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    )
}

/** "km · m · 14:05 · 17 Nov": the units and formats now, by example. */
@Composable
private fun unitsSummary(): String {
    val u = Units.current
    val now = nowMillis()
    val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).date
    return listOf(distanceLabel(u.distance), u.speed.label, Fmt.timeOfDay(now), Fmt.day(today, withYear = false)).joinToString(" · ")
}

private fun distanceLabel(d: DistanceUnit) = when (d) {
    DistanceUnit.METRIC -> T.settings.kmAndM
    DistanceUnit.IMPERIAL -> T.settings.miAndFt
    DistanceUnit.NAUTICAL -> T.settings.nauticalMiles
}

private fun heightLabel(h: HeightUnit) = if (h == HeightUnit.FT) T.common.unitFt else T.common.unitM

@Composable
private fun LanguagePage() {
    val prefs = LocalPrefs.current
    val choice by prefs.language.collectAsState()
    val device = Language.of(deviceLanguage()) ?: Language.EN
    Choice(choice.isEmpty(), T.common.deviceLanguage, T.common.deviceLanguageNow(device.nativeName)) { prefs.setLanguage("") }
    for (l in Language.entries) Choice(choice == l.code, l.nativeName, null) { prefs.setLanguage(l.code) }
}

/**
 * Units and formats: quick choices (automatic, metric, imperial, nautical), then each on its own,
 * "Automatic" first with what the device has; the examples are in each format.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitsPage() {
    val prefs = LocalPrefs.current
    val c by prefs.unitChoice.collectAsState()
    val coords by prefs.coordFormat.collectAsState()
    val device = remember { Units.device }
    val set = prefs::setUnitChoice
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(c.automatic, onClick = { set(UnitChoice()) }, label = { Text(T.settings.automatic) })
        FilterChip(c == UnitChoice.metric(c), onClick = { set(UnitChoice.metric(c)) }, label = { Text(T.settings.metric) })
        FilterChip(c == UnitChoice.imperial(c), onClick = { set(UnitChoice.imperial(c)) }, label = { Text(T.settings.imperial) })
        FilterChip(c == UnitChoice.nautical(c), onClick = { set(UnitChoice.nautical(c)) }, label = { Text(T.settings.nautical) })
    }
    Text(
        T.settings.automaticNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    val today = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(TimeZone.currentSystemDefault()).date
    Choices(T.settings.distance, c.distance, device.distance, DistanceUnit.entries, ::distanceLabel) { set(c.copy(distance = it)) }
    Choices(T.settings.height, c.height, device.height, HeightUnit.entries, ::heightLabel) { set(c.copy(height = it)) }
    Choices(T.settings.speed, c.speed, device.speed, SpeedUnit.entries, { it.label }) { set(c.copy(speed = it)) }
    Choices(T.settings.direction, c.direction, device.direction, DirectionFormat.entries, {
        if (it == DirectionFormat.MILS) T.settings.mils else Fmt.heading(23f, it)
    }) { set(c.copy(direction = it)) }
    Choices(T.settings.time, c.time, device.time, TimeFormat.entries, { Fmt.clock(14, 5, null, it) }) { set(c.copy(time = it)) }
    Choices(T.settings.date, c.date, device.date, DateOrder.entries, { Fmt.day(today, withYear = true, order = it) }) { set(c.copy(date = it)) }
    // How coordinates are shown on pins and cards (also chosen in the tools).
    Section(T.settings.coordinates)
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in CoordFormat.entries) FilterChip(coords == f, onClick = { prefs.setCoordFormat(f) }, label = { Text(f.label) })
    }
}

/** One unit or format: "Automatic (what the device has)", then each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <E> Choices(title: String, chosen: E?, device: E, all: List<E>, label: (E) -> String, onPick: (E?) -> Unit) {
    Section(title)
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(chosen == null, onClick = { onPick(null) }, label = { Text(T.settings.automaticNow(ltr(label(device)))) })
        for (e in all) FilterChip(chosen == e, onClick = { onPick(e) }, label = { Text(ltr(label(e))) })
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

private fun screenLabel(s: ScreenOn): String = when (s) {
    ScreenOn.SYSTEM -> T.settings.screenSystem
    ScreenOn.ALWAYS -> T.settings.screenAlways
    else -> T.settings.screenMinutes(s.minutes!!)
}

/** How long the screen stays on after the last touch: with the app open, and while navigating. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenPage() {
    val prefs = LocalPrefs.current
    val open by prefs.screenOnOpen.collectAsState()
    val nav by prefs.screenOnNav.collectAsState()
    Text(
        T.settings.screenNote,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    for ((title, chosen, set) in listOf(
        Triple(T.settings.screenWhileOpen, open, prefs::setScreenOnOpen),
        Triple(T.settings.screenWhileNavigating, nav, prefs::setScreenOnNav),
    )) {
        Section(title)
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in ScreenOn.entries) FilterChip(chosen == s, onClick = { set(s) }, label = { Text(screenLabel(s)) })
        }
    }
}

/**
 * The party: your name and look (in a party or not: the next one starts with them), where parties
 * meet online (invites carry it), the online switch and the radio gateway.
 */
@Composable
private fun PartyPage() {
    val party = LocalParty.current
    val s by party.state.collectAsState()
    var editMe by remember { mutableStateOf(false) }
    var editServer by remember { mutableStateOf(false) }
    var reshare by remember { mutableStateOf(false) }
    val look = s.me ?: s.look
    if (!party.watchOnly) {
        ListItem(
            colors = clear(),
            modifier = Modifier.clickable { editMe = true },
            leadingContent = { PartyAvatar(look?.name.orEmpty(), look?.color ?: PartyPalette.PRESETS[0], look?.icon ?: 0, 40.dp) },
            headlineContent = { Text(T.settings.yourNameAndLook) },
            supportingContent = { Text(look?.name?.ifBlank { null } ?: T.settings.noNameYet) },
            trailingContent = { TextButton(onClick = { editMe = true }) { Text(T.common.edit) } },
        )
        Note(T.settings.howOthersSeeYouNext)
    }
    val server = s.party?.server ?: if (s.party == null) party.defaultServer else null
    if (!party.watchOnly) {
        ListItem(
            colors = clear(),
            leadingContent = { Icon(Icons.Filled.Dns, contentDescription = null) },
            headlineContent = { Text(T.party.server) },
            supportingContent = { Text(server?.let(PartyRelays::host) ?: T.party.publicServers) },
            trailingContent = { TextButton(onClick = { editServer = true }) { Text(T.offline.change) } },
        )
        Note(T.settings.serverForParties)
        if (reshare) Note(T.party.shareInviteAgain, error = true)
    }
    if (party.hasRadio && !party.watchOnly) {
        SwitchItem(T.party.online, s.onlineEnabled) { party.setOnlineEnabled(it) }
        if (s.radioName != null) SwitchItem(T.party.gateway, s.gateway, T.party.gatewaySubtitle) { party.setGateway(it) }
    }
    if (editMe) {
        EditMeDialog(
            look?.name.orEmpty(), look?.color ?: PartyPalette.PRESETS[0], look?.icon ?: 0,
            onDismiss = { editMe = false }, title = T.settings.yourNameAndLook,
        ) { n, c, i ->
            editMe = false
            party.setProfile(n, c, i)
        }
    }
    if (editServer) {
        ServerDialog(
            server,
            onSave = { new ->
                editServer = false
                if (new != server) {
                    party.setServer(new)
                    reshare = s.party != null
                }
            },
            onDismiss = { editServer = false },
        )
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = clear(),
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

/** List lines on the sheet's own colour. */
@Composable
private fun clear() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Choice(selected: Boolean, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
