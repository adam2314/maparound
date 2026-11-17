package app.maparound.tools

import app.maparound.routing.GeoPoint
import app.maparound.shared.util.padded
import app.maparound.shared.util.toDegrees
import app.maparound.shared.util.toRadians
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Plain Kotlin: more coordinate formats for the converter: global codes (Geohash, GEOREF), GIS
// coordinates (Web Mercator, ECEF) and national grids (Britain, Ireland, Switzerland, the
// Netherlands, France). Unit-tested.

/** Two numbers ("652381 6862047", "2'600'000 / 1'200'000", "-8.5e5, 4e6"). */
internal fun numberPair(text: String): Pair<Double, Double>? {
    val m = Regex("""^(-?\d+(?:\.\d+)?)\s*[,;/\s]\s*(-?\d+(?:\.\d+)?)$""").find(cleanNumbers(text)) ?: return null
    return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
}

/** Three numbers (ECEF's X Y Z). */
internal fun numberTriple(text: String): Triple<Double, Double, Double>? {
    val m = Regex("""^(-?\d+(?:\.\d+)?)\s*[,;/\s]\s*(-?\d+(?:\.\d+)?)\s*[,;/\s]\s*(-?\d+(?:\.\d+)?)$""").find(cleanNumbers(text)) ?: return null
    return Triple(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble())
}

/** Thousands written with apostrophes or thin spaces ("2'600'000"), metre marks ("m", "E", "N"). */
private fun cleanNumbers(text: String): String =
    text.trim().replace(Regex("""(?<=\d)['’  ](?=\d)"""), "")
        .replace(Regex("""\s*(M|E|N|X|Y|Z)\b""", RegexOption.IGNORE_CASE), " ").trim()

// ---- Geohash -----------------------------------------------------------------------------------------

internal object Geohash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** 10 characters: a cell about 1 m across. */
    fun encode(p: GeoPoint, length: Int = 10): String {
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        val out = StringBuilder()
        var bits = 0
        var ch = 0
        var lonBit = true
        while (out.length < length) {
            if (lonBit) {
                val mid = (lonLo + lonHi) / 2
                if (p.lon >= mid) { ch = ch * 2 + 1; lonLo = mid } else { ch *= 2; lonHi = mid }
            } else {
                val mid = (latLo + latHi) / 2
                if (p.lat >= mid) { ch = ch * 2 + 1; latLo = mid } else { ch *= 2; latHi = mid }
            }
            lonBit = !lonBit
            if (++bits == 5) {
                out.append(BASE32[ch])
                bits = 0
                ch = 0
            }
        }
        return out.toString()
    }

    /** The middle of the cell; [strict]: only text that can't be much else (letters and digits, 6 or more). */
    fun decode(text: String, strict: Boolean = false): GeoPoint? {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 12 || t.any { it !in BASE32 }) return null
        if (strict && (t.length < 6 || t.none { it.isDigit() } || t.none { it.isLetter() })) return null
        var latLo = -90.0
        var latHi = 90.0
        var lonLo = -180.0
        var lonHi = 180.0
        var lonBit = true
        for (c in t) {
            val v = BASE32.indexOf(c)
            for (b in 4 downTo 0) {
                val on = (v shr b) and 1 == 1
                if (lonBit) {
                    val mid = (lonLo + lonHi) / 2
                    if (on) lonLo = mid else lonHi = mid
                } else {
                    val mid = (latLo + latHi) / 2
                    if (on) latLo = mid else latHi = mid
                }
                lonBit = !lonBit
            }
        }
        return GeoPoint((latLo + latHi) / 2, (lonLo + lonHi) / 2)
    }
}

// ---- GEOREF (World Geographic Reference System, aviation) ----------------------------------------------

