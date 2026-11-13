package app.maparound.map

import kotlin.math.max
import kotlin.math.min

/**
 * The world for the offline map where the installed maps have nothing: land and sea, lakes, rivers,
 * and the names of countries, seas, cities, towns and lakes. From Natural Earth (public domain,
 * naturalearthdata.com), packed into `world.bin` by tools/world/make_world.py.
 *
 * Drawn at the overview zooms everywhere, and outside the installed country maps when zoomed in.
 * Accurate to a few hundred metres. Areas and lines are thinned per overview zoom the first time
 * they're needed, and clipped to the tile they're drawn on.
 */
class WorldData private constructor(
    private val land: List<Area>,
    private val lakes: List<Area>,
    private val rivers: List<RiverPiece>,
    private val labels: List<Label>,
) {
    /** A polygon: the outer ring, then its holes (rings are implicitly closed). */
    class Area internal constructor(val minZoom: Int, private val rings: List<IntArray>) {
        val box = GeoBox.of(rings[0])
        private val boxes = rings.map { GeoBox.of(it) }
        private val levels = arrayOfNulls<List<ClipRing>>(FULL_DETAIL_ZOOM + 1)

        /** Its rings as drawn at [zoom]; empty when it's smaller than a pixel there. */
        fun ringsAt(zoom: Int): List<ClipRing> {
            val z = min(max(zoom, 0), FULL_DETAIL_ZOOM)
            levels[z]?.let { return it }
            val tolerance = if (z >= FULL_DETAIL_ZOOM) 0.0 else halfPixel(z)
            val out = ArrayList<ClipRing>(rings.size)
            for (i in rings.indices) {
                val b = boxes[i]
                val tiny = b.maxLat - b.minLat < 2 * tolerance && b.maxLon - b.minLon < 2 * tolerance
                val thinned = if (tiny) null else if (tolerance > 0) douglasPeucker(rings[i], tolerance) else rings[i]
                if (thinned == null || thinned.size < 6) {
                    if (i == 0) break else continue
                }
                out += ClipRing(thinned)
            }
            return out.also { levels[z] = it }
        }
    }

    /** Part of a river; pieces of a river are consecutive and share their end points. */
    class RiverPiece internal constructor(val river: Int, val minZoom: Int, val name: String, coords: IntArray) : ThinnedLine(coords)

    /** A name on the map. Position in microdegrees. */
    class Label internal constructor(
        val kind: Kind,
        val minZoom: Int,
        val name: String,
        val population: Long,
        val lat: Int,
        val lon: Int,
    )

    enum class Kind { COUNTRY, CAPITAL, CITY, TOWN, OCEAN, SEA, LAKE }

    val landPoints: Int get() = land.sumOf { a -> a.ringsAt(FULL_DETAIL_ZOOM).sumOf { it.coords.size / 2 } }
    val labelCount: Int get() = labels.size
    val riverPieces: Int get() = rivers.size

    /**
     * Land at [zoom] cut to [clip]: per polygon, the pieces of its outer ring and of its holes
     * (Natural Earth runs them in opposite directions, and cutting keeps each ring's). Give [clip] a
     * margin beyond what's drawn, so the cut edges aren't seen.
     */
    fun land(zoom: Int, clip: GeoBox, visit: (outer: List<IntArray>, holes: List<IntArray>) -> Unit) = areas(land, zoom, clip, visit)

    /** Lakes and reservoirs at [zoom] cut to [clip], like [land]. */
    fun lakes(zoom: Int, clip: GeoBox, visit: (outer: List<IntArray>, holes: List<IntArray>) -> Unit) = areas(lakes, zoom, clip, visit)

    private fun areas(list: List<Area>, zoom: Int, clip: GeoBox, visit: (List<IntArray>, List<IntArray>) -> Unit) {
        for (a in list) {
            if (a.minZoom > zoom || !a.box.intersects(clip)) continue
            val rings = a.ringsAt(zoom)
            if (rings.isEmpty()) continue
            val outer = rings[0].clip(clip)
            if (outer.isEmpty()) continue
            val holes = ArrayList<IntArray>()
            for (i in 1 until rings.size) holes += rings[i].clip(clip)
            visit(outer, holes)
        }
    }

    /** Rivers at [zoom] cut to [clip], with their names (may be empty). */
    fun rivers(zoom: Int, clip: GeoBox, visit: (name: String, coords: IntArray) -> Unit) {
        val joiner = LineJoiner { key, coords ->
            val name = (key as RiverPiece).name
            for (part in clipLine(coords, clip)) visit(name, part)
        }
        var current: RiverPiece? = null
        for (p in rivers) {
            if (p.minZoom > zoom || !p.box.intersects(clip)) continue
            // The joiner keys on the river: the first piece seen stands for it.
            if (current == null || current.river != p.river) current = p
            joiner.add(current, p.coordsAt(zoom))
        }
        joiner.flush()
    }

    /** Names to show at [zoom] inside [box]. */
    fun labels(zoom: Int, box: GeoBox, visit: (Label) -> Unit) {
        for (l in labels) if (l.minZoom <= zoom && box.contains(l.lat, l.lon)) visit(l)
    }

    companion object {
        private const val PIECE = 64

        /** Reads `world.bin`; null if it isn't a world file. */
        fun decode(bytes: ByteArray): WorldData? {
            if (bytes.size < 5 || bytes.decodeToString(0, 4) != "MAWD" || bytes[4].toInt() != 1) return null
            val r = PackedReader(bytes, 5)
            // 1e-4 degrees in the file.
            val toMicro = 100
            fun areas(): List<Area> = List(r.int()) {
                val minZoom = r.byte()
                val rings = List(r.int()) { r.points(toMicro) }
                Area(minZoom, rings)
            }
            val land = areas()
            val lakes = areas()
            val rivers = ArrayList<RiverPiece>()
            for (river in 0 until r.int()) {
                val minZoom = r.byte()
                val name = r.text()
                for (c in cutLine(r.points(toMicro), PIECE)) rivers += RiverPiece(river, minZoom, name, c)
            }
            val kinds = Kind.entries
            val labels = List(r.int()) {
                val kind = kinds.getOrElse(r.byte()) { Kind.TOWN }
                val minZoom = r.byte()
                val name = r.text()
                val population = r.varint()
                Label(kind, minZoom, name, population, r.zigzag() * toMicro, r.zigzag() * toMicro)
            }
            return WorldData(land, lakes, rivers, labels)
        }
    }
}
