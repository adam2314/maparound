package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.maparound.data.Prefs
import app.maparound.map.TileSources
import app.maparound.raster.RasterLibrary
import app.maparound.raster.RasterMap
import app.maparound.routing.GeoPoint
import app.maparound.routing.MapFocus

/** Shows a raster map as the background of the offline map and moves there. */
fun showRasterOnMap(prefs: Prefs, map: RasterMap) {
    prefs.setBaseLayer(TileSources.OFFLINE_ID)
    // A scanned map already has its own roads and names: start without the vector map on top.
    if (prefs.mapBackground.value != Prefs.BACKGROUND_RASTER + map.id) prefs.setVectorOnBackground(false)
    prefs.setMapBackground(Prefs.BACKGROUND_RASTER + map.id)
    MapFocus.show(listOf(GeoPoint(map.north, map.west), GeoPoint(map.south, map.east)))
}

fun removeRaster(prefs: Prefs, rasters: RasterLibrary, map: RasterMap) {
    if (prefs.mapBackground.value == Prefs.BACKGROUND_RASTER + map.id) prefs.setMapBackground(Prefs.BACKGROUND_NONE)
    rasters.remove(map.id)
}

/** The "Raster maps" part of the Offline tab. */
fun LazyListScope.rasterMapsSection(
    maps: List<RasterMap>,
    onAdd: () -> Unit,
    onShow: (RasterMap) -> Unit,
    onRemove: (RasterMap) -> Unit,
    header: @Composable () -> Unit,
) {
    item { header() }
    if (maps.isEmpty()) {
        item {
            ListItem(
                modifier = Modifier.clickable(onClick = onAdd),
                leadingContent = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
                headlineContent = { Text("Add a raster map (.rmap)") },
                supportingContent = {
                    Text("TwoNav / CompeGPS maps, e.g. scanned 1:50,000 topo sheets. Shown under or instead of the vector map; large files are read in place, not copied.")
                },
            )
        }
    }
    items(maps, key = { it.id }) { m ->
        var menu by remember { mutableStateOf(false) }
        ListItem(
            modifier = Modifier.clickable { onShow(m) },
            leadingContent = { Icon(Icons.Filled.Image, contentDescription = null) },
            headlineContent = { Text(m.name) },
            supportingContent = {
                Text("${Fmt.fileSize(m.sizeBytes)} · ${m.width}×${m.height} px · ${m.levels} levels · ${m.projection}")
            },
            trailingContent = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${m.name}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Show on map") }, leadingIcon = { Icon(Icons.Filled.Map, null) }, onClick = { menu = false; onShow(m) })
                        DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onRemove(m) })
                    }
                }
            },
        )
    }
    if (maps.isNotEmpty()) {
        item {
            TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add another raster map")
            }
        }
    }
}

/** Message for a failed add. */
fun rasterError(e: Throwable): String = "Couldn't add the map: " + (e.message ?: e::class.simpleName ?: "error")

