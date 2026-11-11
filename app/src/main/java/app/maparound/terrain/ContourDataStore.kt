package app.maparound.terrain

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.Way
import kotlin.math.ceil

/**
 * A virtual mapsforge data source that generates contour lines on the fly from elevation
 * tiles. Each line is a way tagged `contour=minor|major` and `ele=<metres>`, so the render
 * theme styles and labels it like any other map feature.
 *
 * @param seaSource the vector maps; their sea/land polygons are used to cut contours at the
 *   real coastline (elevation data often runs a little out into the sea in blocky steps).
 */
class ContourDataStore(
    private val dem: DemIndex,
    private val seaSource: MapDataStore? = null,
) : MapDataStore() {

    // Small: a zoom-12 tile in the Alps can hold tens of thousands of points.
    private val cache = object : LinkedHashMap<Tile, MapReadResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Tile, MapReadResult>?) = size > 8
    }

    // MultiMapDataStore merges this into its own box, so it must never be null.
    override fun boundingBox(): BoundingBox = dem.boundingBox() ?: BoundingBox(0.0, 0.0, 0.0, 0.0)

    override fun close() {
        synchronized(cache) { cache.clear() }
    }

    override fun getDataTimestamp(tile: Tile): Long = 0L

    override fun readPoiData(tile: Tile): MapReadResult = MapReadResult()

    override fun readMapData(tile: Tile): MapReadResult {
        synchronized(cache) { cache[tile] }?.let { return it }
        val result = generate(tile)
        synchronized(cache) { cache[tile] = result }
        return result
    }

    override fun startPosition(): LatLong? = dem.boundingBox()?.centerPoint
    override fun startZoomLevel(): Byte? = null

    override fun supportsTile(tile: Tile): Boolean {
        if (tile.zoomLevel < MIN_ZOOM) return false
        val bb = tile.boundingBox
        return dem.hasDataIn(bb.minLatitude, bb.minLongitude, bb.maxLatitude, bb.maxLongitude)
    }

    // Never claim a tile fully, so the real map data is always read as well.
    override fun supportsFullTile(tile: Tile): Boolean = false

    override fun supportsArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean =
        zoomLevel >= MIN_ZOOM && dem.hasDataIn(
            boundingBox.minLatitude, boundingBox.minLongitude, boundingBox.maxLatitude, boundingBox.maxLongitude,
        )

    override fun supportsFullArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean = false

    private fun generate(tile: Tile): MapReadResult {
        val result = MapReadResult()
        val bb = tile.boundingBox
        val latSpan = bb.maxLatitude - bb.minLatitude
        val lonSpan = bb.maxLongitude - bb.minLongitude

        // One grid cell per DEM sample, kept between 48 and 256 cells per side: fewer gives
        // angular lines when zoomed in, more costs time without visible gain.
        val cols = ceil(lonSpan / dem.finestStep).toInt().coerceIn(MIN_CELLS, MAX_CELLS) + 1
        val rows = ceil(latSpan / dem.finestStep).toInt().coerceIn(MIN_CELLS, MAX_CELLS) + 1
        val stepLat = latSpan / (rows - 1)
        val stepLon = lonSpan / (cols - 1)
        // One extra cell of margin on every side so lines run past the tile edge (no gaps).
        val gCols = cols + 2
        val gRows = rows + 2
        val top = bb.maxLatitude + stepLat
        val left = bb.minLongitude - stepLon

        val grid = FloatArray(gCols * gRows)
        var valid = 0
        for (r in 0 until gRows) {
            val lat = (top - r * stepLat).coerceIn(-85.0, 85.0)
            for (c in 0 until gCols) {
                val v = dem.elevation(lat, left + c * stepLon)
                grid[r * gCols + c] = v
                if (!v.isNaN()) valid++
            }
        }
        if (valid == 0) return result
        maskSea(tile, grid, gCols, gRows, top, left, stepLat, stepLon)

        val (minor, major) = Contours.intervalsFor(tile.zoomLevel.toInt())
        for (line in Contours.trace(grid, gCols, gRows, minor)) {
            val pts = Array(line.pointCount) { k ->
                LatLong(
                    (top - line.xy[2 * k + 1] * stepLat).coerceIn(-85.0, 85.0),
                    (left + line.xy[2 * k] * stepLon).coerceIn(-180.0, 180.0),
                )
            }
            val tags = listOf(
                if (line.level % major == 0) TAG_MAJOR else TAG_MINOR,
                Tag("ele", line.level.toString()),
            )
            result.ways.add(Way(LAYER, tags, arrayOf(pts), null))
        }
        return result
    }

    /**
     * Sets grid points that lie on sea to NaN. Mapsforge maps model the coast as sea polygons
     * (natural=sea, often whole sub-tiles) with land polygons (natural=nosea) painted on top.
     */
    private fun maskSea(
        tile: Tile, grid: FloatArray, cols: Int, rows: Int,
        top: Double, left: Double, stepLat: Double, stepLon: Double,
    ) {
        val source = seaSource ?: return
        if (!source.supportsTile(tile)) return
        val data = try {
            source.readMapData(tile)
        } catch (e: Exception) {
            return
        } ?: return
        val sea = ArrayList<Array<LatLong>>()
        val land = ArrayList<Array<LatLong>>()
        for (way in data.ways) {
            val kind = way.tags.firstOrNull { it.key == "natural" }?.value ?: continue
            when (kind) {
                "sea", "issea" -> sea.addAll(way.latLongs)
                "nosea", "land" -> land.addAll(way.latLongs)
            }
        }
        if (sea.isEmpty() && !data.isWater) return
        val isSea = BooleanArray(cols * rows) { sea.isEmpty() } // isWater tile with no polygons: all sea
        fill(sea, true, isSea, cols, rows, top, left, stepLat, stepLon)
        fill(land, false, isSea, cols, rows, top, left, stepLat, stepLon)
        for (k in grid.indices) if (isSea[k]) grid[k] = Float.NaN
    }

    /**
     * Scanline fill of each ring into [mask] (one scan line per grid row). Rings are filled one
     * by one, so overlapping polygons combine as a union.
     */
    private fun fill(
        rings: List<Array<LatLong>>, value: Boolean, mask: BooleanArray, cols: Int, rows: Int,
        top: Double, left: Double, stepLat: Double, stepLon: Double,
    ) {
        var crossings = DoubleArray(16)
        for (ring in rings) {
            if (ring.size < 3) continue
            for (r in 0 until rows) {
                val lat = top - r * stepLat
                var n = 0
                for (k in ring.indices) {
                    val a = ring[k]
                    val b = ring[(k + 1) % ring.size]
                    if ((a.latitude > lat) != (b.latitude > lat)) {
                        val x = a.longitude + (lat - a.latitude) * (b.longitude - a.longitude) / (b.latitude - a.latitude)
                        if (n == crossings.size) crossings = crossings.copyOf(n * 2)
                        crossings[n++] = x
                    }
                }
                if (n < 2) continue
                java.util.Arrays.sort(crossings, 0, n)
                var k = 0
                while (k + 1 < n) {
                    val c0 = ceil((crossings[k] - left) / stepLon).toInt().coerceAtLeast(0)
                    val c1 = kotlin.math.floor((crossings[k + 1] - left) / stepLon).toInt().coerceAtMost(cols - 1)
                    for (c in c0..c1) mask[r * cols + c] = value
                    k += 2
                }
            }
        }
    }

    companion object {
        const val MIN_ZOOM: Byte = 11
        private const val MIN_CELLS = 48
        private const val MAX_CELLS = 256
        private const val LAYER: Byte = 5 // ground level (OSM layer 0)
        private val TAG_MAJOR = Tag("contour", "major")
        private val TAG_MINOR = Tag("contour", "minor")
    }
}