internal object Georef {
    /** 24 letters (no I, O): 15° of longitude; the first 12, 15° of latitude; the first 15, degrees. */
    private const val LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** "GJPJ 3425 1717": 15° squares, degrees, then minutes to a hundredth (about 18 m). */
    fun encode(p: GeoPoint): String {
        val lon = (p.lon + 180).coerceIn(0.0, 359.999999)
        val lat = (p.lat + 90).coerceIn(0.0, 179.999999)
        val lonBand = (lon / 15).toInt()
        val latBand = (lat / 15).toInt()
        val lonDeg = floor(lon).toInt() - lonBand * 15
        val latDeg = floor(lat).toInt() - latBand * 15
        val lonMin = floor((lon - floor(lon)) * 6000).toLong().coerceAtMost(5999)
        val latMin = floor((lat - floor(lat)) * 6000).toLong().coerceAtMost(5999)
        return "${LETTERS[lonBand]}${LETTERS[latBand]}${LETTERS[lonDeg]}${LETTERS[latDeg]} ${padded(lonMin, 4)} ${padded(latMin, 4)}"
    }

    private val pattern = Regex("""^([A-HJ-NP-Z])([A-HJ-M])([A-HJ-NP-Q])([A-HJ-NP-Q])\s*(\d*)\s*(\d*)$""")

    /** [strict]: only with minutes (bare letters could be a word). */
    fun decode(text: String, strict: Boolean = false): GeoPoint? {
        val m = pattern.find(text.trim().uppercase()) ?: return null
        val g = m.groupValues
        if (strict && g[5].isEmpty()) return null
        var a = g[5]
        var b = g[6]
        if (b.isEmpty()) {
            if (a.length % 2 != 0) return null
            b = a.substring(a.length / 2)
            a = a.substring(0, a.length / 2)
        }
        if (a.length != b.length || (a.isNotEmpty() && a.length !in 2..4)) return null
        fun minutes(d: String): Double =
            if (d.isEmpty()) 30.0 else (d.toLong() + 0.5) / 10.0.pow(d.length - 2)
        val lon = LETTERS.indexOf(g[1][0]) * 15 + LETTERS.indexOf(g[3][0]) + minutes(a) / 60 - 180
        val lat = LETTERS.indexOf(g[2][0]) * 15 + LETTERS.indexOf(g[4][0]) + minutes(b) / 60 - 90
        if (minutes(a) >= 60 || minutes(b) >= 60) return null
        return GeoPoint(lat, lon)
    }
}

// ---- Web Mercator (EPSG:3857) and ECEF ------------------------------------------------------------------

internal object WebMercator {
    private const val R = 6_378_137.0
    private const val MAX_LAT = 85.05112878

    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (abs(p.lat) > MAX_LAT) return null
        return R * toRadians(p.lon) to R * ln(tan(PI / 4 + toRadians(p.lat) / 2))
    }

    fun toPoint(x: Double, y: Double): GeoPoint? {
        val limit = PI * R
        if (abs(x) > limit * 1.0001 || abs(y) > limit * 1.0001) return null
        return GeoPoint(toDegrees(2 * atan(exp(y / R)) - PI / 2), toDegrees(x / R))
    }
}

internal object Ecef {
    /** X Y Z in metres, on the WGS 84 ellipsoid (height 0). */
    fun from(p: GeoPoint): Triple<Double, Double, Double> {
        val v = Ellipsoid.WGS84.toEcef(p.lat, p.lon)
        return Triple(v[0], v[1], v[2])
    }

    /** Three numbers about an Earth radius from the centre (within 100 km of the ellipsoid). */
    fun toPoint(x: Double, y: Double, z: Double): GeoPoint? {
        val r = sqrt(x * x + y * y + z * z)
        if (r !in 6_256_000.0..6_478_000.0) return null
        return Ellipsoid.WGS84.fromEcef(x, y, z)
    }
}

// ---- Britain: Ordnance Survey National Grid (OSGB36, Airy 1830) -------------------------------------------

internal object Osgb {
    private val airy = Ellipsoid(6_377_563.396, 299.3249646)
    private val tm = TransverseMercator(airy, 49.0, -2.0, 0.9996012717, 400_000.0, -100_000.0)

    // ETRS89/WGS 84 → OSGB36, the Ordnance Survey's Helmert transformation (about 5 m).
    private const val AS = PI / 180 / 3600
    private const val TX = -446.448
    private const val TY = 125.157
    private const val TZ = -542.060
    private const val S = 20.4894e-6
    private const val RX = -0.1502 * AS
    private const val RY = -0.2470 * AS
    private const val RZ = -0.8421 * AS

