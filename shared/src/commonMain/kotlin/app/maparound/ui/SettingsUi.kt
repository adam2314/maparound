package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.maparound.data.LocalPrefs
import app.maparound.i18n.Language
import app.maparound.i18n.T
import app.maparound.shared.util.deviceLanguage

/** The gear for the app's settings (in the Library's top bar), and its sheet. */
@Composable
fun SettingsButton() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Filled.Settings, contentDescription = T.common.settings) }
    if (open) SettingsSheet(onDismiss = { open = false })
}

/** The app's settings: its language (the device's, or one the app speaks); and supporting MapAround. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val prefs = LocalPrefs.current
    val choice by prefs.language.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(T.common.settings, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text(
                T.common.language,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            val device = Language.of(deviceLanguage()) ?: Language.EN
            Choice(choice.isEmpty(), T.common.deviceLanguage, T.common.deviceLanguageNow(device.nativeName)) { prefs.setLanguage("") }
            for (l in Language.entries) Choice(choice == l.code, l.nativeName, null) { prefs.setLanguage(l.code) }
            // What donations pay for, on the website.
            val platform = LocalPlatform.current
            HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp))
            ListItem(
                modifier = Modifier.clickable { platform.openUrl(Guides.page(Guides.SUPPORT)) },
                leadingContent = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text(T.common.supportMapAround) },
                supportingContent = { Text(T.common.supportSubtitle) },
            )
        }
    }
}

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
