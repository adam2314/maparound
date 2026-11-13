package app.maparound.map

import app.maparound.data.MapNames
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.abs

/**
 * The areas of the regions MapAround's offline maps are made for: Geofabrik's download regions
 * (countries, and the states or provinces of the big ones), the same paths as the download list's,
 * packed into `regions.bin` by tools/regions/make_regions.py and simplified to about a kilometre.
 * The map screen tells from them which map is made for what it shows, and offers it where no
 * installed map reaches.
 */
class MapRegions private constructor(private val regions: List<Region>) {

    /** One region: its path in the download list ("europe/germany/bayern") and its area. */
    class Region internal constructor(val path: String, private val rings: List<IntArray>) {
        // Its box, in microdegrees.
        private val minLat: Int
        private val maxLat: Int
        private val minLon: Int
        private val maxLon: Int

        /** Its box, in microdegrees. */
        val box: GeoBox get() = GeoBox(minLat, minLon, maxLat, maxLon)

        init {
            var a = Int.MAX_VALUE
            var b = Int.MIN_VALUE
            var c = Int.MAX_VALUE
            var d = Int.MIN_VALUE
            for (r in rings) {
                for (i in r.indices step 2) {
                    a = minOf(a, r[i]); b = maxOf(b, r[i])
                    c = minOf(c, r[i + 1]); d = maxOf(d, r[i + 1])
                }
            }
            minLat = a; maxLat = b; minLon = c; maxLon = d
        }

        /** Its box's size, to put smaller (more specific) regions first. */
        internal val boxSize: Double get() = (maxLat - minLat).toDouble() * (maxLon - minLon).toDouble()

        /** The map's file name once downloaded ("europe__germany__bayern.map"). */
        val fileName: String get() = MapNames.localFileName("$path.map")

        /** "Bayern" */
        val title: String get() = MapNames.pretty(path.substringAfterLast('/'))

        /** "Bayern, Germany": a region of a country with the country's name. */
        val label: String
            get() {
                val parts = path.split('/')
                return if (parts.size >= 3) "$title, ${MapNames.pretty(parts[1])}" else title
            }

        /** Whether the place is in it (inside an odd number of its rings: holes are holes). */
        fun contains(lat: Double, lon: Double): Boolean = containsMicro(lat * 1e6, lon * 1e6)

