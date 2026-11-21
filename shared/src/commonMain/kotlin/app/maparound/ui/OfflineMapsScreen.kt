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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.maparound.data.ElevationSources
import app.maparound.data.ImportedMap
import app.maparound.data.Terrain3dSource
import app.maparound.data.Catalog
import app.maparound.data.CatalogEntry
import app.maparound.data.DownloadInfo
import app.maparound.data.LocalMap
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.data.MapAroundMaps
import app.maparound.data.MapNames
import app.maparound.data.MapPos
import app.maparound.data.mapBounds
import app.maparound.i18n.T
import app.maparound.i18n.ltr
import app.maparound.map.TileSources
import app.maparound.raster.LocalRasters
import app.maparound.routing.Segments
import app.maparound.routing.MapFocus
import app.maparound.routing.GeoPoint
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
    data class Ready(val entries: List<CatalogEntry>, val attribution: String) : Listing
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
    // The list to download from: MapAround's, or (switch) the older one it offers too (mapsforge.org).
    var basicList by rememberSaveable { mutableStateOf(false) }
    val catalog = offline.catalog.alternative?.takeIf { basicList } ?: offline.catalog
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
    LaunchedEffect(path, retryToken, catalog) {
        listing = Listing.Loading
        listing = try {
            catalog.list(path).let { Listing.Ready(it, catalog.attribution) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Listing.Failed(e.message ?: T.offline.networkError)
        }
    }
    // Cleared whenever another folder opens.
    var filter by rememberSaveable { mutableStateOf("") }
    fun openFolder(p: String) {
        path = p
        filter = ""
    }
    val listState = rememberLazyListState()
    // A region the map asked for: its folder, filtered down to it, scrolled to.
    val focus by OfflineFocus.region.collectAsState()
    var scrollToList by remember { mutableStateOf(false) }
    LaunchedEffect(focus) {
        val region = focus ?: return@LaunchedEffect
        OfflineFocus.region.value = null
        path = region.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        filter = MapNames.pretty(region.substringAfterLast('/'))
        scrollToList = true
    }
    LaunchedEffect(listing, scrollToList) {
        if (scrollToList && listing !is Listing.Loading) {
            scrollToList = false
            val n = listState.layoutInfo.totalItemsCount
            if (n > 0) listState.animateScrollToItem(n - 1)
        }
    }
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
                val builtInTrails = MapAroundMaps.hasTrails(m.path)
                mapBounds(m.path)?.let { bb ->
                    m.fileName to Extras(
                        terrain = offline.terrain.missingCells(bb).isEmpty(),
                        // The block with the map's centre is enough to route most trips there.
                        routing = offline.routing.isInstalled(Segments.nameFor(bb.centerLat, bb.centerLon)),
                        trails = offline.trails.missingCells(bb).isEmpty() || builtInTrails,
                        builtInTrails = builtInTrails,
                    )
                }
            }.toMap()
        }
    }

    PlatformBackHandler(enabled = path.isNotEmpty()) { openFolder(parentPath(path)) }

    // One picker for every kind of map file: vector maps are copied in, raster maps read where they are.
    val importLauncher = repo.rememberAnyImporter(onStart = { importing = true }) { result ->
        importing = false
        result.fold(
            { added ->
                when (added) {
                    is ImportedMap.Vector -> scope.launch { snackbar.showSnackbar(T.offline.imported(added.map.title)) }
                    is ImportedMap.Raster -> { showRasterOnMap(prefs, added.map); onShowOnMap() }
                }
            },
            { e -> scope.launch { snackbar.showSnackbar(T.offline.importFailed(e.message)) } },
        )
    }
    val elevationSource by prefs.elevationSource.collectAsState()
    val terrain3dSource by prefs.terrain3dSource.collectAsState()
    var editElevationSource by remember { mutableStateOf(false) }

    fun startDownload(entry: CatalogEntry) {
        val error = downloader.enqueue(entry, catalog.urlFor(entry), wifiOnly)
        scope.launch { snackbar.showSnackbar(error ?: T.offline.downloadingMap(entry.title)) }
    }

    fun requestDownload(entry: CatalogEntry) {
        if ((entry.sizeBytes ?: 0L) > LARGE_DOWNLOAD) confirmEntry = entry else startDownload(entry)
    }

    fun showOnMap(map: LocalMap) {
        scope.launch {
            val bbox = withContext(Dispatchers.Default) { mapBounds(map.path) }
            if (bbox != null) {
                prefs.saveLastPosition(MapPos(bbox.centerLat, bbox.centerLon, zoomToFit(bbox)))
                // A map already open (beside the web app's panel) goes there too.
                MapFocus.show(listOf(GeoPoint(bbox.maxLat, bbox.minLon), GeoPoint(bbox.minLat, bbox.maxLon)))
            }
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
            title = { Text(T.offline.screenTitle) },
            navigationIcon = { ClosePanelButton() },
            actions = {
                Tip(T.offline.importAnyMapFile) { IconButton(onClick = { importLauncher() }) {
                    Icon(Icons.Filled.FileOpen, contentDescription = T.offline.importAnyMapFile)
                } }
                SettingsButton()
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        RasterPreparingRow(rasters)

        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            val hasWorld = installed.any { it.isWorld } || downloads.any { MapNames.isWorld(it.fileName) }
            if (!hasWorld && !offline.builtInWorld) {
                item {
                    ElevatedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(T.offline.worldOverview, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    T.offline.worldOverviewText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Button(onClick = { requestDownload(WORLD_ENTRY) }) { Text(T.offline.getWorldMap) }
                        }
                    }
                }
            }

            item {
                SectionHeader(
                    T.offline.onThisDevice,
                    if (installed.isEmpty()) null else Fmt.fileSize(installed.sumOf { it.sizeBytes }),
                )
            }
            item { SectionInfo(T.offline.vectorMapsInfo, SourceSite("OpenStreetMap", "https://www.openstreetmap.org/about"), Guides.OFFLINE_MAPS) }
            if (installed.isEmpty()) {
                item {
                    Text(
                        T.offline.noMapsYet,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { importLauncher() },
                    leadingContent = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                    headlineContent = { Text(T.offline.importAnyMapFile) },
                    supportingContent = { Text(if (repo.importsRasters) T.offline.importHelpAll else T.offline.importHelpVector) },
                )
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
                                T.offline.worldNotNeeded.takeIf { map.isWorld && offline.builtInWorld },
                                T.offline.terrainTag.takeIf { ex?.terrain == true },
                                T.offline.routingTag.takeIf { ex?.routing == true },
                                T.offline.trailsTag.takeIf { ex?.trails == true },
                            ).joinToString(" · "),
                        )
                    },
                    trailingContent = {
                        Box {
                            Tip(T.offline.optionsFor(map.title)) { IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = T.offline.optionsFor(map.title))
                            } }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!map.isWorld && offline.terrainAddOn) {
                                    DropdownMenuItem(
                                        text = { Text(T.offline.contoursAndHillShading) },
                                        leadingIcon = { Icon(Icons.Filled.Terrain, null) },
                                        trailingIcon = { if (ex?.terrain == true) Icon(Icons.Filled.Check, null) },
                                        onClick = { menu = false; terrainTarget = map },
                                    )
                                }
                                if (!map.isWorld && offline.addOns) {
                                    DropdownMenuItem(
                                        text = { Text(T.offline.offlineRouting) },
                                        leadingIcon = { Icon(Icons.Filled.Route, null) },
                                        trailingIcon = { if (ex?.routing == true) Icon(Icons.Filled.Check, null) },
                                        onClick = { menu = false; routingTarget = map },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(if (ex?.builtInTrails == true) T.offline.markedTrailsInMap else T.offline.markedTrails) },
                                        leadingIcon = { Icon(Icons.Filled.Hiking, null) },
                                        trailingIcon = { if (ex?.trails == true) Icon(Icons.Filled.Check, null) },
                                        enabled = ex?.builtInTrails != true,
                                        onClick = { menu = false; trailsTarget = map },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(T.offline.deleteMap) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                    onClick = { menu = false; deleteTarget = map },
                                )
                            }
                        }
                    },
                )
            }

            // ---- raster maps (RMAP), read in place ----
            if (rasters.available) {
                val addAny = if (repo.importsRasters) ({ importLauncher() }) else addRaster
                rasterMapsSection(rasterMaps, addAny, { showRasterOnMap(prefs, it); onShowOnMap() }, { removeRaster(prefs, rasters, it) }) {
                    Column {
                        SectionHeader(T.offline.rasterMaps, if (rasterMaps.isEmpty()) null else Fmt.fileSize(rasterMaps.sumOf { it.sizeBytes }))
                        SectionInfo(T.offline.rasterHelp, SourceSite("MOBAC", "https://mobac.sourceforge.io/"), Guides.RASTER_MAPS)
                    }
                }
            }

            // ---- terrain (elevation data for contours & hill shading) ----
            val progress = terrainState.progress
            item {
                Column {
                    SectionHeader(
                        T.offline.terrain,
                        if (terrainState.tiles > 0) Fmt.fileSize(terrainState.bytes) else null,
                    )
                    val host = ElevationSources.customHost(elevationSource)
                    SectionInfo(
                        T.offline.terrainInfo,
                        SourceSite(host ?: "Terrain Tiles", ElevationSources.site(elevationSource)),
                        Guides.ELEVATION,
                    )
                    ListItem(
                        headlineContent = { Text(T.offline.terrainSource) },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    elevationSource ?: T.offline.terrainSourceDefault,
                                    terrain3dSource?.template?.takeIf { offline.webTerrain },
                                ).joinToString("\n"),
                                maxLines = 4,
                            )
                        },
                        trailingContent = { TextButton(onClick = { editElevationSource = true }) { Text(T.offline.change) } },
                    )
                }
            }
            if (terrainState.tiles == 0 && progress == null) {
                item {
                    Text(
                        T.offline.noTerrainYet,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            run {
                if (progress != null) {
                    item {
                        ListItem(
                            headlineContent = { Text(T.offline.downloadingTerrain(progress.label)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                                    Text(
                                        T.offline.terrainProgress(progress.done, progress.total, progress.failed),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            trailingContent = {
                                Tip(T.offline.stopTerrainDownload) { IconButton(onClick = { offline.terrain.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = T.offline.stopTerrainDownload)
                                } }
                            },
                        )
                    }
                }
                if (terrainState.tiles > 0) {
                    item {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Terrain, contentDescription = null) },
                            headlineContent = { Text(T.offline.elevationData) },
                            supportingContent = { Text(T.offline.elevationTiles(terrainState.tiles)) },
                            trailingContent = {
                                Tip(T.offline.deleteAllTerrain) { IconButton(onClick = { confirmDeleteTerrain = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = T.offline.deleteAllTerrain)
                                } }
                            },
                        )
                    }
                }
            }

            // ---- offline routing data ----
            if (offline.addOns) {
                item {
                    Column {
                        SectionHeader(
                            T.offline.offlineRouting,
                            Fmt.fileSize(routingState.installed.sumOf { it.bytes }).takeIf { routingState.installed.isNotEmpty() },
                        )
                        SectionInfo(T.offline.routingInfo, SourceSite("BRouter", "https://brouter.de/brouter/"), Guides.OFFLINE_ROUTING)
                    }
                }
                if (routingState.installed.isEmpty() && routingState.downloads.isEmpty()) {
                    item {
                        Text(
                            T.offline.noRoutingYet,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                items(routingState.downloads, key = { "rd-${it.id}" }) { d ->
                    ListItem(
                        headlineContent = { Text(T.offline.routingData(d.name)) },
                        supportingContent = {
                            val f = d.fraction
                            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        },
                        trailingContent = {
                            Tip(T.offline.cancelDownload) { IconButton(onClick = { offline.routing.cancel(d.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = T.offline.cancelDownload)
                            } }
                        },
                    )
                }
                items(routingState.installed, key = { "rs-${it.name}" }) { s ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Route, contentDescription = null) },
                        headlineContent = { Text(T.offline.routingData(s.name)) },
                        supportingContent = { Text(T.offline.routingBlock(Fmt.fileSize(s.bytes))) },
                        trailingContent = {
                            Tip(T.offline.deleteRoutingData(s.name)) { IconButton(onClick = { offline.routing.delete(s.name) }) {
                                Icon(Icons.Filled.Delete, contentDescription = T.offline.deleteRoutingData(s.name))
                            } }
                        },
                    )
                }
            }

            // ---- marked trails ----
            val trailProgress = trailState.progress
            if (offline.addOns) {
                item {
                    Column {
                        SectionHeader(
                            T.offline.markedTrails,
                            Fmt.fileSize(trailState.bytes).takeIf { trailState.cells > 0 },
                        )
                        SectionInfo(T.offline.trailsInfo, SourceSite("Waymarked Trails", "https://waymarkedtrails.org/"), Guides.MARKED_TRAILS)
                    }
                }
                if (trailState.cells == 0 && trailProgress == null) {
                    item {
                        Text(
                            T.offline.noTrailsYet,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (trailProgress != null) {
                    item {
                        ListItem(
                            headlineContent = { Text(T.offline.downloadingTrails(trailProgress.label)) },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LinearProgressIndicator(progress = { trailProgress.fraction }, modifier = Modifier.fillMaxWidth())
                                    Text(
                                        T.offline.trailsProgress(trailProgress.done, trailProgress.total, trailProgress.failed),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            },
                            trailingContent = {
                                Tip(T.offline.stopTrailDownload) { IconButton(onClick = { offline.trails.cancel() }) {
                                    Icon(Icons.Filled.Close, contentDescription = T.offline.stopTrailDownload)
                                } }
                            },
                        )
                    }
                }
                if (trailState.cells > 0) {
                    item {
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Hiking, contentDescription = null) },
                            headlineContent = { Text(T.offline.trailData) },
                            supportingContent = { Text(T.offline.trailAreas(trailState.cells)) },
                            trailingContent = {
                                Tip(T.offline.deleteAllTrails) { IconButton(onClick = { confirmDeleteTrails = true }) {
                                    Icon(Icons.Filled.Delete, contentDescription = T.offline.deleteAllTrails)
                                } }
                            },
                        )
                    }
                }
            }

            if (downloads.isNotEmpty()) {
                item { SectionHeader(T.offline.downloading, null) }
                items(downloads, key = { "dl-${it.id}" }) { d -> DownloadRow(d, onCancel = { downloader.cancel(d.id) }) }
            }

            item {
                Column {
                    SectionHeader(T.offline.downloadMaps, (listing as? Listing.Ready)?.attribution)
                    SectionInfo(
                        T.offline.downloadInfo,
                        if (basicList) SourceSite("mapsforge.org", "https://mapsforge.org/")
                        else SourceSite(T.offline.howTheyreMade, "https://github.com/adam2314/my-pipeline"),
                        Guides.OFFLINE_MAPS,
                    )
                }
            }
            if (downloader.canWaitForWifi) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { prefs.setWifiOnly(!wifiOnly) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(T.offline.downloadOverWifiOnly, Modifier.weight(1f))
                        Switch(checked = wifiOnly, onCheckedChange = { prefs.setWifiOnly(it) })
                    }
                }
            }
            if (offline.catalog.alternative != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { basicList = !basicList }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(T.offline.basicMaps)
                            Text(
                                T.offline.basicMapsText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = basicList, onCheckedChange = { basicList = it })
                    }
                }
            }
            item { Breadcrumbs(path, onNavigate = { openFolder(it) }) }
            item {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(T.offline.filter) },
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
                        title = T.offline.cantLoadMapList,
                        body = l.message,
                        action = T.offline.retry,
                        onAction = { retryToken++ },
                        modifier = Modifier.padding(16.dp),
                    )
                }
                is Listing.Ready -> {
                    val visible = l.entries.filter { filter.isBlank() || it.title.contains(filter.trim(), ignoreCase = true) }
                    items(visible, key = { it.path }) { entry ->
                        if (entry.isDirectory) {
                            ListItem(
                                modifier = Modifier.clickable { openFolder(entry.path) },
                                leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                headlineContent = { Text(entry.title) },
                                supportingContent = { Text(if (path.isEmpty()) T.offline.continent else T.offline.regions) },
                                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                            )
                        } else {
                            val local = installed.firstOrNull { it.fileName == entry.localFileName }
                            CatalogMapRow(
                                entry = entry,
                                local = local,
                                localIsMapAround = local?.let { extras[it.fileName]?.builtInTrails } == true,
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
            title = { Text(T.offline.confirmDownloadTitle(entry.title)) },
            text = {
                Text(T.offline.confirmDownloadText(entry.sizeLabel, free, overWifi = wifiOnly && downloader.canWaitForWifi))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEntry = null
                    startDownload(entry)
                }) { Text(T.common.download) }
            },
            dismissButton = { TextButton(onClick = { confirmEntry = null }) { Text(T.common.cancel) } },
        )
    }

    routingTarget?.let { map ->
        RoutingDialog(
            map = map,
            onDismiss = { routingTarget = null },
            onStart = { names ->
                routingTarget = null
                val error = offline.routing.download(names, wifiOnly)
                scope.launch { snackbar.showSnackbar(error ?: T.offline.downloadingRoutingFor(map.title)) }
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
                scope.launch { snackbar.showSnackbar(error ?: T.offline.downloadingTrailsFor(map.title)) }
            },
        )
    }

    if (confirmDeleteTrails) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTrails = false },
            title = { Text(T.offline.confirmDeleteTrailsTitle) },
            text = { Text(T.offline.confirmDeleteTrailsText) },
            confirmButton = {
                TextButton(onClick = {
                    offline.trails.deleteAll()
                    confirmDeleteTrails = false
                }) { Text(T.common.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTrails = false }) { Text(T.common.cancel) } },
        )
    }

    terrainTarget?.let { map ->
        TerrainDialog(
            map = map,
            onDismiss = { terrainTarget = null },
            onStart = { bbox, high ->
                terrainTarget = null
                val error = offline.terrain.download(map.title, bbox, high)
                scope.launch { snackbar.showSnackbar(error ?: T.offline.downloadingTerrainFor(map.title)) }
            },
        )
    }

    if (editElevationSource) {
        ElevationSourceDialog(
            elevationSource, terrain3dSource, offline.webTerrain,
            onSave = { tiles, tiles3d ->
                prefs.setElevationSource(tiles)
                if (offline.webTerrain) prefs.setTerrain3dSource(tiles3d)
                editElevationSource = false
            },
            onDismiss = { editElevationSource = false },
        )
    }

    if (confirmDeleteTerrain) {
        AlertDialog(
            onDismissRequest = { confirmDeleteTerrain = false },
            title = { Text(T.offline.confirmDeleteTerrainTitle) },
            text = {
                Text(T.offline.confirmDeleteTerrainText(Fmt.fileSize(terrainState.bytes)))
            },
            confirmButton = {
                TextButton(onClick = {
                    offline.terrain.deleteAll()
                    confirmDeleteTerrain = false
                }) { Text(T.common.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteTerrain = false }) { Text(T.common.cancel) } },
        )
    }

    deleteTarget?.let { map ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(T.offline.confirmDeleteMapTitle(map.title)) },
            text = { Text(T.offline.confirmDeleteMapText(Fmt.fileSize(map.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    repo.delete(map)
                    deleteTarget = null
                }) { Text(T.common.delete) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(T.common.cancel) } },
        )
    }
}

