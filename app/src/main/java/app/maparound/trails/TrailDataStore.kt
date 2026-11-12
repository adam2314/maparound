package app.maparound.trails

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tag
import org.mapsforge.core.model.Tile
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.datastore.MapReadResult
import org.mapsforge.map.datastore.Way
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * All downloaded trail cells, merged (ways shared by neighbouring cells kept once) and indexed:
 * the route ways, and the cliffs, arêtes, ridges and earth banks fetched with them.
 */
class TrailIndex(dir: File) {
    /** A route way (with its routes) or a feature. */
    private class Entry(
        val way: TrailWay?,
        val routes: List<TrailRoute>,
        val feature: TrailFeature?,
        val minLat: Int, val minLon: Int, val maxLat: Int, val maxLon: Int,
    )

    private val entries = ArrayList<Entry>()
    private val grid = HashMap<Long, MutableList<Int>>()
    val isEmpty: Boolean get() = entries.isEmpty()
    val wayCount: Int get() = entries.count { it.way != null }
    val featureCount: Int get() = entries.count { it.feature != null }

    init {
        val seenWays = HashSet<Long>()
        val seenFeatures = HashSet<Long>()
        dir.listFiles { f -> f.name.endsWith(".trails") }.orEmpty().sortedBy { it.name }.forEach { f ->
            val cell = runCatching { TrailCellIO.read(f.path) }.getOrNull() ?: return@forEach
            for (w in cell.ways) if (seenWays.add(w.id)) add(w.coords) { a, b, c, d -> Entry(w, w.routes.map { cell.routes[it] }, null, a, b, c, d) }
            for (x in cell.features) if (seenFeatures.add(x.id)) add(x.coords) { a, b, c, d -> Entry(null, emptyList(), x, a, b, c, d) }
        }
    }

    private inline fun add(coords: IntArray, entry: (Int, Int, Int, Int) -> Entry) {
        var a = Int.MAX_VALUE; var b = Int.MAX_VALUE; var c = Int.MIN_VALUE; var d = Int.MIN_VALUE
        for (k in coords.indices step 2) {
            a = min(a, coords[k]); c = max(c, coords[k])
            b = min(b, coords[k + 1]); d = max(d, coords[k + 1])
        }
        val idx = entries.size
        entries += entry(a, b, c, d)
        for (gy in bucket(a)..bucket(c)) for (gx in bucket(b)..bucket(d)) {
            grid.getOrPut(key(gy, gx)) { ArrayList(4) } += idx
        }
    }

    fun boundingBox(): BoundingBox? {
        if (entries.isEmpty()) return null
        return BoundingBox(
            entries.minOf { it.minLat } / 1e6, entries.minOf { it.minLon } / 1e6,
            entries.maxOf { it.maxLat } / 1e6, entries.maxOf { it.maxLon } / 1e6,
        )
    }

    private inline fun near(bb: BoundingBox, visit: (Entry) -> Unit) {
        val a = (bb.minLatitude * 1e6).toInt(); val c = (bb.maxLatitude * 1e6).toInt()
        val b = (bb.minLongitude * 1e6).toInt(); val d = (bb.maxLongitude * 1e6).toInt()
        val done = HashSet<Int>()
        for (gy in bucket(a)..bucket(c)) for (gx in bucket(b)..bucket(d)) {
            for (i in grid[key(gy, gx)] ?: continue) {
                if (!done.add(i)) continue
                val e = entries[i]
                if (e.maxLat < a || e.minLat > c || e.maxLon < b || e.minLon > d) continue
                visit(e)
            }
        }
    }

    /** Route ways whose bounding box intersects the given box, each with the routes it belongs to. */
    fun query(bb: BoundingBox, visit: (TrailWay, List<TrailRoute>) -> Unit) = near(bb) { e -> e.way?.let { visit(it, e.routes) } }

    /** Cliffs, arêtes, ridges and earth banks whose bounding box intersects the given box. */
    fun queryFeatures(bb: BoundingBox, visit: (TrailFeature) -> Unit) = near(bb) { e -> e.feature?.let(visit) }

    fun hasDataIn(bb: BoundingBox, routes: Boolean = true, features: Boolean = true): Boolean {
        var found = false
        near(bb) { e -> if ((routes && e.way != null) || (features && e.feature != null)) found = true }
        return found
    }

