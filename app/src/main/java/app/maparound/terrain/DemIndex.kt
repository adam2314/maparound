package app.maparound.terrain

import org.mapsforge.core.model.BoundingBox
import java.io.File
import java.io.RandomAccessFile
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.math.sqrt

// NOTE: no Android imports in this package's core files – they also run in the desktop
// render-preview tool and in JVM unit tests.

/** One SRTM-style .hgt file: a square grid of big-endian 16-bit heights covering 1°×1°. */
class HgtTile(file: File, val south: Int, val west: Int) {
    /** Samples per side: 3601 (1 arc-second, ~30 m) or 1201 (3 arc-second, ~90 m). */
    val size: Int = sqrt((file.length() / 2).toDouble()).toInt()
    private val heights: ShortBuffer

    init {
        require(size.toLong() * size * 2 == file.length() && size >= 2) { "Not an HGT file: ${file.name}" }
        // Memory-mapped: no heap cost, and absolute reads are safe from several render threads.
        heights = RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()).asShortBuffer()
        }
    }

    private fun raw(row: Int, col: Int): Int = heights.get(row * size + col).toInt()

    /** Bilinearly interpolated height in metres, or NaN over data voids. */
    fun elevation(lat: Double, lon: Double): Float {
        val n = size - 1
        val fy = (south + 1 - lat) * n // rows run north -> south
        val fx = (lon - west) * n
        val r = floor(fy).toInt().coerceIn(0, n - 1)
        val c = floor(fx).toInt().coerceIn(0, n - 1)
        val dy = (fy - r).toFloat().coerceIn(0f, 1f)
        val dx = (fx - c).toFloat().coerceIn(0f, 1f)
        val v00 = raw(r, c)
        val v01 = raw(r, c + 1)
        val v10 = raw(r + 1, c)
        val v11 = raw(r + 1, c + 1)
        if (v00 == VOID || v01 == VOID || v10 == VOID || v11 == VOID) {
            // Fall back to the nearest valid corner so small voids don't punch holes.
            val nearest = listOf(v00, v01, v10, v11).firstOrNull { it != VOID } ?: return Float.NaN
            return nearest.toFloat()
        }
        val top = v00 + (v01 - v00) * dx
        val bottom = v10 + (v11 - v10) * dx
        return top + (bottom - top) * dy
    }

    companion object {
        const val VOID = -32768
    }
}

/**
 * All .hgt tiles in a folder (named like N46E007.hgt). Files are opened lazily on first use.
 */
class DemIndex(val dir: File) {
    private val files: Map<Long, File>
    private val open = ConcurrentHashMap<Long, HgtTile>()
    private val broken = ConcurrentHashMap.newKeySet<Long>()

    /** Finest sample spacing among the files, in degrees. */
    val finestStep: Double

    init {
        val found = HashMap<Long, File>()
        var finest = 1.0 / 1200
        dir.listFiles().orEmpty().forEach { f ->
            val (lat, lon) = parseName(f.name) ?: return@forEach
            found[key(lat, lon)] = f
            if (f.length() > 3_000_000L) finest = 1.0 / 3600 // a 1" file is ~25 MB, a 3" one ~2.9 MB
        }
        files = found
        finestStep = finest
    }

    val isEmpty: Boolean get() = files.isEmpty()
    val tileCount: Int get() = files.size

    /** Changes whenever the set of files changes; used to know when to rebuild map layers. */
    val fingerprint: String = files.values.sortedBy { it.name }.joinToString("|") { "${it.name}:${it.length()}" }

    fun boundingBox(): BoundingBox? {
        if (files.isEmpty()) return null
        val lats = files.keys.map { (it shr 32).toInt() }
        val lons = files.keys.map { it.toInt() }
        return BoundingBox(
            lats.min().toDouble().coerceAtLeast(-85.0),
            lons.min().toDouble(),
            (lats.max() + 1).toDouble().coerceAtMost(85.0),
            (lons.max() + 1).toDouble(),
        )
    }

    fun covers(lat: Int, lon: Int): Boolean = files.containsKey(key(lat, lon))

    /** True if any 1° tile overlapping the box has data. */
    fun hasDataIn(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Boolean {
        for (lat in floor(minLat).toInt()..floor(maxLat).toInt()) {
            for (lon in floor(minLon).toInt()..floor(maxLon).toInt()) {
                if (covers(lat, lon)) return true
            }
        }
        return false
    }

    fun elevation(lat: Double, lon: Double): Float {
        val k = key(floor(lat).toInt(), floor(lon).toInt())
        val tile = open[k] ?: load(k) ?: return Float.NaN
        return tile.elevation(lat, lon)
    }

    private fun load(k: Long): HgtTile? {
        if (k in broken) return null
        val file = files[k] ?: return null
        return try {
            open.computeIfAbsent(k) { HgtTile(file, (k shr 32).toInt(), k.toInt()) }
        } catch (e: Exception) {
            broken += k
            null
        }
    }

    companion object {
        private val NAME = Regex("""^([NS])(\d{1,2})([EW])(\d{1,3})\.hgt$""", RegexOption.IGNORE_CASE)

        fun key(lat: Int, lon: Int): Long = (lat.toLong() shl 32) or (lon.toLong() and 0xFFFFFFFFL)

        /** "N46E007.hgt" -> (46, 7); "S05W078.hgt" -> (-5, -78). */
        fun parseName(name: String): Pair<Int, Int>? {
            val m = NAME.matchEntire(name) ?: return null
            val lat = m.groupValues[2].toInt() * if (m.groupValues[1].equals("S", true)) -1 else 1
            val lon = m.groupValues[4].toInt() * if (m.groupValues[3].equals("W", true)) -1 else 1
            return lat to lon
        }

        fun nameFor(lat: Int, lon: Int): String =
            String.format(
                java.util.Locale.ROOT, "%s%02d%s%03d",
                if (lat >= 0) "N" else "S", kotlin.math.abs(lat),
                if (lon >= 0) "E" else "W", kotlin.math.abs(lon),
            )
    }
}