/**
 * Where the elevation data comes from: the 1° tiles' address and the 3D view's relief tiles (the
 * web app's, and the apps' where no elevation is downloaded; [web] adds what web pages may read).
 * Saving the defaults (or "Use the default") gives null: the defaults.
 */
@Composable
internal fun ElevationSourceDialog(
    tiles: String?,
    tiles3d: Terrain3dSource?,
    web: Boolean,
    onSave: (tiles: String?, tiles3d: Terrain3dSource?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(tiles ?: ElevationSources.DEFAULT_TILES) }
    var text3d by remember { mutableStateOf(tiles3d?.template ?: ElevationSources.DEFAULT_3D) }
    var mapbox by remember { mutableStateOf(tiles3d?.mapbox == true) }
    val ok = ElevationSources.tilesTemplate(text)
    val ok3d = ElevationSources.tiles3dTemplate(text3d)
    val small = MaterialTheme.typography.bodySmall
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.offline.terrainSourceTitle) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(T.offline.terrainSourceText, style = small)
                if (web) Text(T.offline.terrainSourceCors, style = small)
                // Addresses read left to right, also in a right-to-left language.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(T.offline.tilesAddress) },
                        isError = ok == null,
                        supportingText = if (ok == null) ({ Text(T.offline.needsTilesTemplate) }) else null,
                        textStyle = small,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // The 3D view's relief: the web app's, and the apps' where no elevation is downloaded.
                run {
                    Text(T.offline.terrain3dText, style = small)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = text3d,
                            onValueChange = { text3d = it },
                            label = { Text(T.offline.terrain3dAddress) },
                            isError = ok3d == null,
                            supportingText = if (ok3d == null) ({ Text(T.offline.needs3dTemplate) }) else null,
                            textStyle = small,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !mapbox, onClick = { mapbox = false }, label = { Text(T.offline.encodingTerrarium) })
                        FilterChip(selected = mapbox, onClick = { mapbox = true }, label = { Text(T.offline.encodingMapbox) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ok != null && ok3d != null,
                onClick = {
                    val t = ok?.takeIf { it != ElevationSources.DEFAULT_TILES }
                    val t3 = ok3d?.let { Terrain3dSource(it, mapbox) }?.takeIf { it.template != ElevationSources.DEFAULT_3D || it.mapbox }
                    onSave(t, t3)
                },
            ) { Text(T.common.save) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null, null) }) { Text(T.offline.useDefault) }
                TextButton(onClick = onDismiss) { Text(T.common.cancel) }
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String, trailing: String?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // The title keeps its width; a long note beside it wraps.
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
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
        TextButton(onClick = { onNavigate("") }, enabled = segments.isNotEmpty()) { Text(T.offline.allRegions) }
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
                    ltr("${Fmt.fileSize(d.downloadedBytes)} / ${Fmt.fileSize(d.totalBytes)}")
                } else {
                    Fmt.fileSize(d.downloadedBytes)
                }
                Text("${d.statusText} · $amount", style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Tip(T.offline.cancelDownload) { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = T.offline.cancelDownload) } }
        },
    )
}