    private fun bucket(micro: Int) = floor(micro / BUCKET).toInt()
    private fun key(gy: Int, gx: Int): Long = (gy.toLong() shl 32) or (gx.toLong() and 0xFFFFFFFFL)

    companion object {
        private const val BUCKET = 50_000.0 // 0.05° grid
    }
}

/**
 * Virtual mapsforge data source for marked trails. Each way is emitted once per distinct waymark
 * colour, tagged so the theme can draw the colours side by side:
 *  `trail=hiking`, `trail_colour=red|blue|…`, `trail_offset=0|l05|r05|l1|r1|l15|r15`,
 * and on the first copy `trail_n=<count>` plus the most important route's `name` / `ref`.
 *
 * Also the cliffs, arêtes, ridges and earth banks downloaded with the trails, from zoom
 * [FEATURE_MIN_ZOOM], as `natural=cliff|arete|ridge|earth_bank` ways like in OSM; those come
 * even when the trail lines are off ([routes]).
 */
class TrailDataStore(private val index: TrailIndex, private val routes: Boolean = true) : MapDataStore() {

    override fun boundingBox(): BoundingBox = index.boundingBox() ?: BoundingBox(0.0, 0.0, 0.0, 0.0)
    override fun close() = Unit
    override fun getDataTimestamp(tile: Tile): Long = 0L
    override fun readPoiData(tile: Tile): MapReadResult = MapReadResult()
    override fun startPosition(): LatLong? = null
    override fun startZoomLevel(): Byte? = null
    override fun supportsTile(tile: Tile): Boolean = supportsArea(tile.boundingBox, tile.zoomLevel)
    override fun supportsFullTile(tile: Tile): Boolean = false
    override fun supportsArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean =
        zoomLevel >= MIN_ZOOM && index.hasDataIn(boundingBox, routes = routes, features = zoomLevel >= FEATURE_MIN_ZOOM)
    override fun supportsFullArea(boundingBox: BoundingBox, zoomLevel: Byte): Boolean = false

    override fun readMapData(tile: Tile): MapReadResult {
        val result = MapReadResult()
        if (tile.zoomLevel >= FEATURE_MIN_ZOOM) {
            index.queryFeatures(tile.boundingBox) { f ->
                val pts = Array(f.coords.size / 2) { k -> LatLong(f.coords[2 * k] / 1e6, f.coords[2 * k + 1] / 1e6) }
                result.ways.add(Way(LAYER, listOf(FEATURE_TAGS[f.kind.ordinal]), arrayOf(pts), null))
            }
        }
        if (!routes) return result
        index.query(tile.boundingBox) { way, routes ->
            // Most important routes first (international … local), one line per colour, max 4.
            val ordered = routes.sortedBy { it.network }
            val colours = ordered.map { it.colour }.distinct().take(4)
            val pts = Array(way.coords.size / 2) { k ->
                LatLong(way.coords[2 * k] / 1e6, way.coords[2 * k + 1] / 1e6)
            }
            val latLongs = arrayOf(pts)
            val offsets = OFFSETS[colours.size - 1]
            colours.forEachIndexed { k, colour ->
                val tags = ArrayList<Tag>(6)
                tags += TAG_HIKING
                tags += Tag("trail_colour", colour.key)
                tags += Tag("trail_offset", offsets[k])
                if (k == 0) {
                    tags += Tag("trail_n", colours.size.toString())
                    val main = ordered.first()
                    if (main.name.isNotEmpty()) tags += Tag("name", main.name)
                    if (main.ref.isNotEmpty()) tags += Tag("ref", main.ref)
                }
                result.ways.add(Way(LAYER, tags, latLongs, null))
            }
        }
        return result
    }

    companion object {
        const val MIN_ZOOM: Byte = 11

        /** Cliffs and ridges from here (topo.xml draws them from 13). */
        const val FEATURE_MIN_ZOOM: Byte = 13
        private const val LAYER: Byte = 5
        private val TAG_HIKING = Tag("trail", "hiking")
        private val FEATURE_TAGS = TrailFeature.Kind.entries.map { Tag("natural", it.tag) }
        private val OFFSETS = arrayOf(
            arrayOf("0"),
            arrayOf("l05", "r05"),
            arrayOf("l1", "0", "r1"),
            arrayOf("l15", "l05", "r05", "r15"),
        )
    }
}
