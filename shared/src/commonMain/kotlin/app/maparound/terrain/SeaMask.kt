package app.maparound.terrain

import app.maparound.data.CoastMasks
import app.maparound.shared.mapsforge.MapsforgeFile
import app.maparound.shared.openFile
import app.maparound.shared.util.Bits
import app.maparound.three.TileId
import okio.FileHandle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Rasterises the sea of the installed vector maps onto an elevation tile's grid, with the
 * shared mapsforge reader (the same as Android's CoastMask, which uses mapsforge itself).
 *
 * Elevation data tends to spill land heights a few hundred metres past the real coastline in
 * blocky steps, which hill shading turns into stair-step lines in the sea. Flattening every sea
 * pixel to 0 m makes shading (and contours) end exactly at the OSM coastline.
 *
 * Mapsforge maps model the coast as `natural=sea` polygons (often whole sub-tiles) with
 * `natural=nosea` land polygons painted over them, so sea = union(sea) minus union(nosea).
 */
object SeaMask {
    private const val READ_ZOOM = 12

    /**
     * @return a bit per grid sample (row-major, row 0 = north edge) set where the maps say sea,
     *   or null when the maps have no sea in this 1° cell (or don't cover it at all).
     */
    fun build(maps: List<MapsforgeFile>, south: Int, west: Int, size: Int): Bits? {
        val sea = ArrayList<DoubleArray>()
        val land = ArrayList<DoubleArray>()
        val waterTiles = ArrayList<Pair<Int, Int>>()
        val x0 = TileId.xOf(west.toDouble(), READ_ZOOM)
        val x1 = TileId.xOf(west + 1 - 1e-9, READ_ZOOM)
        val y0 = TileId.yOf((south + 1).toDouble().coerceAtMost(85.0), READ_ZOOM)
        val y1 = TileId.yOf((south + 1e-9).coerceAtLeast(-85.0), READ_ZOOM)
        for (x in x0..x1) for (y in y0..y1) {
            val covering = maps.filter { covers(it, x, y) }
            if (covering.isEmpty()) continue
            var tileSea = false
            var water = true
            for (m in covering) {
                val data = runCatching { m.read(READ_ZOOM, x, y) }.getOrNull() ?: continue
                for (way in data.ways) {
                    when (way.tags.firstOrNull { it.key == "natural" }?.value) {
                        "sea", "issea" -> { sea.addAll(way.rings); tileSea = true }
                        "nosea", "land" -> land.addAll(way.rings)
                    }
                }
                if (!data.isWater) water = false
            }
            if (water && !tileSea) waterTiles += x to y
        }
        if (sea.isEmpty() && waterTiles.isEmpty()) return null

        val mask = Bits(size * size)
        val n = size - 1
        val rowOf = { lat: Double -> (south + 1 - lat) * n }
        val colOf = { lon: Double -> (lon - west) * n }
        // Whole-water tiles first, then sea polygons, then carve the land back out.
        for ((x, y) in waterTiles) {
            fillRect(
                mask, size,
                rowOf(MapsforgeFile.tileLat(y, READ_ZOOM)), rowOf(MapsforgeFile.tileLat(y + 1, READ_ZOOM)),
                colOf(MapsforgeFile.tileLon(x, READ_ZOOM)), colOf(MapsforgeFile.tileLon(x + 1, READ_ZOOM)), true,
            )
        }
        for (ring in sea) fillRing(mask, size, ring, rowOf, colOf, true)
        for (ring in land) fillRing(mask, size, ring, rowOf, colOf, false)
        return if (mask.isEmpty) null else mask
    }

    private fun covers(m: MapsforgeFile, x: Int, y: Int): Boolean {
        val h = m.header
        val north = MapsforgeFile.tileLat(y, READ_ZOOM)
        val south = MapsforgeFile.tileLat(y + 1, READ_ZOOM)
        val west = MapsforgeFile.tileLon(x, READ_ZOOM)
        val east = MapsforgeFile.tileLon(x + 1, READ_ZOOM)
        return h.minLat <= north && h.maxLat >= south && h.minLon <= east && h.maxLon >= west
    }

    private fun fillRect(mask: Bits, size: Int, r0: Double, r1: Double, c0: Double, c1: Double, value: Boolean) {
        val ra = ceil(r0).toInt().coerceAtLeast(0)
        val rb = floor(r1).toInt().coerceAtMost(size - 1)
        val ca = ceil(c0).toInt().coerceAtLeast(0)
        val cb = floor(c1).toInt().coerceAtMost(size - 1)
        if (ca > cb) return
        for (r in ra..rb) mask.set(r * size + ca, r * size + cb + 1, value)
    }

    /** Scanline fill of one ring ([lat0, lon0, …]); rings are filled one by one so overlaps form a union. */
    internal fun fillRing(
        mask: Bits, size: Int, ring: DoubleArray,
        rowOf: (Double) -> Double, colOf: (Double) -> Double, value: Boolean,
    ) {
        val count = ring.size / 2
        if (count < 3) return
        val rows = DoubleArray(count) { rowOf(ring[2 * it]) }
        val cols = DoubleArray(count) { colOf(ring[2 * it + 1]) }
        val rMin = ceil(rows.min()).toInt().coerceAtLeast(0)
        val rMax = floor(rows.max()).toInt().coerceAtMost(size - 1)
        var xs = DoubleArray(8)
        for (r in rMin..rMax) {
            val y = r.toDouble()
            var crossings = 0
            for (k in 0 until count) {
                val j = if (k + 1 == count) 0 else k + 1
                if ((rows[k] > y) != (rows[j] > y)) {
                    if (crossings == xs.size) xs = xs.copyOf(crossings * 2)
                    xs[crossings++] = cols[k] + (y - rows[k]) * (cols[j] - cols[k]) / (rows[j] - rows[k])
                }
            }
            if (crossings < 2) continue
            xs.sort(0, crossings)
            var k = 0
            while (k + 1 < crossings) {
                val ca = ceil(xs[k]).toInt().coerceAtLeast(0)
                val cb = floor(xs[k + 1]).toInt().coerceAtMost(size - 1)
                if (ca <= cb) mask.set(r * size + ca, r * size + cb + 1, value)
                k += 2
            }
        }
    }
}

/** [CoastMasks] from mapsforge map files (the vector maps, not the world overview). */
class MapCoastMasks(paths: List<String>) : CoastMasks {
    private val handles: List<FileHandle> = paths.mapNotNull { runCatching { openFile(it) }.getOrNull() }
    private val maps: List<MapsforgeFile> = handles.mapNotNull { runCatching { MapsforgeFile(it) }.getOrNull() }

    override fun build(lat: Int, lon: Int, size: Int): Bits? = SeaMask.build(maps, lat, lon, size)

    override fun close() {
        handles.forEach { runCatching { it.close() } }
    }
}
