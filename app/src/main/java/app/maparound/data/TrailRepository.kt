package app.maparound.data

import android.content.Context
import android.net.ConnectivityManager
import app.maparound.USER_AGENT
import app.maparound.i18n.T
import app.maparound.trails.TrailCellIO
import app.maparound.terrain.DemIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.CoroutineContext
import kotlin.math.floor
import okio.source
import okio.buffer
import app.maparound.terrain.GeoBounds

/**
 * Marked hiking routes (OSM route relations) for offline use, fetched from the Overpass API
 * one 1°×1° cell at a time and stored as compact `files/trails/N47E009.trails` files.
 */
class TrailRepository(private val context: Context, private val prefs: Prefs) : TrailData {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val dir: File = File(base, "trails").apply { mkdirs() }


    private val _state = MutableStateFlow(TrailState())
    override val state: StateFlow<TrailState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    init {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
        refresh()
    }

    fun refresh() {
        val files = dir.listFiles { f -> f.name.endsWith(".trails") }.orEmpty()
        _state.update { it.copy(cells = files.size, bytes = files.sumOf { f -> f.length() }, version = it.version + 1) }
    }

    override fun cellsFor(bounds: GeoBounds): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (lat in floor(bounds.minLat).toInt()..floor(bounds.maxLat - 1e-9).toInt()) {
            for (lon in floor(bounds.minLon).toInt()..floor(bounds.maxLon - 1e-9).toInt()) out += lat to lon
        }
        return out
    }

    override fun missingCells(bounds: GeoBounds): List<Pair<Int, Int>> =
        // Cells from before cliffs were fetched with the trails count as missing.
        cellsFor(bounds).filterNot { (lat, lon) -> file(lat, lon).let { it.exists() && TrailCellIO.hasFeatures(it.path) } }

    private fun file(lat: Int, lon: Int) = File(dir, DemIndex.nameFor(lat, lon) + ".trails")

    /** Returns an error message, or null when the download started. */
    override fun download(label: String, bounds: GeoBounds): String? {
        if (job?.isActive == true) return T.offline.trailsAlreadyRunning
        val cells = missingCells(bounds)
        if (cells.isEmpty()) return T.offline.trailsAlreadyInstalled(label)
        if (cells.size > MAX_CELLS) return T.offline.trailsTooLarge(cells.size)
        if (prefs.wifiOnly.value && isMetered()) return T.offline.wifiOnlyOnMobileData

        job = scope.launch {
            var failed = 0
            _state.update { it.copy(progress = DownloadProgress(label, 0, cells.size, 0)) }
            try {
                cells.forEachIndexed { i, (lat, lon) ->
                    if (!fetchCell(lat, lon)) failed++
                    _state.update { it.copy(progress = DownloadProgress(label, i + 1, cells.size, failed)) }
                }
                _events.tryEmit(
                    if (failed == 0) T.offline.trailsReady(label)
                    else T.offline.trailsFailed(label, failed, cells.size),
                )
            } catch (e: CancellationException) {
                _events.tryEmit(T.offline.trailsStopped)
                throw e
            } finally {
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
        dir.listFiles()?.forEach { it.delete() }
        refresh()
    }

    /** One Overpass query per cell; retried on the server's "too busy" answers. */
    private suspend fun fetchCell(lat: Int, lon: Int): Boolean {
        val bbox = "$lat,$lon,${lat + 1},${lon + 1}"
        val query = "[out:xml][timeout:300];" +
            "relation[\"route\"~\"^(hiking|foot)$\"]($bbox)->.r;" +
            "way(r.r)($bbox);out geom qt;.r out body qt;" +
            // Cliffs, arêtes, ridges and earth banks: the mapsforge maps leave them out.
            "way[\"natural\"~\"${TrailCellIO.FEATURE_REGEX}\"]($bbox);out geom qt;"
        for (attempt in 0 until 3) {
            for (endpoint in ENDPOINTS) {
                kotlin.coroutines.coroutineContext.ensureActive()
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 330_000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("User-Agent", USER_AGENT)
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                    when (conn.responseCode) {
                        200 -> {
                            val ctx = kotlin.coroutines.coroutineContext
                            val cell = Cancellable(conn.inputStream.buffered(1 shl 16), ctx).source().buffer().use { TrailCellIO.parseOverpass(it) }
                            val tmp = File(dir, DemIndex.nameFor(lat, lon) + ".tmp")
                            TrailCellIO.write(cell, tmp.path)
                            return tmp.renameTo(file(lat, lon))
                        }
                        429, 503, 504 -> Unit // busy: try the next server / wait
                        else -> return false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // network error or Overpass timeout text instead of XML: try again
                } finally {
                    conn.disconnect()
                }
            }
            delay(20_000L * (attempt + 1))
        }
        return false
    }

    /** Lets a long streaming parse stop promptly when the download is cancelled. */
    private class Cancellable(input: InputStream, private val ctx: CoroutineContext) : FilterInputStream(input) {
        private fun check() {
            if (!ctx.isActive) throw CancellationException("cancelled")
        }
        override fun read(): Int { check(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return super.read(b, off, len) }
    }

    private fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    companion object {
        private val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
        private const val MAX_CELLS = 120
    }
}
