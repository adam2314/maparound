package app.maparound.stats

import app.maparound.data.CatalogEntry
import app.maparound.i18n.T
import app.maparound.map.MapRegions

// Plain Kotlin: how MapAround's own maps stand, region by region, for the data view: built in the
// latest monthly run, an older build, or none. From the map list (catalog.json, my-pipeline's) and
// the regions' outlines (regions.bin, the app's own, tools/regions/make_regions.py). Unit-tested.

/** How a region's MapAround map stands: the legend's classes, in this order. */
enum class BuildState { CURRENT, OLD, MISSING }

/**
 * A region's map: how it stands, when it was built and its size (for a region the list has in
 * parts, both sides of the date line: the oldest part's day, all their sizes), and why there's none
 * ([why]; null when the list doesn't have it at all).
 */
class RegionBuild(
    val state: BuildState,
    val builtAt: Long?,
    val sizeBytes: Long?,
    val why: CatalogEntry.Unavailable?,
)

/**
 * The map builds to show: the regions drawn ([shown]: the finest, not a country whose states cover
 * it), the rest ([wholes]: countries as one map, groups of states), how each region stands, the
 * latest build's time, and how many of the list's maps there are to download.
 */
class MapBuildsData(
    val shown: List<MapRegions.Region>,
    val wholes: List<MapRegions.Region>,
    val builds: Map<String, RegionBuild>,
    val latest: Long?,
    val built: Int,
    val total: Int,
) {
    private val byPath by lazy { (shown + wholes).associateBy { it.path } }

    fun region(path: String): MapRegions.Region? = byPath[path]

    /** The regions as one map that [region] is part of (its country, a group of states), the smallest first. */
    fun wholesAround(region: MapRegions.Region): List<MapRegions.Region> {
        val (lat, lon) = region.inner
        return wholes.filter { it !== region && it.boxSize > region.boxSize && it.contains(lat, lon) }.sortedBy { it.boxSize }
    }
}

object MapBuilds {
    /** A region's tap key: "data:m:europe/germany/bayern". */
    const val TAP = "data:m:"

    private const val DAY_MS = 86_400_000L

    /** A map built this long before the latest one is from an earlier monthly run. */
    const val EARLIER_RUN_MS = 20 * DAY_MS

    /** A map this old is old however recent the latest is (the monthly builds stopped). */
    const val OLD_MS = 45 * DAY_MS

    /**
     * A region this much covered by smaller ones (its states, the countries of a group) is shown
     * through them. On the app's regions, those wholes are 58–100 % covered (less where their
     * outline reaches further out to sea than their parts'), the others at most 14 %.
     */
    private const val COVERED = 0.5

    /** The grid of places a region is sampled at, to tell how much of it smaller ones cover. */
    private const val GRID = 16

    /** Green, orange, red. */
    val colors = listOf("#2e9e5b", "#f39c32", "#d9443a")

    /** The legend: the latest build, an older one, none. */
    val classes: List<StatClass>
        get() = BuildState.entries.map { StatClass(T.stats.buildState(it), colors[it.ordinal]) }

    /** A catalog path in parts, both sides of the date line: "europe/…/new-zealand-2". */
    private val PART = Regex("(.+)-\\d+")

    /** The map builds for [regions], from the map list ([catalog], as MapAroundCatalog.parse reads it), at [now]. */
    fun of(regions: List<MapRegions.Region>, catalog: List<CatalogEntry>, now: Long): MapBuildsData {
        val maps = catalog.filter { !it.isDirectory }
        val shown = shown(regions)
        val shownSet = shown.toHashSet()
        return MapBuildsData(
            shown = shown,
            wholes = regions.filter { it !in shownSet },
            builds = builds(regions.map { it.path }, maps, now),
            latest = latest(maps),
            built = maps.count { it.url != null },
            total = maps.size,
        )
    }

    /** The latest build's time: the newest map's. */
    private fun latest(maps: List<CatalogEntry>): Long? = maps.filter { it.url != null }.mapNotNull { it.modified }.maxOrNull()

    /**
     * How each region ([paths]) stands by the list's maps: a region's map, or its parts', the worst
     * of them. A region the list doesn't have has none.
     */
    fun builds(paths: Collection<String>, maps: List<CatalogEntry>, now: Long): Map<String, RegionBuild> {
        val known = paths.toHashSet()
        val latest = latest(maps)
        val parts = HashMap<String, MutableList<CatalogEntry>>()
        for (m in maps) {
            if (m.isDirectory) continue
            val p = m.path.removeSuffix(".map")
            val key = if (p in known) p else PART.matchEntire(p)?.groupValues?.get(1)?.takeIf { it in known } ?: continue
            parts.getOrPut(key) { ArrayList() } += m
        }
        return known.associateWith { path ->
            val ms = parts[path] ?: return@associateWith RegionBuild(BuildState.MISSING, null, null, null)
            val built = ms.filter { it.url != null }
            RegionBuild(
                state = ms.maxOf { stateOf(it, latest, now) },
                builtAt = built.mapNotNull { it.modified }.minOrNull(),
                sizeBytes = built.mapNotNull { it.sizeBytes }.takeIf { it.isNotEmpty() }?.sum(),
                why = ms.firstNotNullOfOrNull { it.unavailable },
            )
        }
    }

