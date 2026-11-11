package app.maparound.terrain

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.datastore.MapDataStore
import app.maparound.shared.util.Bits
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Rasterises the sea of the installed vector maps onto an elevation tile's grid.
 *
 * Elevation data tends to spill land heights a few hundred metres past the real coastline in
 * blocky steps, which hill shading turns into stair-step lines in the sea. Flattening every sea
 * pixel to 0 m makes shading (and contours) end exactly at the OSM coastline.
 *
 * Mapsforge maps model the coast as `natural=sea` polygons (often whole sub-tiles) with
 * `natural=nosea` land polygons painted over them, so sea = union(sea) minus union(nosea).
 */
object CoastMask {
    private const val READ_ZOOM: Byte = 12

    /**
     * @return a bit per grid sample (row-major, row 0 = north edge) set where the maps say sea,
     *   or null when the maps have no sea in this 1° cell (or don't cover it at all).
     */
    fun build(maps: MapDataStore, south: Int, west: Int, size: Int): Bits? {
        val sea = ArrayList<Array<LatLong>>()
        val land = ArrayList<Array<LatLong>>()
        var anyWaterTile = false
        val x0 = MercatorProjection.longitudeToTileX(west.toDouble(), READ_ZOOM)
        val x1 = MercatorProjection.longitudeToTileX(west + 1 - 1e-9, READ_ZOOM)
        val y0 = MercatorProjection.latitudeToTileY((south + 1).toDouble().coerceAtMost(85.0), READ_ZOOM)
        val y1 = MercatorProjection.latitudeToTileY((south + 1e-9).coerceAtLeast(-85.0), READ_ZOOM)
        val waterTiles = ArrayList<Tile>()
        for (x in x0..x1) for (y in y0..y1) {
            val tile = Tile(x, y, READ_ZOOM, 256)
            if (!maps.supportsTile(tile)) continue
            val data = try { maps.readMapData(tile) } catch (e: Exception) { null } ?: continue
            var tileSea = false
            for (way in data.ways) {
                when (way.tags.firstOrNull { it.key == "natural" }?.value) {
                    "sea", "issea" -> { sea.addAll(way.latLongs); tileSea = true }
                    "nosea", "land" -> land.addAll(way.latLongs)
                }
            }
            if (data.isWater && !tileSea) { anyWaterTile = true; waterTiles += tile }
        }
        if (sea.isEmpty() && !anyWaterTile) return null

        val mask = Bits(size * size)
        val n = size - 1
        fun rowOf(lat: Double) = (south + 1 - lat) * n
        fun colOf(lon: Double) = (lon - west) * n
        // Whole-water tiles first, then sea polygons, then carve the land back out.
        for (t in waterTiles) {
            val bb = t.boundingBox
            fillRect(mask, size, rowOf(bb.maxLatitude), rowOf(bb.minLatitude), colOf(bb.minLongitude), colOf(bb.maxLongitude), true)
        }
        for (ring in sea) fillRing(mask, size, ring, ::rowOf, ::colOf, true)
        for (ring in land) fillRing(mask, size, ring, ::rowOf, ::colOf, false)
        return if (mask.isEmpty) null else mask
    }

    private fun fillRect(mask: Bits, size: Int, r0: Double, r1: Double, c0: Double, c1: Double, value: Boolean) {
        val ra = ceil(r0).toInt().coerceAtLeast(0)
        val rb = floor(r1).toInt().coerceAtMost(size - 1)
        val ca = ceil(c0).toInt().coerceAtLeast(0)
        val cb = floor(c1).toInt().coerceAtMost(size - 1)
        if (ca > cb) return
        for (r in ra..rb) mask.set(r * size + ca, r * size + cb + 1, value)
    }

    /** Scanline fill of one ring; rings are filled one by one so overlaps form a union. */
    private fun fillRing(
        mask: Bits, size: Int, ring: Array<LatLong>,
        rowOf: (Double) -> Double, colOf: (Double) -> Double, value: Boolean,
    ) {
        if (ring.size < 3) return
        val rows = DoubleArray(ring.size) { rowOf(ring[it].latitude) }
        val cols = DoubleArray(ring.size) { colOf(ring[it].longitude) }
        val rMin = ceil(rows.min()).toInt().coerceAtLeast(0)
        val rMax = floor(rows.max()).toInt().coerceAtMost(size - 1)
        var xs = DoubleArray(8)
        for (r in rMin..rMax) {
            val y = r.toDouble()
            var count = 0
            for (k in rows.indices) {
                val j = if (k + 1 == rows.size) 0 else k + 1
                if ((rows[k] > y) != (rows[j] > y)) {
                    if (count == xs.size) xs = xs.copyOf(count * 2)
                    xs[count++] = cols[k] + (y - rows[k]) * (cols[j] - cols[k]) / (rows[j] - rows[k])
                }
            }
            if (count < 2) continue
            java.util.Arrays.sort(xs, 0, count)
            var k = 0
            while (k + 1 < count) {
                val ca = ceil(xs[k]).toInt().coerceAtLeast(0)
                val cb = floor(xs[k + 1]).toInt().coerceAtMost(size - 1)
                if (ca <= cb) mask.set(r * size + ca, r * size + cb + 1, value)
                k += 2
            }
        }
    }
}
