package app.maparound.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.maparound.data.Catalog
import app.maparound.data.CatalogEntry
import app.maparound.data.DownloadInfo
import app.maparound.data.LocalMap
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.data.MapNames
import app.maparound.data.MapPos
import app.maparound.data.mapBounds
import app.maparound.map.TileSources
import app.maparound.raster.LocalRasters
import app.maparound.routing.Segments
import app.maparound.terrain.GeoBounds
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Listing {
    data object Loading : Listing
    data class Ready(val entries: List<CatalogEntry>) : Listing
    data class Failed(val message: String) : Listing
}

private const val LARGE_DOWNLOAD = 200L shl 20 // ask before downloading > 200 MB

private val WORLD_ENTRY = CatalogEntry(
    path = "world/world.map",
    isDirectory = false,
    sizeLabel = "3.1M",
    sizeBytes = Catalog.parseSize("3.1M"),
    modified = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapsScreen(snackbar: SnackbarHostState, onShowOnMap: () -> Unit) {
    val scope = rememberCoroutineScope()
    val offline = LocalOfflineData.current
    val repo = offline.maps
    val downloader = offline.downloads
    val catalog = offline.catalog
    val prefs = LocalPrefs.current
    val rasters = LocalRasters.current

    val installed by repo.files.collectAsState()
    val wifiOnly by prefs.wifiOnly.collectAsState()
    val downloads by produceState(initialValue = emptyList<DownloadInfo>()) {
        while (true) {
            value = withContext(Dispatchers.Default) {
                offline.routing.poll()
                downloader.poll()
            }
            delay(1000)
        }
    }

    var path by rememberSaveable { mutableStateOf("") }
    var retryToken by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf<Listing>(Listing.Loading) }
    LaunchedEffect(path, retryToken) {
        listing = Listing.Loading
        listing = try {
            Listing.Ready(catalog.list(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Listing.Failed(e.message ?: "Network error")
        }
    }
    var filter by rememberSaveable(path) { mutableStateOf("") }
    var confirmEntry by remember { mutableStateOf<CatalogEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<LocalMap?>(null) }
    var terrainTarget by remember { mutableStateOf<LocalMap?>(null) }
    var routingTarget by remember { mutableStateOf<LocalMap?>(null) }
    var trailsTarget by remember { mutableStateOf<LocalMap?>(null) }
    var confirmDeleteTerrain by remember { mutableStateOf(false) }
    var confirmDeleteTrails by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    val terrainState by offline.terrain.state.collectAsState()
    val routingState by offline.routing.state.collectAsState()
    val trailState by offline.trails.state.collectAsState()
    // Map file name -> which extras are fully installed for it.
    val extras by produceState(emptyMap<String, Extras>(), installed, terrainState.version, routingState.installed, trailState.version) {
        value = withContext(Dispatchers.Default) {
            installed.filterNot { it.isWorld }.mapNotNull { m ->
                mapBounds(m.path)?.let { bb ->
                    m.fileName to Extras(
                        terrain = offline.terrain.missingCells(bb).isEmpty(),
                        // The block with the map's centre is enough to route most trips there.
                        routing = offline.routing.isInstalled(Segments.nameFor(bb.centerLat, bb.centerLon)),
                        trails = offline.trails.missingCells(bb).isEmpty(),
                    )
                }
            }.toMap()
        }
    }

    PlatformBackHandler(enabled = path.isNotEmpty()) { path = parentPath(path) }

    val importLauncher = repo.rememberImporter(onStart = { importing = true }) { result ->
        importing = false
        scope.launch {
            snackbar.showSnackbar(
                result.fold({ "Imported ${it.title}" }, { "Import failed: ${it.message}" }),
            )
        }
    }

    fun startDownload(entry: CatalogEntry) {
        val error = downloader.enqueue(entry, catalog.urlFor(entry), wifiOnly)
        scope.launch { snackbar.showSnackbar(error ?: "Downloading ${entry.title}…") }
    }

    fun requestDownload(entry: CatalogEntry) {
        if ((entry.sizeBytes ?: 0L) > LARGE_DOWNLOAD) confirmEntry = entry else startDownload(entry)
    }

    fun showOnMap(map: LocalMap) {
        scope.launch {
            val bbox = withContext(Dispatchers.Default) { mapBounds(map.path) }
            if (bbox != null) prefs.saveLastPosition(MapPos(bbox.centerLat, bbox.centerLon, zoomToFit(bbox)))
            prefs.setBaseLayer(TileSources.OFFLINE_ID)
            onShowOnMap()
        }
    }

    val rasterMaps by rasters.maps.collectAsState()
    val addRaster = rasters.rememberPicker { r ->
        r.fold(
            { showRasterOnMap(prefs, it); onShowOnMap() },
            { e -> scope.launch { snackbar.showSnackbar(rasterError(e)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Offline maps") },
            actions = {
                IconButton(onClick = { importLauncher() }) {
                    Icon(Icons.Filled.FileOpen, contentDescription = "Import a .map file")
                }
                IconButton(onClick = addRaster) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Add a raster map (.rmap)")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            val hasWorld = installed.any { it.isWorld } || downloads.any { MapNames.isWorld(it.fileName) }
            if (!hasWorld) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("World overview", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "3 MB background map for zoomed-out views. Recommended.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(onClick = { requestDownload(WORLD_ENTRY) }) { Text("Get") }
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    "On this device",
                    if (installed.isEmpty()) null else Fmt.fileSize(installed.sumOf { it.sizeBytes }),
                )
            }
            if (installed.isEmpty()) {
                item {
                    Text(
                        "No offline maps yet. Pick a region below, or import a mapsforge .map file " +
                            "(e.g. from OpenAndroMaps) with the folder button above.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(installed, key = { it.fileName }) { map ->
                val ex = extras[map.fileName]
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickable { showOnMap(map) },
                    leadingContent = { Icon(Icons.Filled.Map, contentDescription = null) },
                    headlineContent = { Text(map.title) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                map.region,
                                Fmt.fileSize(map.sizeBytes),
                                "terrain ✓".takeIf { ex?.terrain == true },
                                "routing ✓".takeIf { ex?.routing == true },
                                "trails ✓".takeIf { ex?.trails == true },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${map.title}")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!map.isWorld) {
                                    DropdownMenuItem(
                                        text = { Text("Contours & hill shading") },
                                        leadingIcon = { Icon(Icons.Filled.Terrain, null) },
                                        trailingIcon = { if (ex?.terrain == true) Icon(Icons.Filled.Check, null) },
                                        onClick = { menu = false; terrainTarget = map },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Offline routing") },
                                        leadingIcon = { Icon(Icons.Filled.Route, null) },
                                        trailingIcon = { if (ex?.routing == true) Icon(Icons.Filled.Check, null) },
                                        onClick = { menu = false; routingTarget = map },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Marked trails") },
                                        leadingIcon = { Icon(Icons.Filled.Hiking, null) },
                                        trailingIcon = { if (ex?.trails == true) Icon(Icons.Filled.Check, null) },
                                        onClick = { menu = false; trailsTarget = map },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Delete map") },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menu = false; deleteTarget = map },
                                )
                            }
                        }
                    },
                )
            }

            // ---- raster maps (RMAP), read in place ----
            rasterMapsSection(rasterMaps, addRaster, { showRasterOnMap(prefs, it); onShowOnMap() }, { removeRaster(prefs, rasters, it) }) {
                SectionHeader("Raster maps", if (rasterMaps.isEmpty()) null else Fmt.fileSize(rasterMaps.sumOf { it.sizeBytes }))
            }

            // ---- terrain (elevation data for contours & hill shading) ----
            val progress = terrainState.progress
            if (terrainState.tiles > 0 || progress != null) {
                item {
                    SectionHeader(
                        "Terrain",
                        if (terrainState.tiles > 0) Fmt.fileSize(terrainState.bytes) else null,
                    )
                }
                if (progress != null) {
                    item {
                        ListItem(
                            headlineContent = { Text("Downloading terrain · ${progress.label}") },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                                    Text(
                                        "${progress.done} / ${progress.total} tiles" +
                                            (if (progress.failed > 0) " · ${progress.failed} failed" else "") +
                                            " · keep MapAround open",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { offline.terrain.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Stop terrain download")
                                }
                            },
                        )
                    }
                }
                if (terrainState.tiles > 0) {
                    item {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Terrain, contentDescription = null) },
                            headlineContent = { Text("Elevation data") },
                            supportingContent = { Text("${terrainState.tiles} tiles (1°×1°) · contour lines & hill shading") },
                            trailingContent = {
                                IconButton(onClick = { confirmDeleteTerrain = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete all terrain data")
                                }
                            },
                        )
                    }
                }
            } else if (installed.any { !it.isWorld }) {
                item {
                    Text(
                        "Tip: use ⋮ next to a map to add contour lines & hill shading, offline routing and marked trails.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // ---- offline routing data ----
            if (routingState.installed.isNotEmpty() || routingState.downloads.isNotEmpty()) {
                item {
                    SectionHeader(
                        "Offline routing",
                        Fmt.fileSize(routingState.installed.sumOf { it.bytes }).takeIf { routingState.installed.isNotEmpty() },
                    )
                }
                items(routingState.downloads, key = { "rd-${it.id}" }) { d ->
                    ListItem(
                        headlineContent = { Text("Routing data ${d.name}") },
                        supportingContent = {
                            val f = d.fraction
                            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        },
                        trailingContent = {
                            IconButton(onClick = { offline.routing.cancel(d.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel download")
                            }
                        },
                    )
                }
                items(routingState.installed, key = { "rs-${it.name}" }) { s ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Route, contentDescription = null) },
                        headlineContent = { Text("Routing data ${s.name}") },
                        supportingContent = { Text("5°×5° block · ${Fmt.fileSize(s.bytes)}") },
                        trailingContent = {
                            IconButton(onClick = { offline.routing.delete(s.name) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete routing data ${s.name}")
                            }
                        },
                    )
                }
            }

            // ---- marked trails ----
            val trailProgress = trailState.progress
            if (trailState.cells > 0 || trailProgress != null) {
                item {
                    SectionHeader(
                        "Marked trails",
                        Fmt.fileSize(trailState.bytes).takeIf { trailState.cells > 0 },
                    )
                }
                if (trailProgress != null) {
                    item {
                        ListItem(
                            headlineContent = { Text("Downloading trails · ${trailProgress.label}") },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(progress = { trailProgress.fraction }, modifier = Modifier.fillMaxWidth())
                                    Text(
                                        "${trailProgress.done} / ${trailProgress.total} areas" +
                                            (if (trailProgress.failed > 0) " · ${trailProgress.failed} failed" else "") +
                                            " · keep MapAround open",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            trailingContent = {
                                IconButton(onClick = { offline.trails.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Stop trail download")
                                }
                            },
                        )
                    }
                }
                if (trailState.cells > 0) {
                    item {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Hiking, contentDescription = null) },
                            headlineContent = { Text("Trail data") },
                            supportingContent = { Text("${trailState.cells} areas (1°×1°) · shown in the Topo style") },
                            trailingContent = {
                                IconButton(onClick = { confirmDeleteTrails = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete all trail data")
                                }
                            },
                        )
                    }
                }
            }

            if (downloads.isNotEmpty()) {
                item { SectionHeader("Downloading", null) }
                items(downloads, key = { "dl-${it.id}" }) { d -> DownloadRow(d, onCancel = { downloader.cancel(d.id) }) }
            }

            item { SectionHeader("Download maps", "OpenStreetMap data via mapsforge.org") }
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { prefs.setWifiOnly(!wifiOnly) }.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Download over Wi‑Fi only", Modifier.weight(1f))
                    Switch(checked = wifiOnly, onCheckedChange = { prefs.setWifiOnly(it) })
                }
            }
            item { Breadcrumbs(path, onNavigate = { path = it }) }
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when (val l = listing) {
                Listing.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is Listing.Failed -> item {
                    MessageCard(
                        icon = Icons.Filled.CloudOff,
                        title = "Can't load the map list",
                        body = l.message,
                        action = "Retry",
                        onAction = { retryToken++ },
                        modifier = Modifier.padding(16.dp),
                    )
                }
                is Listing.Ready -> {
                    val visible = l.entries.filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) }
                    items(visible, key = { it.path }) { entry ->
                        if (entry.isDirectory) {
                            ListItem(
                                modifier = Modifier.clickable { path = entry.path },
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                headlineContent = { Text(entry.title) },
                                supportingContent = { Text(if (path.isEmpty()) "Continent" else "Regions") },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            )
                        } else {
                            CatalogMapRow(
                                entry = entry,
                                local = installed.firstOrNull { it.fileName == entry.localFileName },
                                download = downloads.firstOrNull { it.fileName == entry.localFileName },
                                onDownload = { requestDownload(entry) },
                            )
                        }
                    }
                }
            }
        }
    }

    confirmEntry?.let { entry ->
        val free = Fmt.fileSize(repo.freeBytes())
        AlertDialog(
            onDismissRequest = { confirmEntry = null },
            title = { Text("Download ${entry.title}?") },
            text = {
                Text(
                    "This map is ${entry.sizeLabel ?: "large"}B ($free free on the device). " +
                        if (wifiOnly) "It will download over Wi‑Fi." else "Consider using Wi‑Fi.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEntry = null
                    startDownload(entry)
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { confirmEntry = null }) { Text("Cancel") } },
        )
    }

    routingTarget?.let { map ->
        RoutingDialog(
            map = map,
            onDismiss = { routingTarget = null },
            onStart = { names ->
                routingTarget = null
                val error = offline.routing.download(names, wifiOnly)
                scope.launch { snackbar.showSnackbar(error ?: "Downloading routing data for ${map.title}…") }
            },
        )
    }

    trailsTarget?.let { map ->
        TrailsDialog(
            map = map,
            onDismiss = { trailsTarget = null },
            onStart = { bbox ->
                trailsTarget = null
                val error = offline.trails.download(map.title, bbox)
                scope.launch { snackbar.showSnackbar(error ?: "Downloading marked trails for ${map.title}…") }
            },
        )
    }

    if (confirmDeleteTrails) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTrails = false },
            title = { Text("Delete trail data?") },
            text = { Text("Marked trails are then shown only online, as an overlay.") },
            confirmButton = {
                TextButton(onClick = {
                    offline.trails.deleteAll()
                    confirmDeleteTrails = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTrails = false }) { Text("Cancel") } },
        )
    }

    terrainTarget?.let { map ->
        TerrainDialog(
            map = map,
            onDismiss = { terrainTarget = null },
            onStart = { bbox, high ->
                terrainTarget = null
                val error = offline.terrain.download(map.title, bbox, high)
                scope.launch { snackbar.showSnackbar(error ?: "Downloading terrain for ${map.title}…") }
            },
        )
    }

    if (confirmDeleteTerrain) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTerrain = false },
            title = { Text("Delete terrain data?") },
            text = {
                Text(
                    "Removes contour lines and hill shading for all maps and frees " +
                        "${Fmt.fileSize(terrainState.bytes)}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    offline.terrain.deleteAll()
                    confirmDeleteTerrain = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTerrain = false }) { Text("Cancel") } },
        )
    }

    deleteTarget?.let { map ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${map.title}?") },
            text = { Text("Frees ${Fmt.fileSize(map.sizeBytes)}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(map)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = path.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onNavigate("") }, enabled = segments.isNotEmpty()) { Text("All regions") }
        segments.forEachIndexed { i, s ->
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
            TextButton(
                onClick = { onNavigate(segments.take(i + 1).joinToString("/") + "/") },
                enabled = i < segments.lastIndex,
            ) { Text(MapNames.pretty(s)) }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadInfo, onCancel: () -> Unit) {
    ListItem(
        headlineContent = { Text(d.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val progress = d.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val amount = if (d.totalBytes > 0) {
                    "${Fmt.fileSize(d.downloadedBytes)} / ${Fmt.fileSize(d.totalBytes)}"
                } else {
                    Fmt.fileSize(d.downloadedBytes)
                }
                Text("${d.statusText} · $amount", style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel download") }
        },
    )
}

@Composable
private fun CatalogMapRow(entry: CatalogEntry, local: LocalMap?, download: DownloadInfo?, onDownload: () -> Unit) {
    val updateAvailable = local != null && entry.modified.let { it != null && it > local.modified }
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val date = entry.modified?.let {
                Fmt.date(it)
            }
            Text(listOfNotNull(entry.sizeLabel?.let { "${it}B" }, date?.let { "updated $it" }).joinToString(" · "))
        },
        trailingContent = {
            when {
                download != null -> {
                    val p = download.progress
                    if (p != null) {
                        CircularProgressIndicator(progress = { p }, modifier = Modifier.size(28.dp))
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                updateAvailable -> TextButton(onClick = onDownload) { Text("Update") }
                local != null -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Installed",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download ${entry.title}")
                }
            }
        },
    )
}

@Composable
private fun TerrainDialog(map: LocalMap, onDismiss: () -> Unit, onStart: (GeoBounds, Boolean) -> Unit) {
    val terrain = LocalOfflineData.current.terrain
    // bbox, total cells, missing cells
    val info by produceState<Triple<GeoBounds, Int, Int>?>(null, map) {
        value = withContext(Dispatchers.Default) {
            mapBounds(map.path)?.let { bb -> Triple(bb, terrain.cellsFor(bb).size, terrain.missingCells(bb).size) }
        }
    }
    val prefs = LocalPrefs.current
    var high by remember { mutableStateOf(prefs.terrainHighDetail) }
    val current = info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contours & hill shading") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Elevation data for ${map.title}, in 1°×1° tiles from the open Terrain Tiles dataset. " +
                        "Contour lines are drawn from it on your phone, so they work offline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    current.third == 0 -> Text(
                        "Installed ✓ (${current.second} tiles)",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    else -> {
                        Text(
                            "${current.third} of ${current.second} tiles to download",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        DetailOption(
                            selected = high,
                            title = "High detail (30 m)",
                            subtitle = "Up to ${Fmt.fileSize(terrain.estimateBytes(current.third, true))}",
                            onClick = { high = true },
                        )
                        DetailOption(
                            selected = !high,
                            title = "Standard (90 m)",
                            subtitle = "Up to ${Fmt.fileSize(terrain.estimateBytes(current.third, false))} · 9× smaller, less detail",
                            onClick = { high = false },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (current != null && current.third > 0) {
                TextButton(onClick = { onStart(current.first, high) }) { Text("Download") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (current != null && current.third > 0) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DetailOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Which offline extras are installed for a map. */
private data class Extras(val terrain: Boolean, val routing: Boolean, val trails: Boolean)

@Composable
private fun RoutingDialog(map: LocalMap, onDismiss: () -> Unit, onStart: (List<String>) -> Unit) {
    val routing = LocalOfflineData.current.routing
    // (missing block names, their sizes or null when the server could not be reached)
    val info by produceState<Pair<List<String>, Map<String, Long>?>?>(null, map) {
        val bb = withContext(Dispatchers.Default) { mapBounds(map.path) }
        if (bb == null) {
            value = emptyList<String>() to null
            return@produceState
        }
        val sizes = runCatching { routing.remoteSizes() }.getOrNull()
        val names = routing.segmentsFor(bb).filter { sizes == null || it in sizes } // sea-only blocks don't exist
        value = names.filterNot(routing::isInstalled) to sizes
    }
    val current = info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Offline routing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Lets the route planner work without internet in ${map.title}. Without it, routes are " +
                        "calculated online by brouter.de. The data comes in 5°×5° blocks, so one block can cover several countries.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    current.first.isEmpty() -> Text(
                        "Installed ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
                    )
                    else -> {
                        val sizes = current.second
                        current.first.forEach { name ->
                            Text(
                                "$name" + (sizes?.get(name)?.let { " · " + Fmt.fileSize(it) } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (sizes != null) {
                            Text(
                                "Total ${Fmt.fileSize(current.first.sumOf { sizes[it] ?: 0L })}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        } else {
                            Text("Couldn't check sizes: no connection to brouter.de.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (current != null && current.first.isNotEmpty()) {
                TextButton(onClick = { onStart(current.first) }) { Text("Download") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (current != null && current.first.isNotEmpty()) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun TrailsDialog(map: LocalMap, onDismiss: () -> Unit, onStart: (GeoBounds) -> Unit) {
    val trails = LocalOfflineData.current.trails
    // bbox, total cells, missing cells
    val info by produceState<Triple<GeoBounds, Int, Int>?>(null, map) {
        value = withContext(Dispatchers.Default) {
            mapBounds(map.path)?.let { bb -> Triple(bb, trails.cellsFor(bb).size, trails.missingCells(bb).size) }
        }
    }
    val current = info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Marked trails") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Waymarked hiking routes from OpenStreetMap (for example red-white-red or the colour-marked trails), " +
                        "drawn in their colours with their names in the Topo style. Without this download, they are " +
                        "shown only online, as an overlay.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    current.third == 0 -> Text(
                        "Installed ✓ (${current.second} areas)", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    else -> {
                        Text("${current.third} of ${current.second} areas (1°×1°) to download", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Downloaded from the Overpass API one area at a time. Busy hiking regions like the Alps can take " +
                                "several minutes and up to ~50 MB per area; on the phone it is about 10× smaller.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (current != null && current.third > 0) {
                TextButton(onClick = { onStart(current.first) }) { Text("Download") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (current != null && current.third > 0) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun parentPath(path: String): String {
    val parent = path.removeSuffix("/").substringBeforeLast('/', "")
    return if (parent.isEmpty()) "" else "$parent/"
}

/** Rough zoom level that shows the whole bounding box on a phone screen. */
private fun zoomToFit(bb: GeoBounds): Int {
    val span = max(bb.maxLon - bb.minLon, (bb.maxLat - bb.minLat) * 1.4).coerceAtLeast(0.005)
    return floor(log2(360.0 * 1.5 / span)).toInt().coerceIn(2, 16)
}
