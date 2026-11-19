package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.share.SharedLinks

/** "Share location" choices for one point. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointShareSheet(lat: Double, lon: Double, label: String?, onDismiss: () -> Unit) {
    val platform = LocalPlatform.current
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                label ?: ltr(Fmt.point(lat, lon)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
            // The link chosen in Settings → Sharing, only it (or all of them).
            val linkHint = when (SharedLinks.shareLink) {
                app.maparound.share.ShareLink.MAPAROUND -> T.map.sendMapAroundLinkHint
                app.maparound.share.ShareLink.GOOGLE -> T.map.sendGoogleLinkHint
                app.maparound.share.ShareLink.OSM -> T.map.sendOsmLinkHint
                app.maparound.share.ShareLink.ALL -> T.map.sendAsLinkHint
            }
            Option(Icons.Filled.Link, T.map.sendAsLink, linkHint) {
                onDismiss(); platform.sharePointText(lat, lon, label)
            }
            Option(Icons.Filled.Description, T.map.sendAsGpx, T.map.sendAsGpxHint) {
                onDismiss(); platform.sharePointGpx(lat, lon, label)
            }
            Option(Icons.AutoMirrored.Filled.OpenInNew, T.map.openElsewhere, T.map.openElsewhereHint) {
                onDismiss(); platform.openPointElsewhere(lat, lon, label)
            }
            Option(Icons.Filled.ContentCopy, T.map.copyCoordinates, ltr(Fmt.point(lat, lon))) {
                onDismiss(); clipboard.setText(AnnotatedString(SharedLinks.pointText(lat, lon, label)))
            }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}
