package app.maparound.raster

import app.maparound.routing.GeoPoint
import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.latin1
import app.maparound.shared.util.toDegrees
import app.maparound.shared.util.toRadians
import app.maparound.tools.Ellipsoid
import app.maparound.tools.Itm
import app.maparound.tools.TransverseMercator
import app.maparound.tools.Utm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Plain Kotlin: reading TwoNav / CompeGPS ".rmap" raster maps (scanned or rendered maps as a JPEG
// tile pyramid with an embedded calibration). Unit-tested. Layout as read by QMapShack's
// CMapRMAP (GPL-3); calibration fitted through all points rather than assuming corners.

/** Random access to the file (a FileChannel on Android, a byte array in tests). */
fun interface RandomRead {
    /** Reads exactly [length] bytes at [position] into a new array (shorter only at end of file). */
    fun read(position: Long, length: Int): ByteArray
}

class RmapException(message: String) : Exception(message)

class RmapFile(private val source: RandomRead, val sizeBytes: Long) {
    class Level(val width: Int, val height: Int, val xTiles: Int, val yTiles: Int, val tileOffsets: LongArray) {
        /** How many level-0 pixels one pixel of this level covers. */
        var factor = 1.0
            internal set
    }

    val width: Int
    val height: Int
    val tileWidth: Int
    val tileHeight: Int
    val levels: List<Level>
    val calibration: Calibration

    init {
        val h = le(source.read(0, 71))
        val magic = h.bytes(19)
        if (latin1(magic) != "CompeGPSRasterImage") throw RmapException("Not an RMAP file")
        val tag1 = h.int()
        val tag2 = h.int()
        if (tag1 != 10 || tag2 != 7) throw RmapException("Unsupported RMAP sub-format ($tag1/$tag2)")
        h.int()
        width = abs(h.int())
        height = abs(h.int()) // stored negative (rows go top-down)
        h.int()
        h.int()
        tileWidth = h.int()
        tileHeight = h.int()
        val mapDataOffset = h.long()
        h.int()
        val nLevels = h.int()
        if (nLevels !in 1..64 || tileWidth !in 16..4096 || tileHeight !in 16..4096) throw RmapException("Damaged RMAP header")
        val levelOffsets = le(source.read(71, 8 * nLevels)).let { b -> LongArray(nLevels) { b.long() } }
        levels = levelOffsets.map { off ->
            val lh = le(source.read(off, 16))
            val w = abs(lh.int())
            val hgt = abs(lh.int())
            val xt = lh.int()
            val yt = lh.int()
            if (xt <= 0 || yt <= 0 || xt.toLong() * yt > 4_000_000) throw RmapException("Damaged RMAP level")
            val offs = le(source.read(off + 16, 8 * xt * yt)).let { b -> LongArray(xt * yt) { b.long() } }
            Level(w, hgt, xt, yt, offs)
        }
        levels.forEach { it.factor = levels[0].width.toDouble() / it.width }
        val textHeader = le(source.read(mapDataOffset, 8))
        textHeader.int()
        val textLen = textHeader.int()
        if (textLen !in 1..1_000_000) throw RmapException("Missing calibration")
        val raw = source.read(mapDataOffset + 8, textLen)
        val text = runCatching { raw.decodeToString(throwOnInvalidSequence = true) }.getOrElse { latin1(raw) }
        calibration = Calibration.parse(text)
    }

    /** The encoded image (JPEG) of one tile, or null where the map has none. */
    fun tileBytes(level: Int, x: Int, y: Int): ByteArray? {
        val l = levels[level]
        if (x !in 0 until l.xTiles || y !in 0 until l.yTiles) return null
        val off = l.tileOffsets[y * l.xTiles + x]
        if (off <= 0 || off >= sizeBytes) return null
        val head = le(source.read(off, 8))
        head.int() // tag (7 = JPEG in practice)
        val len = head.int()
        val n = if (len in 1..32_000_000 && off + 8 + len <= sizeBytes) len else minOf(4_000_000L, sizeBytes - off - 8).toInt()
        return source.read(off + 8, n)
    }

    /** The level whose pixels best match [level0PixelsPerOutputPixel] (prefers the sharper one). */
    fun bestLevel(level0PixelsPerOutputPixel: Double): Int {
        var best = 0
        for (i in levels.indices) if (levels[i].factor <= level0PixelsPerOutputPixel * 1.25) best = i
        return best
    }

    private fun le(bytes: ByteArray) = ByteReader(bytes, littleEndian = true)
}

/** A map projection between WGS 84 latitude/longitude and the map's own coordinates. */
interface MapProjection {
    val name: String
    fun forward(lat: Double, lon: Double): DoubleArray
    fun inverse(x: Double, y: Double): GeoPoint
}

/**
 * The calibration of an RMAP (CompeGPS map text): its projection and an affine transform from
 * level-0 pixels to map coordinates, fitted by least squares through all calibration points.
 */
class Calibration(val projection: MapProjection, private val a: DoubleArray, val datum: String) {
    // pixel -> map:  X = a0 + a1·px + a2·py,  Y = a3 + a4·px + a5·py
    private val det = a[1] * a[5] - a[2] * a[4]

    fun pixelToMap(px: Double, py: Double) = doubleArrayOf(a[0] + a[1] * px + a[2] * py, a[3] + a[4] * px + a[5] * py)

    fun mapToPixel(x: Double, y: Double): DoubleArray {
        val dx = x - a[0]
        val dy = y - a[3]
        return doubleArrayOf((a[5] * dx - a[2] * dy) / det, (-a[4] * dx + a[1] * dy) / det)
    }

    fun pixelToLatLon(px: Double, py: Double): GeoPoint = pixelToMap(px, py).let { projection.inverse(it[0], it[1]) }

    fun latLonToPixel(lat: Double, lon: Double): DoubleArray = projection.forward(lat, lon).let { mapToPixel(it[0], it[1]) }

    companion object {
        fun parse(text: String): Calibration {
            val lines = text.split("\r\n", "\n").map { it.trim() }
            fun value(key: String) = lines.firstOrNull { it.startsWith("$key=", ignoreCase = true) }?.substringAfter('=')?.trim()
            val projectionText = value("Projection") ?: throw RmapException("No projection in the calibration")
            val datum = value("Datum") ?: "WGS 84"
            val points = lines.filter { Regex("^P\\d+=", RegexOption.IGNORE_CASE).containsMatchIn(it) }.mapNotNull { line ->
                val v = line.substringAfter('=').split(',').map { it.trim() }
                if (v.size < 5) return@mapNotNull null
                val px = v[0].toDoubleOrNull() ?: return@mapNotNull null
                val py = v[1].toDoubleOrNull() ?: return@mapNotNull null
                val c3 = v[3].toDoubleOrNull() ?: return@mapNotNull null
                val c4 = v[4].toDoubleOrNull() ?: return@mapNotNull null
                CalPoint(px, py, v[2].equals("A", ignoreCase = true), c3, c4)
            }
            if (points.size < 2) throw RmapException("Too few calibration points")
            val allLonLat = points.all { it.lonLat }
            // Projected points in the Israeli grid's range (E 100–300 km, N 350–850 km).
            val itmLike = points.none { it.lonLat } && points.all { it.v1 in 100_000.0..300_000.0 && it.v2 in 350_000.0..850_000.0 }
            val projection = Projections.of(projectionText, datum, allLonLat, itmLike)
            // Calibration points given as longitude/latitude are projected first.
            val mapPts = points.map { p -> if (p.lonLat) projection.forward(p.v2, p.v1) else doubleArrayOf(p.v1, p.v2) }
            return Calibration(projection, fit(points.map { it.px }, points.map { it.py }, mapPts), datum)
        }

        private class CalPoint(val px: Double, val py: Double, val lonLat: Boolean, val v1: Double, val v2: Double)

        /** Least-squares affine fit; with two points, a north-up scale and offset. */
        private fun fit(px: List<Double>, py: List<Double>, map: List<DoubleArray>): DoubleArray {
            val n = px.size
            if (n == 2) {
                val sx = (map[1][0] - map[0][0]) / (px[1] - px[0])
                val sy = (map[1][1] - map[0][1]) / (py[1] - py[0])
                if (!sx.isFinite() || !sy.isFinite()) throw RmapException("Calibration points are in a line")
                return doubleArrayOf(map[0][0] - sx * px[0], sx, 0.0, map[0][1] - sy * py[0], 0.0, sy)
            }
            // Normal equations for [1, px, py] against X and against Y.
            val m = Array(3) { DoubleArray(3) }
            val bx = DoubleArray(3)
            val by = DoubleArray(3)
            for (i in 0 until n) {
                val r = doubleArrayOf(1.0, px[i], py[i])
                for (j in 0..2) {
                    for (k in 0..2) m[j][k] += r[j] * r[k]
                    bx[j] += r[j] * map[i][0]
                    by[j] += r[j] * map[i][1]
                }
            }
            val cx = solve3(m, bx) ?: throw RmapException("Calibration points are in a line")
            val cy = solve3(m, by) ?: throw RmapException("Calibration points are in a line")
            return doubleArrayOf(cx[0], cx[1], cx[2], cy[0], cy[1], cy[2])
        }

        private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            fun det(a: Array<DoubleArray>) =
                a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
            val d = det(m)
            if (abs(d) < 1e-12 * (abs(m[1][1]) + 1).pow(2)) return null
            return DoubleArray(3) { col -> det(Array(3) { r -> DoubleArray(3) { c -> if (c == col) b[r] else m[r][c] } }) / d }
        }
    }
}

