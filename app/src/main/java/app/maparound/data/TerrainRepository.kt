package app.maparound.data

import android.content.Context
import android.net.ConnectivityManager
import android.text.format.Formatter
import app.maparound.USER_AGENT
import app.maparound.terrain.CoastMask
import app.maparound.terrain.DemIndex
import app.maparound.terrain.HgtProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.map.datastore.MultiMapDataStore
import org.mapsforge.map.reader.MapFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.math.floor
import okio.source

/**
 * Elevation data (1°×1° .hgt tiles) for contour lines and hill shading.
 *
 * Source: the open "Terrain Tiles" dataset on AWS (skadi format, ~30 m), see
 * https://registry.opendata.aws/terrain-tiles/. Each tile is unpacked, cut at the coastline of
 * the installed maps, optionally reduced to ~90 m, and stored in `files/dem/`.
 */
class TerrainRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val maps: MapFilesRepository,
) {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val demDir: File = File(base, "dem").apply { mkdirs() }
    private val tmpDir: File = File(base, "dem-tmp").apply { mkdirs() }

    data class Progress(val label: String, val done: Int, val total: Int, val failed: Int) {
        val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
    }

    data class State(
        val tiles: Int = 0,
        val bytes: Long = 0,
        /** Bumped whenever the set of tiles changes, so map layers know to rebuild. */
        val version: Int = 0,
        val progress: Progress? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    init {
        tmpDir.listFiles()?.forEach { it.delete() } // leftovers from an interrupted run
        refresh()
    }

    fun refresh() {
        val files = demDir.listFiles { f -> DemIndex.parseName(f.name) != null }.orEmpty()
        _state.update { it.copy(tiles = files.size, bytes = files.sumOf { f -> f.length() }, version = it.version + 1) }
    }

    /** All 1° cells overlapping a map's bounding box. */
    fun cellsFor(bbox: BoundingBox): List<Pair<Int, Int>> {
        val cells = ArrayList<Pair<Int, Int>>()
        for (lat in floor(bbox.minLatitude).toInt()..floor(bbox.maxLatitude - 1e-9).toInt()) {
            for (lon in floor(bbox.minLongitude).toInt()..floor(bbox.maxLongitude - 1e-9).toInt()) {
                if (lat in -85..84) cells += lat to lon
            }
        }
        return cells
    }

    fun missingCells(bbox: BoundingBox): List<Pair<Int, Int>> =
        cellsFor(bbox).filterNot { (lat, lon) -> File(demDir, DemIndex.nameFor(lat, lon) + ".hgt").exists() }

    fun estimateBytes(cells: Int, highDetail: Boolean): Long =
        cells * if (highDetail) BYTES_1_ARCSEC else BYTES_3_ARCSEC

    /** Starts downloading the missing tiles for [bbox]. Returns an error message, or null. */
    fun download(label: String, bbox: BoundingBox, highDetail: Boolean): String? {
        if (job?.isActive == true) return "A terrain download is already running"
        val cells = missingCells(bbox)
        if (cells.isEmpty()) return "Terrain for $label is already installed"
        val needed = estimateBytes(cells.size, highDetail)
        if (needed > MAX_DOWNLOAD_BYTES) {
            return "That's too much terrain for one download (${fmt(needed)}). " +
                if (highDetail) "Choose standard detail or a smaller region." else "Choose a smaller region."
        }
        val free = demDir.usableSpace
        if (free < needed + (64L shl 20)) return "Not enough storage: up to ${fmt(needed)} needed, ${fmt(free)} free"
        if (prefs.wifiOnly.value && isMetered()) return "Wi‑Fi only is on and you're on mobile data"

        prefs.terrainHighDetail = highDetail
        job = scope.launch {
            val done = AtomicInteger()
            val failed = AtomicInteger()
            _state.update { it.copy(progress = Progress(label, 0, cells.size, 0)) }
            val coast = openCoastSource()
            try {
                val limit = Semaphore(PARALLEL)
                coroutineScope {
                    for ((lat, lon) in cells) {
                        launch {
                            limit.withPermit {
                                if (!fetchCell(lat, lon, highDetail, coast)) failed.incrementAndGet()
                                val d = done.incrementAndGet()
                                _state.update { it.copy(progress = Progress(label, d, cells.size, failed.get())) }
                            }
                        }
                    }
                }
                val f = failed.get()
                _events.tryEmit(
                    if (f == 0) "Contours & hill shading ready for $label"
                    else "Terrain for $label: $f of ${cells.size} tiles failed. Tap again to retry.",
                )
            } catch (e: CancellationException) {
                _events.tryEmit("Terrain download stopped")
                throw e
            } finally {
                coast?.close()
                _state.update { it.copy(progress = null) }
                refresh()
            }
        }
        return null
    }

    fun cancel() {
        job?.cancel()
    }

    fun deleteAll() {
        cancel()
        demDir.listFiles()?.forEach { it.delete() }
        refresh()
    }

    /** The installed vector maps, used to cut elevation at the coastline. */
    private fun openCoastSource(): MultiMapDataStore? {
        val files = maps.files.value.filterNot { it.isWorld }
        if (files.isEmpty()) return null
        val store = MultiMapDataStore(MultiMapDataStore.DataPolicy.RETURN_ALL)
        files.forEach { m -> runCatching { store.addMapDataStore(MapFile(m.file), false, false) } }
        return store
    }

    /** Downloads and prepares one tile. Returns false on failure (true also when the sea has no tile). */
    private suspend fun fetchCell(lat: Int, lon: Int, highDetail: Boolean, coast: MultiMapDataStore?): Boolean {
        val name = DemIndex.nameFor(lat, lon)
        val raw = File(tmpDir, "$name.raw")
        val prepared = File(tmpDir, "$name.hgt")
        try {
            val conn = URL("$SOURCE/${name.take(3)}/$name.hgt.gz").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                when (conn.responseCode) {
                    in 200..299 -> Unit
                    403, 404 -> return true // no tile for this cell
                    else -> return false
                }
                GZIPInputStream(conn.inputStream.buffered(1 shl 16)).use { input ->
                    raw.outputStream().buffered(1 shl 16).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val size = HgtProcessor.sizeForLength(raw.length()) ?: return false
            val mask = coast?.let {
                runCatching { CoastMask.build(it, lat, lon, HgtProcessor.outputSize(size, highDetail)) }.getOrNull()
            }
            raw.source().use {
                HgtProcessor.convert(it, size, prepared.path, highDetail, HgtProcessor.keepsNegativeHeights(lat, lon), mask)
            }
            return prepared.renameTo(File(demDir, "$name.hgt"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        } finally {
            raw.delete()
            prepared.delete()
        }
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    private fun fmt(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    companion object {
        private const val SOURCE = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"
        private const val PARALLEL = 3
        const val BYTES_1_ARCSEC = 3601L * 3601 * 2
        const val BYTES_3_ARCSEC = 1201L * 1201 * 2
        private const val MAX_DOWNLOAD_BYTES = 4L shl 30
    }
}