@Composable
private fun CatalogMapRow(
    entry: CatalogEntry,
    local: LocalMap?,
    /** The installed file is one of MapAround's maps (not an older mapsforge.org one). */
    localIsMapAround: Boolean,
    download: DownloadInfo?,
    onDownload: () -> Unit,
) {
    // Something to download: MapAround's map, or mapsforge.org's where MapAround has none yet.
    val downloadable = entry.unavailable == null || entry.url != null
    val newer = entry.modified.let { it != null && local != null && it > local.modified }
    // A newer file, or MapAround's map in place of an older mapsforge.org one.
    val updateAvailable = local != null && downloadable && (newer || (!entry.basic && entry.url != null && !localIsMapAround))
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val why = when (entry.unavailable) {
                CatalogEntry.Unavailable.NEEDS_FUNDING -> if (entry.basic) T.offline.tooBigBasic else T.offline.tooBig
                CatalogEntry.Unavailable.NOT_BUILT -> if (entry.basic) T.offline.notBuiltBasic else T.offline.notAvailableYet
                null -> null
            }
            if (!downloadable) {
                if (entry.unavailable == CatalogEntry.Unavailable.NEEDS_FUNDING) {
                    // Too big for the free build machines: what would build it.
                    val platform = LocalPlatform.current
                    Column {
                        Text(why.orEmpty())
                        Text(
                            T.offline.howToHelp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { platform.openUrl(Guides.page(Guides.SUPPORT)) }.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    Text(why.orEmpty())
                }
            } else {
                val date = entry.modified?.let { Fmt.date(it) }
                Text(
                    listOfNotNull(
                        why,
                        T.offline.basicMapNote.takeIf { entry.basic },
                        entry.sizeLabel?.let { "${it}B" },
                        date?.let { T.offline.updated(it) },
                    ).joinToString(" · "),
                )
            }
        },
        trailingContent = {
            when {
                !downloadable -> if (local != null) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = T.offline.installed,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                download != null -> {
                    val p = download.progress
                    if (p != null) {
                        CircularProgressIndicator(progress = { p }, modifier = Modifier.size(28.dp))
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }
                updateAvailable -> TextButton(onClick = onDownload) { Text(T.offline.update) }
                local != null -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = T.offline.installed,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )
                else -> Tip(T.offline.downloadMap(entry.title)) { IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = T.offline.downloadMap(entry.title))
                } }
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
    var high by remember { mutableStateOf(prefs.terrainHighDetail && terrain.offersHighDetail) }
    val current = info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(T.offline.contoursAndHillShading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    T.offline.terrainDialogText(map.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    current.third == 0 -> Text(
                        T.offline.installedTiles(current.second),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    else -> {
                        Text(
                            T.offline.tilesToDownload(current.third, current.second),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (terrain.offersHighDetail) {
                            DetailOption(
                                selected = high,
                                title = T.offline.highDetail,
                                subtitle = T.offline.upTo(Fmt.fileSize(terrain.estimateBytes(current.third, true))),
                                onClick = { high = true },
                            )
                            DetailOption(
                                selected = !high,
                                title = T.offline.standardDetail,
                                subtitle = T.offline.upToSmaller(Fmt.fileSize(terrain.estimateBytes(current.third, false))),
                                onClick = { high = false },
                            )
                        } else {
                            Text(
                                T.offline.standardOnly(Fmt.fileSize(terrain.estimateBytes(current.third, false))),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (current != null && current.third > 0) {
                TextButton(onClick = { onStart(current.first, high) }) { Text(T.common.download) }
            } else {
                TextButton(onClick = onDismiss) { Text(T.common.close) }
            }
        },
        dismissButton = {
            if (current != null && current.third > 0) TextButton(onClick = onDismiss) { Text(T.common.cancel) }
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
private data class Extras(val terrain: Boolean, val routing: Boolean, val trails: Boolean, val builtInTrails: Boolean = false)

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
        title = { Text(T.offline.offlineRouting) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    T.offline.routingDialogText(map.title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    current.first.isEmpty() -> Text(
                        T.offline.installedCheck, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall,
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
                                T.offline.total(Fmt.fileSize(current.first.sumOf { sizes[it] ?: 0L })),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        } else {
                            Text(T.offline.cantCheckSizes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (current != null && current.first.isNotEmpty()) {
                TextButton(onClick = { onStart(current.first) }) { Text(T.common.download) }
            } else {
                TextButton(onClick = onDismiss) { Text(T.common.close) }
            }
        },
        dismissButton = {
            if (current != null && current.first.isNotEmpty()) TextButton(onClick = onDismiss) { Text(T.common.cancel) }
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
        title = { Text(T.offline.markedTrails) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    T.offline.trailsDialogText,
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    current == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    current.third == 0 -> Text(
                        T.offline.installedAreas(current.second), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    else -> {
                        Text(T.offline.areasToDownload(current.third, current.second), style = MaterialTheme.typography.titleSmall)
                        Text(
                            T.offline.trailsDownloadNote,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (current != null && current.third > 0) {
                TextButton(onClick = { onStart(current.first) }) { Text(T.common.download) }
            } else {
                TextButton(onClick = onDismiss) { Text(T.common.close) }
            }
        },
        dismissButton = {
            if (current != null && current.third > 0) TextButton(onClick = onDismiss) { Text(T.common.cancel) }
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
