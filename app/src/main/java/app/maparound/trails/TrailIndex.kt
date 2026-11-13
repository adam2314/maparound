package app.maparound.trails

import org.mapsforge.core.model.BoundingBox
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
 * How marked trails are tagged for the map's styles: each way once per distinct waymark colour,
 * so the style can draw the colours side by side: `trail=hiking`, `trail_colour=red|blue|…`,
 * `trail_offset=0|l05|r05|l1|r1|l15|r15`, and on the first copy `trail_n=<count>` plus the most
 * important route's `name` / `ref`. The cliffs, arêtes, ridges and earth banks downloaded with
 * them are `natural=cliff|arete|ridge|earth_bank` ways like in OSM, from [FEATURE_MIN_ZOOM].
 */
object TrailTags {
    /** Trail lines from this zoom (256-pixel tiles). */
    const val MIN_ZOOM: Byte = 11

    /** Cliffs and ridges from here (topo.xml draws them from 13). */
    const val FEATURE_MIN_ZOOM: Byte = 13

    private val OFFSETS = arrayOf(
        arrayOf("0"),
        arrayOf("l05", "r05"),
        arrayOf("l1", "0", "r1"),
        arrayOf("l15", "l05", "r05", "r15"),
    )

    /** The copies of a way on [routes]: one tag set per waymark colour (at most 4), most important first. */
    fun lines(routes: List<TrailRoute>): List<Map<String, String>> {
        val ordered = routes.sortedBy { it.network }
        val colours = ordered.map { it.colour }.distinct().take(4)
        if (colours.isEmpty()) return emptyList()
        val offsets = OFFSETS[colours.size - 1]
        return colours.mapIndexed { k, colour ->
            val tags = LinkedHashMap<String, String>()
            tags["trail"] = "hiking"
            tags["trail_colour"] = colour.key
            tags["trail_offset"] = offsets[k]
            if (k == 0) {
                tags["trail_n"] = colours.size.toString()
                val main = ordered.first()
                if (main.name.isNotEmpty()) tags["name"] = main.name
                if (main.ref.isNotEmpty()) tags["ref"] = main.ref
            }
            tags
        }
    }
}
