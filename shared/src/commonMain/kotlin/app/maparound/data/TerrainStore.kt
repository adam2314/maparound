package app.maparound.data

import app.maparound.shared.fileSystem
import app.maparound.shared.gunzipFile
import app.maparound.shared.ioDispatcher
import app.maparound.shared.net.HttpException
import app.maparound.shared.net.httpDownload
import app.maparound.shared.util.Bits
import app.maparound.terrain.DemIndex
import app.maparound.terrain.GeoBounds
import app.maparound.terrain.HgtProcessor
import app.maparound.ui.Fmt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.math.floor

/** Cuts elevation at the coastline of the installed maps (one 1° cell at a time). */
interface CoastMasks {
    /** A bit per grid sample set where the maps say sea, or null when there's none. */
    fun build(lat: Int, lon: Int, size: Int): Bits?
    fun close()
}

/**
 * Elevation data (1°×1° .hgt tiles) for contour lines, hill shading, the map tools and the 3D
 * view, on Android and iOS.
 *
 * Source: the open "Terrain Tiles" dataset on AWS (skadi format, ~30 m), see
 * https://registry.opendata.aws/terrain-tiles/. Each tile is unpacked, cut at the coastline of
 * the installed maps where the platform can ([coast]), optionally reduced to ~90 m, and stored in
 * [demDir].
 */
class TerrainStore(
    val demDir: String,
    private val prefs: Prefs,
    private val coast: () -> CoastMasks?,
    private val isMetered: () -> Boolean,
    private val freeBytes: () -> Long,
) : TerrainData {
    private val dir: Path = demDir.toPath()
    private val tmpDir: Path = "$demDir-tmp".toPath()

    private val _state = MutableStateFlow(TerrainState())
    override val state: StateFlow<TerrainState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var job: Job? = null

    init {
        fileSystem.createDirectories(dir)
        fileSystem.createDirectories(tmpDir)
        // Leftovers from an interrupted run.
        fileSystem.list(tmpDir).forEach { fileSystem.delete(it, mustExist = false) }
        refresh()
    }

    fun refresh() {
        val files = fileSystem.list(dir).filter { DemIndex.parseName(it.name) != null }
        val bytes = files.sumOf { fileSystem.metadataOrNull(it)?.size ?: 0L }
        _state.update { it.copy(tiles = files.size, bytes = bytes, version = it.version + 1) }
    }

    /** All 1° cells overlapping a map's bounding box. */
    override fun cellsFor(bounds: GeoBounds): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (lat in floor(bounds.minLat).toInt()..floor(bounds.maxLat - 1e-9).toInt()) {
            for (lon in floor(bounds.minLon).toInt()..floor(bounds.maxLon - 1e-9).toInt()) {
                if (lat in -85..84) cells += lat to lon
            }
        }
        return cells
    }

    override fun missingCells(bounds: GeoBounds): List<Pair<Int, Int>> =
        cellsFor(bounds).filterNot { (lat, lon) -> fileSystem.exists(dir / (DemIndex.nameFor(lat, lon) + ".hgt")) }

    override fun estimateBytes(cells: Int, highDetail: Boolean): Long =
        cells * if (highDetail) BYTES_1_ARCSEC else BYTES_3_ARCSEC

    /** Starts downloading the missing tiles for [bounds]. Returns an error message, or null. */
    override fun download(label: String, bounds: GeoBounds, highDetail: Boolean): String? {
        if (job?.isActive == true) return "A terrain download is already running"
        val cells = missingCells(bounds)
        if (cells.isEmpty()) return "Terrain for $label is already installed"
        val needed = estimateBytes(cells.size, highDetail)
        if (needed > MAX_DOWNLOAD_BYTES) {
            return "That's too much terrain for one download (${Fmt.fileSize(needed)}). " +
                if (highDetail) "Choose standard detail or a smaller region." else "Choose a smaller region."
        }
        val free = freeBytes()
        if (free < needed + (64L shl 20)) return "Not enough storage: up to ${Fmt.fileSize(needed)} needed, ${Fmt.fileSize(free)} free"
        if (prefs.wifiOnly.value && isMetered()) return "Wi‑Fi only is on and you're on mobile data"

        prefs.terrainHighDetail = highDetail
        job = scope.launch {
            val done = atomic(0)
            val failed = atomic(0)
            _state.update { it.copy(progress = DownloadProgress(label, 0, cells.size, 0)) }
            val masks = runCatching { coast() }.getOrNull()
            try {
                val limit = Semaphore(PARALLEL)
                coroutineScope {
                    for ((lat, lon) in cells) {
                        launch {
                            limit.withPermit {
                                if (!fetchCell(lat, lon, highDetail, masks)) failed.incrementAndGet()
                                val d = done.incrementAndGet()
                                _state.update { it.copy(progress = DownloadProgress(label, d, cells.size, failed.value)) }
                            }
                        }
                    }
                }
                val f = failed.value
                _events.tryEmit(
                    if (f == 0) "Contours & hill shading ready for $label"
                    else "Terrain for $label: $f of ${cells.size} tiles failed. Tap again to retry.",
                )
            } catch (e: CancellationException) {
                _events.tryEmit("Terrain download stopped")
                throw e
            } finally {
                masks?.close()
                _state.update { it.copy(progress = null) }
                refresh()
            }
        }
        return null
    }

    override fun cancel() {
        job?.cancel()
    }

    override fun deleteAll() {
        cancel()
        fileSystem.list(dir).forEach { fileSystem.delete(it, mustExist = false) }
        refresh()
    }

    /** Downloads and prepares one tile. Returns false on failure (true also when the sea has no tile). */
    private suspend fun fetchCell(lat: Int, lon: Int, highDetail: Boolean, coast: CoastMasks?): Boolean {
        val name = DemIndex.nameFor(lat, lon)
        val gz = tmpDir / "$name.gz"
        val raw = tmpDir / "$name.raw"
        val prepared = tmpDir / "$name.hgt"
        val context = currentCoroutineContext()
        try {
            try {
                httpDownload("$SOURCE/${name.take(3)}/$name.hgt.gz", gz.toString(), 60_000) { context.isActive }
            } catch (e: HttpException) {
                return e.status == 403 || e.status == 404 // no tile for this cell
            }
            context.ensureActive()
            gunzipFile(gz, raw)
            fileSystem.delete(gz)
            val size = HgtProcessor.sizeForLength(fileSystem.metadata(raw).size ?: 0L) ?: return false
            val mask = coast?.let { runCatching { it.build(lat, lon, HgtProcessor.outputSize(size, highDetail)) }.getOrNull() }
            fileSystem.source(raw).use {
                HgtProcessor.convert(it, size, prepared.toString(), highDetail, HgtProcessor.keepsNegativeHeights(lat, lon), mask)
            }
            fileSystem.atomicMove(prepared, dir / "$name.hgt")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            for (p in listOf(gz, raw, prepared)) fileSystem.delete(p, mustExist = false)
        }
    }

    companion object {
        private const val SOURCE = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"
        private const val PARALLEL = 3
        const val BYTES_1_ARCSEC = 3601L * 3601 * 2
        const val BYTES_3_ARCSEC = 1201L * 1201 * 2
        private const val MAX_DOWNLOAD_BYTES = 4L shl 30
    }
}
