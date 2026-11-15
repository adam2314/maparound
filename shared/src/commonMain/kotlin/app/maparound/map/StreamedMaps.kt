package app.maparound.map

import app.maparound.data.MapNames

/**
 * A map the vector map reads over the internet where no installed map reaches, in pieces as it
 * needs them ([app.maparound.shared.stream.MapStream]).
 */
data class RemoteMap(
    /** Its region in the download list, e.g. "europe/germany/bayern". */
    val path: String,
    val url: String,
    /** Its region's bounds. */
    val box: GeoBox,
    /** One of MapAround's own maps, with the marked trails built in (else mapsforge.org's). */
    val trails: Boolean,
    /** Where it really is: the boxes of its outline's pieces (not the sea between islands). */
    val parts: List<GeoBox> = listOf(box),
) {
    /** Whether it has anything in [area]. */
    fun reaches(area: GeoBox): Boolean = box.intersects(area) && parts.any { it.intersects(area) }

    /** The name it would have installed ("europe__germany__bayern.map"). */
    val fileName: String get() = MapNames.localFileName("$path.map")
}

// Plain Kotlin: which maps to stream. Unit-tested.

object StreamPlan {
    /**
     * The maps to stream for the download list's [regions] (path → bounds): MapAround's map
     * ([built], path → address) where there is one, else mapsforge.org's ([basic], path → its
     * address, or those of its parts where it's split in two or three there).
     *
     * Each region's most detailed maps (a big country's states rather than the whole country),
     * except the whole country when MapAround has built it and not all its states (its marked
     * trails, grades and all), or when some of its states have no map anywhere. Not the areas of
     * several countries (the US Midwest) whose countries or states are streamed anyway: where
     * two or more regions streamed lie [within] it (outer, inner: by their outlines), filling
     * half of it or more.
     *
     * Nothing for a region whose map is [installed] (file names), nor for its parts or the
     * country it's part of; nor for a region in the middle of one of the [otherMaps] (installed
     * maps that aren't of a region, by their bounds).
     */
    fun choose(
        regions: Map<String, GeoBox>,
        built: Map<String, String>,
        basic: Map<String, List<String>>,
        installed: Set<String>,
        otherMaps: List<GeoBox> = emptyList(),
        within: (outer: String, inner: String) -> Boolean = { o, i ->
            val b = regions.getValue(i)
            regions.getValue(o).contains(middle(b.minLat, b.maxLat), middle(b.minLon, b.maxLon))
        },
    ): List<RemoteMap> {
        val paths = regions.keys
        fun parentOf(p: String): String? {
            var q = p
            while ('/' in q) {
                q = q.substringBeforeLast('/')
                if (q in paths) return q
            }
            return null
        }
        val children = paths.groupBy(::parentOf)
        val installedPaths = paths.filter { MapNames.localFileName("$it.map") in installed }.toSet()
        fun urls(p: String): List<String> = built[p]?.let(::listOf) ?: basic[p].orEmpty()
        fun installedWithin(p: String): Boolean = p in installedPaths || children[p].orEmpty().any(::installedWithin)
        fun covered(p: String): Boolean = urls(p).isNotEmpty() || children[p].orEmpty().let { it.isNotEmpty() && it.all(::covered) }
        fun pick(p: String): List<String> {
            if (p in installedPaths) return emptyList()
            val parts = children[p].orEmpty()
            if (parts.isEmpty()) return if (urls(p).isEmpty()) emptyList() else listOf(p)
            val whole = urls(p).isNotEmpty() && parts.none(::installedWithin) &&
                ((p in built && !parts.all { it in built }) || !parts.all(::covered))
            return if (whole) listOf(p) else parts.flatMap(::pick)
        }
        val picked = children[null].orEmpty().sorted().flatMap(::pick)
        // Areas of several countries: most of them taken by smaller regions streamed anyway.
        fun area(b: GeoBox) = (b.maxLat - b.minLat).toDouble() * (b.maxLon - b.minLon).toDouble()
        val several = picked.filter { p ->
            val box = regions.getValue(p)
            val inside = picked.filter { q -> q != p && area(regions.getValue(q)) < area(box) && within(p, q) }
            inside.size >= 2 && inside.sumOf { area(regions.getValue(it)) } >= area(box) / 2
        }.toSet()
        return picked.filterNot { it in several }
            .flatMap { p -> urls(p).map { RemoteMap(p, it, regions.getValue(p), p in built) } }
            .filterNot { m -> otherMaps.any { it.contains(middle(m.box.minLat, m.box.maxLat), middle(m.box.minLon, m.box.maxLon)) } }
    }

    private fun middle(a: Int, b: Int) = ((a.toLong() + b) / 2).toInt()
}
