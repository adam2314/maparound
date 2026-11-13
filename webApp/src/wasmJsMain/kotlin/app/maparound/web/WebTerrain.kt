package app.maparound.web

import app.maparound.data.DownloadProgress
import app.maparound.data.TerrainData
import app.maparound.data.TerrainState
import app.maparound.data.TerrainStore
import app.maparound.shared.fileSystem
import app.maparound.shared.toByteArray
import app.maparound.terrain.DemIndex
import app.maparound.terrain.GeoBounds
import app.maparound.terrain.HgtProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okio.Buffer
import okio.Path.Companion.toPath

/**
 * Fetches an elevation tile ([url]: 1″, .hgt.gz), unpacks it with the browser's own gzip and keeps
 * every third sample (3″, 1201×1201): [done] gets those big-endian samples, or null and the HTTP
 * status (404: no tile there, the sea) or a negative number for a failure.
 */
private fun fetchHgt3(url: String, done: (JsAny?, Int) -> Unit): Unit = js(
    """{
    fetch(url).then((r) => {
        if (!r.ok) { done(null, r.status); return; }
        return new Response(r.body.pipeThrough(new DecompressionStream('gzip'))).arrayBuffer().then((buf) => {
            const src = new Uint8Array(buf);
            const n = Math.round(Math.sqrt(src.length / 2));
            if (n === 1201) { done(src, 200); return; }
            if (n !== 3601) { done(null, -2); return; }
            const out = new Uint8Array(1201 * 1201 * 2);
            for (let row = 0; row < 1201; row++) {
                for (let col = 0; col < 1201; col++) {
                    const s = (row * 3 * 3601 + col * 3) * 2, d = (row * 1201 + col) * 2;
                    out[d] = src[s];
                    out[d + 1] = src[s + 1];
                }
            }
            done(out, 200);
        });
    }).catch(() => done(null, -1));
}""",
)

/** The 1° cells overlapping an area. */
private fun cellsOf(bounds: GeoBounds): List<Pair<Int, Int>> {
    val out = ArrayList<Pair<Int, Int>>()
    for (lat in kotlin.math.floor(bounds.minLat).toInt()..kotlin.math.floor(bounds.maxLat - 1e-9).toInt()) {
        for (lon in kotlin.math.floor(bounds.minLon).toInt()..kotlin.math.floor(bounds.maxLon - 1e-9).toInt()) {
            if (lat in -85..84) out += lat to lon
        }
    }
    return out
}

/**
 * Elevation data in the browser, for line of sight and viewshed (the 3D view gets its own online):
 * the apps' 1° tiles (skadi, on Amazon's servers), fetched and unpacked by the browser and kept at
 * standard detail (~90 m, 2.9 MB a tile) with the app's files. Fetched as they're needed.
 */
class WebTerrain(demDir: String) : TerrainData {
    private val dir = demDir.toPath()
    private val _state = MutableStateFlow(TerrainState())
    override val state: StateFlow<TerrainState> = _state.asStateFlow()
    private var run = 0

    /** Called when the files changed (a download, a deletion), before [state] says so. */
    var onFilesChanged: (() -> Unit)? = null

    init {
        fileSystem.createDirectories(dir)
        refresh()
    }

    private fun refresh() {
        onFilesChanged?.invoke()
        val files = fileSystem.list(dir).filter { DemIndex.parseName(it.name) != null }
        _state.update { s -> s.copy(tiles = files.size, bytes = files.sumOf { fileSystem.metadataOrNull(it)?.size ?: 0L }, version = s.version + 1) }
    }

    private fun file(lat: Int, lon: Int) = dir / (DemIndex.nameFor(lat, lon) + ".hgt")

    override fun cellsFor(bounds: GeoBounds) = cellsOf(bounds)
    override fun missingCells(bounds: GeoBounds) = cellsOf(bounds).filterNot { (lat, lon) -> fileSystem.exists(file(lat, lon)) }
    /** What the browser downloads: the 1″ tile (it keeps a ninth of it). */
    override fun estimateBytes(cells: Int, highDetail: Boolean): Long = cells * DOWNLOAD_BYTES

    override val offersHighDetail: Boolean get() = false

    /** Standard detail only, here (kept in the browser's memory and storage). */
    override fun download(label: String, bounds: GeoBounds, highDetail: Boolean): String? {
        if (_state.value.progress != null) return "A terrain download is already running"
        val cells = missingCells(bounds)
        if (cells.isEmpty()) return "Terrain for $label is already installed"
        val id = ++run
        var failed = 0
        _state.update { it.copy(progress = DownloadProgress(label, 0, cells.size, 0)) }
        // One tile after another (each is a 25 MB file to unpack).
        fun next(i: Int) {
            if (id != run) return
            if (i == cells.size) {
                _state.update { it.copy(progress = null) }
                refresh()
                return
            }
            val (lat, lon) = cells[i]
            val name = DemIndex.nameFor(lat, lon)
            fetchHgt3("$SOURCE/${name.take(3)}/$name.hgt.gz") { data, status ->
                if (id != run) return@fetchHgt3
                val ok = when {
                    data != null -> runCatching { store(lat, lon, data.toByteArray()) }.isSuccess
                    else -> status == 403 || status == 404 // no tile for this cell: the sea
                }
                if (!ok) failed++
                _state.update { it.copy(progress = DownloadProgress(label, i + 1, cells.size, failed)) }
                next(i + 1)
            }
        }
        next(0)
        return null
    }

    private fun store(lat: Int, lon: Int, samples: ByteArray) {
        val tmp = dir / (DemIndex.nameFor(lat, lon) + ".part")
        HgtProcessor.convert(Buffer().write(samples), HgtProcessor.SIZE_3_ARCSEC, tmp.toString(), highDetail = false, keepNegative = HgtProcessor.keepsNegativeHeights(lat, lon))
        fileSystem.atomicMove(tmp, file(lat, lon))
    }

    override fun cancel() {
        if (_state.value.progress == null) return
        run++
        _state.update { it.copy(progress = null) }
        refresh()
    }

    override fun deleteAll() {
        cancel()
        fileSystem.list(dir).forEach { fileSystem.delete(it, mustExist = false) }
        refresh()
    }

    private companion object {
        const val SOURCE = "https://elevation-tiles-prod.s3.amazonaws.com/skadi"

        /** About what one tile's download is (1″, gzipped). */
        const val DOWNLOAD_BYTES = 12_000_000L
    }
}