/** The CompeGPS projection strings MapAround understands. */
object Projections {
    private val bessel = Ellipsoid(6_377_397.155, 299.1528128)

    /**
     * @param allLonLat the calibration points are all longitude/latitude (then an unknown
     *   projection is approximated as plain lat/lon over the map's small area)
     * @param itmLike projected calibration points in the Israeli grid's range
     */
    fun of(text: String, datum: String, allLonLat: Boolean = false, itmLike: Boolean = false): MapProjection {
        val parts = text.split(',').map { it.trim() }
        val code = parts.firstOrNull()?.toIntOrNull()
        val name = parts.getOrNull(1).orEmpty()
        val all = (text + " " + datum).lowercase()
        return when {
            // Israeli Transverse Mercator (Israel 1993), by name.
            Regex("israel|\\bitm\\b|new israeli").containsMatchIn(all) || (itmLike && code !in setOf(0, 1, 2, 114, 117)) -> object : MapProjection {
                override val name = "Israeli TM Grid (ITM)"
                override fun forward(lat: Double, lon: Double) = Itm.from(GeoPoint(lat, lon))?.let { doubleArrayOf(it.first, it.second) }
                    ?: doubleArrayOf(Double.NaN, Double.NaN)
                override fun inverse(x: Double, y: Double) = Itm.toPoint(x, y)
            }
            code == 0 || name.equals("UTM", true) -> {
                val zone = parts.getOrNull(2)?.toIntOrNull() ?: throw RmapException("UTM without a zone")
                val south = parts.getOrNull(3)?.uppercase()?.startsWith("S") == true
                val tm = Utm.tm(zone, !south)
                object : MapProjection {
                    override val name = "UTM $zone${if (south) "S" else "N"}"
                    override fun forward(lat: Double, lon: Double) = tm.forward(lat, lon).let { doubleArrayOf(it.first, it.second) }
                    override fun inverse(x: Double, y: Double) = tm.inverse(x, y)
                }
            }
            code == 1 -> object : MapProjection {
                override val name = "Latitude/Longitude"
                override fun forward(lat: Double, lon: Double) = doubleArrayOf(lon, lat)
                override fun inverse(x: Double, y: Double) = GeoPoint(y, x)
            }
            code == 2 -> object : MapProjection {
                // Ellipsoidal Mercator on WGS 84 (as QMapShack / PROJ +proj=merc).
                private val e = sqrt(Ellipsoid.WGS84.e2)
                private val r = Ellipsoid.WGS84.a
                override val name = "Mercator"
                override fun forward(lat: Double, lon: Double): DoubleArray {
                    val phi = toRadians(lat)
                    val s = sin(phi)
                    return doubleArrayOf(r * toRadians(lon), r * ln(tan(PI / 4 + phi / 2) * ((1 - e * s) / (1 + e * s)).pow(e / 2)))
                }
                override fun inverse(x: Double, y: Double): GeoPoint {
                    val t = exp(-y / r)
                    var phi = PI / 2 - 2 * atan(t)
                    repeat(8) {
                        val s = e * sin(phi)
                        phi = PI / 2 - 2 * atan(t * ((1 - s) / (1 + s)).pow(e / 2))
                    }
                    return GeoPoint(toDegrees(phi), toDegrees(x / r))
                }
            }
            // German Gauss-Krüger zones 4 and 3 on DHDN (Bessel), as QMapShack.
            code == 114 || code == 117 -> gaussKrueger(if (code == 114) 12.0 else 9.0, if (code == 114) 4_500_000.0 else 3_500_000.0, datum)
            allLonLat -> object : MapProjection {
                override val name = "Approximate (unknown projection \"$text\")"
                override fun forward(lat: Double, lon: Double) = doubleArrayOf(lon, lat)
                override fun inverse(x: Double, y: Double) = GeoPoint(y, x)
            }
            else -> throw RmapException("Unsupported map projection: $text")
        }
    }

    private fun gaussKrueger(lon0: Double, fe: Double, datum: String): MapProjection {
        val tm = TransverseMercator(bessel, 0.0, lon0, 1.0, fe, 0.0)
        val dhdn = datum.contains("DHDN", ignoreCase = true) || datum.contains("Potsdam", ignoreCase = true)
        // DHDN -> WGS 84: 3-parameter shift +606, +23, +413 m.
        return object : MapProjection {
            override val name = "Gauss-Krüger ${lon0.toInt()}°E"
            override fun forward(lat: Double, lon: Double): DoubleArray {
                val p = if (dhdn) {
                    val (x, y, z) = Ellipsoid.WGS84.toEcef(lat, lon)
                    bessel.fromEcef(x - 606, y - 23, z - 413)
                } else GeoPoint(lat, lon)
                return tm.forward(p.lat, p.lon).let { doubleArrayOf(it.first, it.second) }
            }
            override fun inverse(x: Double, y: Double): GeoPoint {
                val p = tm.inverse(x, y)
                if (!dhdn) return p
                val (ex, ey, ez) = bessel.toEcef(p.lat, p.lon)
                return Ellipsoid.WGS84.fromEcef(ex + 606, ey + 23, ez + 413)
            }
        }
    }
}