    private fun helmert(x: Double, y: Double, z: Double, sign: Double): Triple<Double, Double, Double> {
        val s = sign * S
        val rx = sign * RX
        val ry = sign * RY
        val rz = sign * RZ
        return Triple(
            sign * TX + (1 + s) * x - rz * y + ry * z,
            sign * TY + rz * x + (1 + s) * y - rx * z,
            sign * TZ - ry * x + rx * y + (1 + s) * z,
        )
    }

    /** Easting and northing; only around Great Britain, where the grid is defined. */
    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (p.lat !in 49.8..61.0 || p.lon !in -9.0..2.0) return null
        val (e, n) = project(p)
        return if (e in 0.0..700_000.0 && n in 0.0..1_300_000.0) e to n else null
    }

    /** Anywhere (a grid drawn over the map reaches past the coast). */
    fun project(p: GeoPoint): Pair<Double, Double> {
        val v = Ellipsoid.WGS84.toEcef(p.lat, p.lon)
        val (x, y, z) = helmert(v[0], v[1], v[2], 1.0)
        val local = airy.fromEcef(x, y, z)
        return tm.forward(local.lat, local.lon)
    }

    fun toPoint(e: Double, n: Double): GeoPoint? {
        if (e !in 0.0..700_000.0 || n !in 0.0..1_300_000.0) return null
        return unproject(e, n)
    }

    fun unproject(e: Double, n: Double): GeoPoint {
        val local = tm.inverse(e, n)
        val v = airy.toEcef(local.lat, local.lon)
        val (x, y, z) = helmert(v[0], v[1], v[2], -1.0)
        return Ellipsoid.WGS84.fromEcef(x, y, z)
    }

    /** "TQ 30080 80820": the 100 km square's letters, then metres in it. */
    fun format(p: GeoPoint): String? {
        val (e, n) = from(p) ?: return null
        val e100 = floor(e / 100_000).toInt()
        val n100 = floor(n / 100_000).toInt()
        var l1 = (19 - n100) - (19 - n100) % 5 + (e100 + 10) / 5
        var l2 = (19 - n100) * 5 % 25 + e100 % 5
        if (l1 > 7) l1++
        if (l2 > 7) l2++
        val em = floor(e).toLong() % 100_000
        val nm = floor(n).toLong() % 100_000
        return "${'A' + l1}${'A' + l2} ${padded(em, 5)} ${padded(nm, 5)}"
    }

    private val letters = Regex("""^([A-HJ-Z])([A-HJ-Z])\s*(\d*)\s*(\d*)$""")

    /**
     * "TQ 30080 80820", "TQ3080", "NN166712"; [numbers]: also "530080, 180820"; [strict]: only with
     * digits (two letters alone could be anything).
     */
    fun parse(text: String, numbers: Boolean = false, strict: Boolean = false): GeoPoint? {
        val t = text.trim().uppercase()
        letters.find(t)?.let { m ->
            if (strict && m.groupValues[3].length < 2) return null
            var l1 = m.groupValues[1][0] - 'A'
            var l2 = m.groupValues[2][0] - 'A'
            if (l1 > 7) l1--
            if (l2 > 7) l2--
            val e100 = ((l1 - 2) % 5) * 5 + (l2 % 5)
            val n100 = (19 - (l1 / 5) * 5) - l2 / 5
            if (e100 !in 0..6 || n100 !in 0..12) return null
            var a = m.groupValues[3]
            var b = m.groupValues[4]
            if (b.isEmpty()) {
                if (a.length % 2 != 0) return null
                b = a.substring(a.length / 2)
                a = a.substring(0, a.length / 2)
            }
            if (a.length != b.length || a.length > 5) return null
            val unit = 10.0.pow(5 - a.length)
            val half = if (a.isEmpty()) 50_000.0 else unit / 2
            return toPoint(e100 * 100_000 + (a.toLongOrNull() ?: 0) * unit + half, n100 * 100_000 + (b.toLongOrNull() ?: 0) * unit + half)
        }
        if (!numbers) return null
        val (e, n) = numberPair(t) ?: return null
        return toPoint(e, n)
    }
}

