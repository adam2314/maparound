package app.maparound.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.maparound.routing.GeoPoint
import app.maparound.search.Commons
import app.maparound.shared.util.urlEncode
import kotlinx.coroutines.CancellationException

/**
 * A photo from Wikimedia Commons over the whole screen, bigger once its page answers, with its
 * description and credit (author, licence), a link to its page, and where it was taken.
 */
@Composable
fun PhotoViewer(photo: Commons.Photo, onDismiss: () -> Unit, onShowOnMap: ((GeoPoint, String) -> Unit)? = null) {
    val platform = LocalPlatform.current
    var details by remember(photo) { mutableStateOf<Commons.Details?>(null) }
    var failed by remember(photo) { mutableStateOf(false) }
    LaunchedEffect(photo) {
        try {
            details = Commons.details(photo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    val page = details?.pageUrl ?: ("https://${photo.site}/wiki/" + urlEncode(photo.file.replace(' ', '_')).replace("+", "%20"))
    val light = ButtonDefaults.textButtonColors(contentColor = Color.White)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        photo.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 16.dp),
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // The small picture at once, the bigger one over it when it's in.
                    RemoteImage(photo.thumb, photo.name, Modifier.fillMaxSize(), ContentScale.Fit, placeholder = Color.Black)
                    details?.image?.let { RemoteImage(it, null, Modifier.fillMaxSize(), ContentScale.Fit, placeholder = Color.Transparent) }
                }
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    details?.description?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    val credit = listOfNotNull(details?.author?.let { "Photo: $it" }, details?.license).joinToString(" · ")
                    Text(
                        when {
                            credit.isNotEmpty() -> "$credit · Wikimedia Commons"
                            failed -> "From Wikimedia Commons; its author and licence need a connection."
                            else -> "From Wikimedia Commons"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Row {
                        TextButton(onClick = { platform.openUrl(page) }, colors = light) { Text("Photo's page") }
                        val lat = photo.lat
                        val lon = photo.lon
                        if (onShowOnMap != null && lat != null && lon != null) {
                            TextButton(onClick = { onShowOnMap(GeoPoint(lat, lon), photo.name) }, colors = light) { Text("Where it was taken") }
                        }
                    }
                }
            }
        }
    }
}