    /** One map's state: none to download; built before the latest run, or long ago; else the latest. */
    fun stateOf(m: CatalogEntry, latest: Long?, now: Long): BuildState {
        val at = m.modified
        return when {
            m.url == null -> BuildState.MISSING
            at == null -> BuildState.CURRENT
            latest != null && at < latest - EARLIER_RUN_MS -> BuildState.OLD
            now - at > OLD_MS -> BuildState.OLD
            else -> BuildState.CURRENT
        }
    }

    /**
     * The regions to draw: each one not mostly covered by smaller ones. A country whose states are
     * maps of their own (France, the United States' groups of states), or a group of countries (the
     * Alps), is shown through them, so the colours don't pile up; Switzerland, with Liechtenstein in
     * it, is drawn.
     */
    fun shown(regions: List<MapRegions.Region>): List<MapRegions.Region> = regions.filter { coveredShare(it, regions) < COVERED }

    /** How much of [r] smaller regions cover: the share of a grid of places in it that are in one. */
    fun coveredShare(r: MapRegions.Region, regions: List<MapRegions.Region>): Double {
        val box = r.box
        val smaller = regions.filter { o -> o !== r && o.boxSize < r.boxSize && o.box.intersects(box) }
        if (smaller.isEmpty()) return 0.0
        var inside = 0
        var covered = 0
        for (i in 0 until GRID) {
            val y = box.minLat + (box.maxLat - box.minLat) * (i + 0.5) / GRID
            for (j in 0 until GRID) {
                val x = box.minLon + (box.maxLon - box.minLon) * (j + 0.5) / GRID
                if (!r.containsMicro(y, x)) continue
                inside++
                if (smaller.any { it.containsMicro(y, x) }) covered++
            }
        }
        return if (inside == 0) 0.0 else covered.toDouble() / inside
    }

    /**
     * The regions drawn as GeoJSON, the biggest first (so smaller ones are on top where they meet):
     * each region's area ("role": "zone", as the time zones', drawn by the same layers) coloured by
     * how its map stands. Those whose class is in [hidden] are left out.
     */
    fun geoJson(data: MapBuildsData, hidden: Set<Int> = emptySet()): String {
        val sb = StringBuilder(2_000_000)
        sb.append("""{"type":"FeatureCollection","features":[""")
        var first = true
        for (r in data.shown.sortedByDescending { it.boxSize }) {
            val c = (data.builds[r.path]?.state ?: BuildState.MISSING).ordinal
            if (c in hidden) continue
            val polygons = polygons(r.rings.map { ring -> IntArray(ring.size) { ring[it] / 1000 } })
            if (polygons.isEmpty()) continue
            if (!first) sb.append(',')
            first = false
            sb.append("""{"type":"Feature","geometry":{"type":"MultiPolygon","coordinates":[""")
            polygons.forEachIndexed { pi, p ->
                if (pi > 0) sb.append(',')
                sb.append('[')
                p.forEachIndexed { ri, ring ->
                    if (ri > 0) sb.append(',')
                    // Outer rings anticlockwise, holes clockwise, as GeoJSON has them.
                    TimeZones.ring(sb, ring, clockwise = ri > 0)
                }
                sb.append(']')
            }
            sb.append("""]},"properties":{"role":"zone","tap":"$TAP${r.path}","color":"${colors[c]}"}}""")
        }
        return sb.append("]}").toString()
    }

    /**
     * Rings as regions.bin has them (outer rings and holes alike: inside is inside an odd number of
     * them; (lat, lon) pairs) as polygons: each outer ring with the holes straight inside it.
     */
    fun polygons(rings: List<IntArray>): List<List<IntArray>> {
        val rs = rings.filter { it.size >= 6 }
        val boxes = rs.map { r ->
            var s = Int.MAX_VALUE; var n = Int.MIN_VALUE; var w = Int.MAX_VALUE; var e = Int.MIN_VALUE
            for (k in r.indices step 2) { s = minOf(s, r[k]); n = maxOf(n, r[k]); w = minOf(w, r[k + 1]); e = maxOf(e, r[k + 1]) }
            intArrayOf(s, w, n, e)
        }
        // Whether ring j has ring i's first point in it.
        fun holds(j: Int, i: Int): Boolean {
            val y = rs[i][0]
            val x = rs[i][1]
            val b = boxes[j]
            return y >= b[0] && y <= b[2] && x >= b[1] && x <= b[3] && inside(rs[j], y, x)
        }
        val depth = IntArray(rs.size) { i -> rs.indices.count { j -> j != i && holds(j, i) } }
        val outers = rs.indices.filter { depth[it] % 2 == 0 }
        val polygons = outers.associateWith { mutableListOf(rs[it]) }
        for (h in rs.indices) {
            if (depth[h] % 2 == 0) continue
            val o = outers.firstOrNull { depth[it] == depth[h] - 1 && holds(it, h) } ?: continue
            polygons.getValue(o) += rs[h]
        }
        return outers.map { polygons.getValue(it) }
    }
}