// ---- Ireland: Irish Transverse Mercator (EPSG:2157, on ETRS89 ≈ WGS 84) -------------------------------------

internal object IrishTm {
    private val tm = TransverseMercator(Ellipsoid.GRS80, 53.5, -8.0, 0.99982, 600_000.0, 750_000.0)

    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (p.lat !in 51.2..55.6 || p.lon !in -11.0..-5.2) return null
        return tm.forward(p.lat, p.lon)
    }

    fun project(p: GeoPoint): Pair<Double, Double> = tm.forward(p.lat, p.lon)

    fun toPoint(e: Double, n: Double): GeoPoint = tm.inverse(e, n)

    fun parse(text: String): GeoPoint? {
        val (e, n) = numberPair(text) ?: return null
        if (e !in 400_000.0..800_000.0 || n !in 500_000.0..1_000_000.0) return null
        return tm.inverse(e, n)
    }
}

// ---- Switzerland: LV95 (CH1903+), swisstopo's approximate formulas (about 1 m) ------------------------------

internal object Lv95 {
    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (p.lat !in 45.6..48.0 || p.lon !in 5.7..10.7) return null
        return project(p)
    }

    /** The formulas, also a little outside Switzerland (a grid drawn over the map). */
    fun project(p: GeoPoint): Pair<Double, Double> {
        val phi = (p.lat * 3600 - 169_028.66) / 10_000
        val lam = (p.lon * 3600 - 26_782.5) / 10_000
        val e = 2_600_072.37 + 211_455.93 * lam - 10_938.51 * lam * phi - 0.36 * lam * phi * phi - 44.54 * lam * lam * lam
        val n = 1_200_147.07 + 308_807.95 * phi + 3_745.25 * lam * lam + 76.63 * phi * phi -
            194.56 * lam * lam * phi + 119.79 * phi * phi * phi
        return e to n
    }

    fun toPoint(e: Double, n: Double): GeoPoint {
        val y = (e - 2_600_000) / 1_000_000
        val x = (n - 1_200_000) / 1_000_000
        val lam = 2.6779094 + 4.728982 * y + 0.791484 * y * x + 0.1306 * y * x * x - 0.0436 * y * y * y
        val phi = 16.9023892 + 3.238272 * x - 0.270978 * y * y - 0.002528 * x * x - 0.0447 * y * y * x - 0.0140 * x * x * x
        return GeoPoint(phi * 100 / 36, lam * 100 / 36)
    }

    /** "2600072 1200147" (LV95); [old]: also LV03's "600072 200147". */
    fun parse(text: String, old: Boolean = false): GeoPoint? {
        var (e, n) = numberPair(text) ?: return null
        if (old && e in 450_000.0..850_000.0 && n in 50_000.0..320_000.0) {
            e += 2_000_000
            n += 1_000_000
        }
        if (e !in 2_450_000.0..2_850_000.0 || n !in 1_050_000.0..1_320_000.0) return null
        return toPoint(e, n)
    }
}

// ---- The Netherlands: Rijksdriehoek (RD New, EPSG:28992), the approximate formulas (about 1 m) ---------------