        /** [contains] for microdegrees. */
        fun containsMicro(y: Double, x: Double): Boolean {
            if (y < minLat || y > maxLat || x < minLon || x > maxLon) return false
            var inside = false
            for (r in rings) {
                val n = r.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val yi = r[2 * i].toDouble()
                    val xi = r[2 * i + 1].toDouble()
                    val yj = r[2 * j].toDouble()
                    val xj = r[2 * j + 1].toDouble()
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }

        /**
         * The parts of a line ([coords]: lat, lon pairs in microdegrees) away from the region, each
         * at least two points: the line split where it crosses the outline, and in pieces of about
         * a kilometre, each dropped when it's in the region or within [marginM] of its outline
         * (the same border, drawn coarser: the map draws its own), unless [keep] keeps it there.
         */
        fun cutOut(coords: IntArray, marginM: Double = OUTLINE_MARGIN_M, keep: ((Double, Double) -> Boolean)? = null): List<IntArray> {
            val margin = marginM / 111_320.0 * 1e6
            if (!GeoBox.of(coords).intersects(box.grow(margin.toInt(), (margin * 3).toInt()))) return listOf(coords)
            return cutPieces(coords, PIECE_MICRO, ::outlineCrossings) { mLat, mLon ->
                (containsMicro(mLat, mLon) || nearOutline(mLat, mLon, margin)) && (keep == null || !keep(mLat, mLon))
            }
        }

        /** Where the segment a–b crosses the outline: fractions along it, added to [cuts]. */
        private fun outlineCrossings(aLat: Double, aLon: Double, bLat: Double, bLon: Double, cuts: MutableList<Double>) {
            val sMinLat = minOf(aLat, bLat)
            val sMaxLat = maxOf(aLat, bLat)
            val sMinLon = minOf(aLon, bLon)
            val sMaxLon = maxOf(aLon, bLon)
            if (sMaxLat < minLat || sMinLat > maxLat || sMaxLon < minLon || sMinLon > maxLon) return
            for (r in rings) {
                val n = r.size / 2
                var j = n - 1
                for (k in 0 until n) {
                    val pLat = r[2 * j].toDouble()
                    val pLon = r[2 * j + 1].toDouble()
                    val qLat = r[2 * k].toDouble()
                    val qLon = r[2 * k + 1].toDouble()
                    j = k
                    if (maxOf(pLat, qLat) < sMinLat || minOf(pLat, qLat) > sMaxLat) continue
                    if (maxOf(pLon, qLon) < sMinLon || minOf(pLon, qLon) > sMaxLon) continue
                    val dx = bLon - aLon
                    val dy = bLat - aLat
                    val ex = qLon - pLon
                    val ey = qLat - pLat
                    val den = dx * ey - dy * ex
                    if (abs(den) < 1e-9) continue
                    val t = ((pLon - aLon) * ey - (pLat - aLat) * ex) / den
                    val u = ((pLon - aLon) * dy - (pLat - aLat) * dx) / den
                    if (t > 0.0 && t < 1.0 && u >= 0.0 && u <= 1.0) cuts += t
                }
            }
        }

        /** Whether a point (microdegrees) is within [d] microdegrees (of latitude) of the outline. */
        private fun nearOutline(y: Double, x: Double, d: Double): Boolean {
            val k = cos(y / 1e6 * PI / 180).coerceAtLeast(0.05)
            val dx = d / k
            if (y < minLat - d || y > maxLat + d || x < minLon - dx || x > maxLon + dx) return false
            for (r in rings) {
                val n = r.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val pLat = r[2 * j].toDouble()
                    val pLon = r[2 * j + 1].toDouble()
                    val qLat = r[2 * i].toDouble()
                    val qLon = r[2 * i + 1].toDouble()
                    j = i
                    if (y < minOf(pLat, qLat) - d || y > maxOf(pLat, qLat) + d) continue
                    if (x < minOf(pLon, qLon) - dx || x > maxOf(pLon, qLon) + dx) continue
                    // Distance to the edge, with longitudes shrunk to the latitude's scale.
                    val ex = (qLon - pLon) * k
                    val ey = qLat - pLat
                    val px = (x - pLon) * k
                    val py = y - pLat
                    val len2 = ex * ex + ey * ey
                    val t = if (len2 == 0.0) 0.0 else ((px * ex + py * ey) / len2).coerceIn(0.0, 1.0)
                    if (hypot(px - t * ex, py - t * ey) < d) return true
                }
            }
            return false
        }
    }

    val size: Int get() = regions.size


    /** The regions at a place, the most specific (smallest) first: a state, then its country. */
    fun at(lat: Double, lon: Double): List<Region> = regions.filter { it.contains(lat, lon) }.sortedBy { it.boxSize }

    /** The region a downloaded map (by its file name) is made for, or null (a map imported from elsewhere). */
    fun byFileName(fileName: String): Region? = byFile[fileName]

    private val byFile by lazy { regions.associateBy { it.fileName } }

    companion object {
        /** How near its outline the world's lines go with the region (they're drawn coarser): 2 km. */
        const val OUTLINE_MARGIN_M = 2000.0

        /** The pieces lines are judged in: about a kilometre (microdegrees). */
        private const val PIECE_MICRO = 9000.0

        /** Reads `regions.bin`; null if it isn't one. */
        fun decode(bytes: ByteArray): MapRegions? {
            if (bytes.size < 5 || bytes.decodeToString(0, 4) != "MARG" || bytes[4].toInt() != 1) return null
            return runCatching {
                val r = PackedReader(bytes, 5)
                val out = ArrayList<Region>()
                repeat(r.int()) {
                    val path = r.text()
                    // 1e-3 degrees in the file.
                    val rings = List(r.int()) { r.points(1000) }
                    out += Region(path, rings)
                }
                MapRegions(out)
            }.getOrNull()
        }

        /** Where the app's `regions.bin` comes from (the platform sets it at start); none: no offers. */
        var source: (() -> ByteArray?)? = null

        /** The app's regions, read on first use (off the main thread). */
        val bundled: MapRegions? by lazy { runCatching { source?.invoke()?.let(::decode) }.getOrNull() }
    }
}
