package app.maparound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.maparound.i18n.I18n
import app.maparound.i18n.Language
import app.maparound.i18n.T

/** The guides on MapAround's website: one page per topic, in English and Hebrew (docs/guide/, docs/he/guide/). */
object Guides {
    private const val SITE = "https://adam2314.github.io/maparound"

    const val OFFLINE_MAPS = "offline-maps"
    const val RASTER_MAPS = "raster-maps"
    const val ELEVATION = "elevation"
    const val OFFLINE_ROUTING = "offline-routing"
    const val MARKED_TRAILS = "marked-trails"
    const val PARTY_SERVER = "party-server"

    /** The guide [topic]'s page in the app's language. */
    fun url(topic: String): String = if (I18n.language == Language.HE) "$SITE/he/guide/$topic" else "$SITE/guide/$topic"

    /** The map legend ("legend") or the maps' data schema ("schema") in the app's language (docs/map, tools/map-docs). */
    fun map(page: String): String = if (I18n.language == Language.HE) "$SITE/he/map/$page" else "$SITE/map/$page"
}

/** The credit for the elevation data from [template] (null: the default's own credit, else its server). */
fun terrainCredit(template: String?): String =
    app.maparound.data.ElevationSources.customHost(template)?.let { T.map.terrainFrom(it) } ?: T.map.terrainAttribution

/** A link to a data source's website: its name and address. */
data class SourceSite(val name: String, val url: String)

/**
 * What a section is about ([text]), with its source's website ([site], when it has one) and the
 * guide to it ([guide]: a [Guides] topic), both opened in the browser.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionInfo(text: String, site: SourceSite?, guide: String, modifier: Modifier = Modifier, sidePadding: Dp = 16.dp) {
    val platform = LocalPlatform.current
    Column(modifier.fillMaxWidth().padding(horizontal = sidePadding)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (site != null) {
                TextButton(onClick = { platform.openUrl(site.url) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(site.name)
                }
            }
            TextButton(onClick = { platform.openUrl(Guides.url(guide)) }) {
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(T.offline.guide)
            }
        }
    }
}