internal object Rd {
    private const val LAT0 = 52.15517440
    private const val LON0 = 5.38720621

    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (p.lat !in 50.6..53.8 || p.lon !in 3.0..7.4) return null
        return project(p)
    }

    /** The formulas, also a little outside the Netherlands (a grid drawn over the map). */
    fun project(p: GeoPoint): Pair<Double, Double> {
        val df = 0.36 * (p.lat - LAT0)
        val dl = 0.36 * (p.lon - LON0)
        val x = 155_000 + 190_094.945 * dl - 11_832.228 * df * dl - 114.221 * df * df * dl - 32.391 * dl.pow(3) -
            0.705 * df - 2.340 * df.pow(3) * dl - 0.608 * df * dl.pow(3) - 0.008 * dl * dl + 0.148 * df * df * dl.pow(3)
        val y = 463_000 + 309_056.544 * df + 3_638.893 * dl * dl + 73.077 * df * df - 157.984 * df * dl * dl +
            59.788 * df.pow(3) + 0.433 * dl - 6.439 * df * df * dl * dl - 0.032 * df * dl + 0.092 * dl.pow(4) - 0.054 * df * dl.pow(4)
        return x to y
    }

    fun toPoint(x: Double, y: Double): GeoPoint {
        val dx = (x - 155_000) * 1e-5
        val dy = (y - 463_000) * 1e-5
        val lat = LAT0 + (3_235.65389 * dy - 32.58297 * dx * dx - 0.24750 * dy * dy - 0.84978 * dx * dx * dy -
            0.06550 * dy.pow(3) - 0.01709 * dx * dx * dy * dy - 0.00738 * dx + 0.00530 * dx.pow(4) -
            0.00039 * dx * dx * dy.pow(3) + 0.00033 * dx.pow(4) * dy - 0.00012 * dx * dy) / 3600
        val lon = LON0 + (5_260.52916 * dx + 105.94684 * dx * dy + 2.45656 * dx * dy * dy - 0.81885 * dx.pow(3) +
            0.05594 * dx * dy.pow(3) - 0.05607 * dx.pow(3) * dy + 0.01199 * dy - 0.00256 * dx.pow(3) * dy * dy +
            0.00128 * dx * dy.pow(4) + 0.00022 * dy * dy - 0.00022 * dx * dx + 0.00026 * dx.pow(5)) / 3600
        return GeoPoint(lat, lon)
    }

    fun parse(text: String): GeoPoint? {
        var (x, y) = numberPair(text) ?: return null
        if (x in 280_000.0..640_000.0 && y in -10_000.0..300_000.0) x = y.also { y = x }
        if (x !in -10_000.0..300_000.0 || y !in 280_000.0..640_000.0) return null
        return toPoint(x, y)
    }
}

// ---- France: Lambert 93 (EPSG:2154, on RGF93 ≈ WGS 84) ----------------------------------------------------

internal object Lambert93 {
    private val e = sqrt(Ellipsoid.GRS80.e2)
    private const val A = 6_378_137.0
    private const val E0 = 700_000.0
    private const val N0 = 6_600_000.0
    private val lon0 = toRadians(3.0)

    private fun t(phi: Double) = tan(PI / 4 - phi / 2) / ((1 - e * sin(phi)) / (1 + e * sin(phi))).pow(e / 2)
    private fun m(phi: Double) = cos(phi) / sqrt(1 - e * e * sin(phi) * sin(phi))

    private val phi1 = toRadians(49.0)
    private val phi2 = toRadians(44.0)
    private val n = (ln(m(phi1)) - ln(m(phi2))) / (ln(t(phi1)) - ln(t(phi2)))
    private val f = m(phi1) / (n * t(phi1).pow(n))
    private val r0 = A * f * t(toRadians(46.5)).pow(n)

    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (p.lat !in 41.0..51.5 || p.lon !in -5.5..10.0) return null
        return project(p)
    }

    fun project(p: GeoPoint): Pair<Double, Double> {
        val r = A * f * t(toRadians(p.lat)).pow(n)
        val theta = n * (toRadians(p.lon) - lon0)
        return E0 + r * sin(theta) to N0 + r0 - r * cos(theta)
    }

    fun toPoint(x: Double, y: Double): GeoPoint {
        val dx = x - E0
        val dy = r0 - (y - N0)
        val r = sign(n) * sqrt(dx * dx + dy * dy)
        val tt = (r / (A * f)).pow(1 / n)
        val theta = atan2(dx, dy)
        var phi = PI / 2 - 2 * atan(tt)
        repeat(8) { phi = PI / 2 - 2 * atan(tt * ((1 - e * sin(phi)) / (1 + e * sin(phi))).pow(e / 2)) }
        return GeoPoint(toDegrees(phi), toDegrees(theta / n + lon0))
    }

    fun parse(text: String): GeoPoint? {
        var (x, y) = numberPair(text) ?: return null
        if (y in 50_000.0..1_300_000.0 && x in 6_000_000.0..7_200_000.0) x = y.also { y = x }
        if (x !in 50_000.0..1_300_000.0 || y !in 6_000_000.0..7_200_000.0) return null
        return toPoint(x, y)
    }
}

/** Metres as a whole number. */
internal fun metres(v: Double): String = v.roundToLong().toString()
