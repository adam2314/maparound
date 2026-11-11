package app.maparound.tools

import app.maparound.routing.GeoPoint
import app.maparound.shared.util.toDegrees
import app.maparound.shared.util.toRadians
import app.maparound.shared.util.fixed
import app.maparound.shared.util.padded
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

// Plain Kotlin: writing and reading coordinates in the formats people use. Unit-tested.

enum class CoordFormat(val label: String, val example: String) {
    DD("Decimal degrees", "31.776700, 35.234500"),
    DDM("Degrees & minutes", "N31° 46.602′ E35° 14.070′"),
    DMS("Degrees, minutes, seconds", "31°46′36.1″N 35°14′04.2″E"),
    UTM("UTM", "36R 711594 3517858"),
    MGRS("MGRS", "36R YA 11594 17858"),
    ITM("Israel grid (ITM)", "222304 631558"),
    PLUS("Plus Code", "8G3QQ6GM+MR"),
    MAIDENHEAD("Maidenhead locator", "KM71os"),
}

object Coords {
    /** A coordinate read from text, and the format it was written in. */
    data class Parsed(val point: GeoPoint, val format: CoordFormat)

    /** The point written in [format], or null where the format doesn't cover it (e.g. UTM at the poles). */
    fun format(p: GeoPoint, format: CoordFormat): String? = when (format) {
        CoordFormat.DD -> "${fixed(p.lat, 6)}, ${fixed(p.lon, 6)}"
        CoordFormat.DDM -> ddm(p)
        CoordFormat.DMS -> dms(p)
        CoordFormat.UTM -> Utm.from(p)?.toString()
        CoordFormat.MGRS -> Utm.from(p)?.let(Mgrs::format)
        CoordFormat.ITM -> Itm.from(p)?.let { (e, n) -> "${e.roundToLong()} ${n.roundToLong()}" }
        CoordFormat.PLUS -> PlusCode.encode(p)
        CoordFormat.MAIDENHEAD -> Maidenhead.encode(p)
    }

    /**
     * Reads a coordinate in any of the [CoordFormat]s, written the usual ways
     * ("31.7781 35.2354", "N 31 46.686 E 35 14.124", "36R YA1123418345", "Q6H6+XX" near [reference]…).
     */
    fun parse(input: String, reference: GeoPoint? = null): Parsed? {
        val text = input.trim().uppercase()
            .replace(Regex("""^(UTM|MGRS|ITM|ISRAEL(I)?( TM| GRID)?|PLUS ?CODE|OLC|LOCATOR|GRID|LAT(ITUDE)?/?LON(G|GITUDE)?)[:\s]*"""), "")
            .trim()
        if (text.isEmpty()) return null
        PlusCode.decode(text, reference)?.let { return Parsed(it, CoordFormat.PLUS) }
        Mgrs.parse(text)?.let { return Parsed(it, CoordFormat.MGRS) }
        Utm.parse(text)?.let { return Parsed(it, CoordFormat.UTM) }
        Itm.parse(text)?.let { return Parsed(it, CoordFormat.ITM) }
        Maidenhead.decode(text)?.let { return Parsed(it, CoordFormat.MAIDENHEAD) }
        return Degrees.parse(text)
    }

    private fun ddm(p: GeoPoint): String {
        fun part(v: Double, pos: Char, neg: Char): String {
            val thousandths = (abs(v) * 60_000).roundToLong() // of a minute
            val d = thousandths / 60_000
            val m = (thousandths % 60_000) / 1000.0
            return "${if (v >= 0) pos else neg}$d° ${fixed(m, 3).padStart(6, '0')}′"
        }
        return part(p.lat, 'N', 'S') + " " + part(p.lon, 'E', 'W')
    }

    private fun dms(p: GeoPoint): String {
        fun part(v: Double, pos: Char, neg: Char): String {
            val tenths = (abs(v) * 36_000).roundToLong() // of a second
            val d = tenths / 36_000
            val m = (tenths / 600) % 60
            val s = (tenths % 600) / 10.0
            return "$d°${padded(m, 2)}′${fixed(s, 1).padStart(4, '0')}″${if (v >= 0) pos else neg}"
        }
        return part(p.lat, 'N', 'S') + " " + part(p.lon, 'E', 'W')
    }
}

// ---- ellipsoids & transverse Mercator --------------------------------------------------------------

internal class Ellipsoid(val a: Double, inverseFlattening: Double) {
    val f = 1 / inverseFlattening
    val e2 = f * (2 - f)

    fun toEcef(latDeg: Double, lonDeg: Double, h: Double = 0.0): DoubleArray {
        val phi = toRadians(latDeg)
        val lam = toRadians(lonDeg)
        val n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
        return doubleArrayOf((n + h) * cos(phi) * cos(lam), (n + h) * cos(phi) * sin(lam), (n * (1 - e2) + h) * sin(phi))
    }

    fun fromEcef(x: Double, y: Double, z: Double): GeoPoint {
        val p = sqrt(x * x + y * y)
        var phi = atan2(z, p * (1 - e2))
        repeat(6) {
            val n = a / sqrt(1 - e2 * sin(phi) * sin(phi))
            val h = p / cos(phi) - n
            phi = atan2(z, p * (1 - e2 * n / (n + h)))
        }
        return GeoPoint(toDegrees(phi), toDegrees(atan2(y, x)))
    }

    companion object {
        val WGS84 = Ellipsoid(6_378_137.0, 298.257223563)
        val GRS80 = Ellipsoid(6_378_137.0, 298.257222101)
    }
}

/** Transverse Mercator by Krüger's series (sub-millimetre within a few thousand km of the central meridian). */
internal class TransverseMercator(
    ellipsoid: Ellipsoid,
    lat0: Double,
    private val lon0: Double,
    private val k0: Double,
    private val falseEasting: Double,
    private val falseNorthing: Double,
) {
    private val n = ellipsoid.f / (2 - ellipsoid.f)
    private val bigA = ellipsoid.a / (1 + n) * (1 + n * n / 4 + n.pow(4) / 64)
    private val alpha = doubleArrayOf(n / 2 - 2 * n * n / 3 + 5 * n.pow(3) / 16, 13 * n * n / 48 - 3 * n.pow(3) / 5, 61 * n.pow(3) / 240)
    private val beta = doubleArrayOf(n / 2 - 2 * n * n / 3 + 37 * n.pow(3) / 96, n * n / 48 + n.pow(3) / 15, 17 * n.pow(3) / 480)
    private val delta = doubleArrayOf(2 * n - 2 * n * n / 3 - 2 * n.pow(3), 7 * n * n / 3 - 8 * n.pow(3) / 5, 56 * n.pow(3) / 15)
    private val ecc = 2 * sqrt(n) / (1 + n)
    /** Meridian arc from the equator to lat0 (unscaled). */
    private val m0 = if (lat0 == 0.0) 0.0 else raw(lat0, lon0).second

    /** (easting, northing) before scale and false origin. */
    private fun raw(lat: Double, lon: Double): Pair<Double, Double> {
        val s = sin(toRadians(lat))
        val dl = toRadians(lon - lon0)
        val t = sinh(atanh(s) - ecc * atanh(ecc * s))
        val xi = atan2(t, cos(dl))
        val eta = atanh(sin(dl) / sqrt(1 + t * t))
        var x = eta
        var y = xi
        for (j in 1..3) {
            x += alpha[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
            y += alpha[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
        }
        return bigA * x to bigA * y
    }

    fun forward(lat: Double, lon: Double): Pair<Double, Double> {
        val (x, y) = raw(lat, lon)
        return falseEasting + k0 * x to falseNorthing + k0 * (y - m0)
    }

    fun inverse(easting: Double, northing: Double): GeoPoint {
        val xi = ((northing - falseNorthing) / k0 + m0) / bigA
        val eta = (easting - falseEasting) / (k0 * bigA)
        var xp = xi
        var ep = eta
        for (j in 1..3) {
            xp -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            ep -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xp) / cosh(ep))
        var phi = chi
        for (j in 1..3) phi += delta[j - 1] * sin(2 * j * chi)
        return GeoPoint(toDegrees(phi), lon0 + toDegrees(atan2(sinh(ep), cos(xp))))
    }
}

// ---- UTM & MGRS (WGS 84) --------------------------------------------------------------------------

internal data class Utm(val zone: Int, val band: Char, val easting: Double, val northing: Double) {
    val north: Boolean get() = band >= 'N'

    override fun toString() = "$zone$band ${easting.roundToLong()} ${northing.roundToLong()}"

    fun toPoint(): GeoPoint = tm(zone, north).inverse(easting, northing)

    companion object {
        const val BANDS = "CDEFGHJKLMNPQRSTUVWX"

        fun tm(zone: Int, north: Boolean) =
            TransverseMercator(Ellipsoid.WGS84, 0.0, zone * 6.0 - 183, 0.9996, 500_000.0, if (north) 0.0 else 10_000_000.0)

        fun bandOf(lat: Double): Char = BANDS[floor((lat + 80) / 8).toInt().coerceIn(0, BANDS.lastIndex)]

        /** UTM covers 80°S–84°N (the poles use UPS, not supported). */
        fun from(p: GeoPoint): Utm? {
            if (p.lat < -80 || p.lat > 84) return null
            val lon = if (p.lon >= 180) p.lon - 360 else p.lon
            var zone = floor((lon + 180) / 6).toInt() + 1
            // Norway and Svalbard exceptions.
            if (p.lat >= 56 && p.lat < 64 && lon >= 3 && lon < 12) zone = 32
            if (p.lat >= 72) zone = when {
                lon >= 0 && lon < 9 -> 31
                lon >= 9 && lon < 21 -> 33
                lon >= 21 && lon < 33 -> 35
                lon >= 33 && lon < 42 -> 37
                else -> zone
            }
            zone = zone.coerceIn(1, 60)
            val (e, n) = tm(zone, p.lat >= 0).forward(p.lat, lon)
            return Utm(zone, bandOf(p.lat), e, n)
        }

        private val pattern = Regex("""^(\d{1,2})\s*([C-HJ-NP-X])[\s,]*(\d{1,7}(?:\.\d+)?)\s*(?:M?E)?[\s,]+(\d{1,8}(?:\.\d+)?)\s*(?:M?N)?$""")

        fun parse(text: String): GeoPoint? {
            val m = pattern.find(text) ?: return null
            val zone = m.groupValues[1].toInt().takeIf { it in 1..60 } ?: return null
            val letter = m.groupValues[2][0]
            val e = m.groupValues[3].toDouble()
            val n = m.groupValues[4].toDouble()
            if (e !in 100_000.0..900_000.0 || n > 10_000_000) return null
            val p = Utm(zone, letter, e, n).toPoint()
            // "S" is both latitude band S (32–40°N) and the "south" hemisphere marker.
            if (letter == 'S' && (p.lat < 31.5 || p.lat > 40.5)) return Utm(zone, 'C', e, n).toPoint()
            return p
        }
    }
}

internal object Mgrs {
    private val columnSets = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    /** "36R YA 11234 18345": 1 m squares (digits are truncated, as MGRS requires). */
    fun format(u: Utm): String {
        val e = floor(u.easting).toLong()
        val n = floor(u.northing).toLong()
        val col = columnSets[(u.zone - 1) % 3][((e / 100_000).toInt() - 1).coerceIn(0, 7)]
        val row = ROWS[(((n / 100_000) % 20).toInt() + if (u.zone % 2 == 0) 5 else 0) % 20]
        return "${u.zone}${u.band} $col$row ${padded(e % 100_000, 5)} ${padded(n % 100_000, 5)}"
    }

    private val pattern = Regex("""^(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])\s*([A-HJ-NP-V])\s*(\d*)\s*(\d*)$""")

    fun parse(text: String): GeoPoint? {
        val m = pattern.find(text) ?: return null
        val zone = m.groupValues[1].toInt().takeIf { it in 1..60 } ?: return null
        val band = m.groupValues[2][0]
        val colIdx = columnSets[(zone - 1) % 3].indexOf(m.groupValues[3][0]).takeIf { it >= 0 } ?: return null
        val rowIdx = ROWS.indexOf(m.groupValues[4][0])
        var digits1 = m.groupValues[5]
        var digits2 = m.groupValues[6]
        if (digits2.isEmpty()) {
            if (digits1.length % 2 != 0) return null
            digits2 = digits1.substring(digits1.length / 2)
            digits1 = digits1.substring(0, digits1.length / 2)
        }
        if (digits1.length != digits2.length || digits1.length > 5) return null
        val unit = 10.0.pow(5 - digits1.length) // metres per last digit
        val half = if (digits1.isEmpty()) 50_000.0 else unit / 2 // centre of the square
        val e = (colIdx + 1) * 100_000.0 + (digits1.toLongOrNull() ?: 0) * unit + half
        var n = ((rowIdx - (if (zone % 2 == 0) 5 else 0) + 20) % 20) * 100_000.0 + (digits2.toLongOrNull() ?: 0) * unit + half
        // The row letters repeat every 2000 km; the latitude band says which repeat it is.
        val north = band >= 'N'
        val bandSouth = -80.0 + 8 * Utm.BANDS.indexOf(band)
        val minNorthing = Utm.tm(zone, north).forward(bandSouth, zone * 6.0 - 183).second - 5_000
        while (n < minNorthing) n += 2_000_000
        return Utm(zone, band, e, n).toPoint()
    }
}

// ---- Israel: Israeli Transverse Mercator (EPSG:2039, Israel 1993 datum) ------------------------------

internal object Itm {
    private val tm = TransverseMercator(
        Ellipsoid.GRS80, 31.734393611111, 35.204516944444, 1.0000067, 219_529.584, 626_907.390,
    )
    // "Israel 1993 to WGS 84 (2)", EPSG:1074-style 7-parameter Helmert, coordinate-frame
    // convention, accuracy 0.5 m (the older 3-parameter shift -48,55,52 is ~10 m off).
    private const val TX = 23.772
    private const val TY = 17.49
    private const val TZ = 17.859
    private const val AS = PI / 180 / 3600
    private const val RX = 0.3132 * AS
    private const val RY = 1.85274 * AS
    private const val RZ = -1.67299 * AS
    private const val S = -5.4262e-6

    /** Only around Israel, where the grid is defined. */
    fun from(p: GeoPoint): Pair<Double, Double>? {
        if (p.lat !in 29.0..33.8 || p.lon !in 33.8..36.2) return null
        val (x, y, z) = Ellipsoid.WGS84.toEcef(p.lat, p.lon)
        // Inverse Helmert: X_local = Rᵀ (X_wgs - T) / (1 + s)
        val dx = (x - TX) / (1 + S)
        val dy = (y - TY) / (1 + S)
        val dz = (z - TZ) / (1 + S)
        val local = Ellipsoid.GRS80.fromEcef(dx - RZ * dy + RY * dz, RZ * dx + dy - RX * dz, -RY * dx + RX * dy + dz)
        return tm.forward(local.lat, local.lon)
    }

    fun toPoint(easting: Double, northing: Double): GeoPoint {
        val local = tm.inverse(easting, northing)
        val (x, y, z) = Ellipsoid.GRS80.toEcef(local.lat, local.lon)
        // Coordinate-frame rotation: X_wgs = T + (1 + s) R X_local
        return Ellipsoid.WGS84.fromEcef(
            TX + (1 + S) * (x + RZ * y - RY * z),
            TY + (1 + S) * (-RZ * x + y + RX * z),
            TZ + (1 + S) * (RY * x - RX * y + z),
        )
    }

    private val pattern = Regex("""^(\d{6,7}(?:\.\d+)?)\s*[,/\s]\s*(\d{6,7}(?:\.\d+)?)$""")

    /** "222345 631598", "222345/631598" (easting first; swapped if clearly the other way round). */
    fun parse(text: String): GeoPoint? {
        val m = pattern.find(text) ?: return null
        var e = m.groupValues[1].toDouble()
        var n = m.groupValues[2].toDouble()
        val eRange = 100_000.0..300_000.0
        val nRange = 350_000.0..850_000.0
        if (e in nRange && n in eRange) e = n.also { n = e }
        if (e !in eRange || n !in nRange) return null
        return toPoint(e, n)
    }
}

// ---- Open Location Code (Plus Codes) -----------------------------------------------------------------

internal object PlusCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"

    /** 10-digit code like "8G3QQ6H6+XX" (about 14 × 14 m), as Google Maps shows. */
    fun encode(p: GeoPoint): String {
        val lat = p.lat.coerceIn(-90.0, 90.0 - 1e-9)
        val lon = ((p.lon + 180) % 360 + 360) % 360
        // Rounded to 6 decimals before flooring, like the reference library (5.32° must not become 5.3199…).
        fun cell(v: Double) = floor(round(v * 8000 * 1e6) / 1e6).toLong()
        var latVal = cell(lat + 90)
        var lonVal = cell(lon)
        val out = CharArray(10)
        for (i in 4 downTo 0) {
            out[i * 2] = ALPHABET[(latVal % 20).toInt()]
            out[i * 2 + 1] = ALPHABET[(lonVal % 20).toInt()]
            latVal /= 20
            lonVal /= 20
        }
        val s = out.concatToString()
        return s.substring(0, 8) + "+" + s.substring(8)
    }

    /** Full codes, or short ones ("Q6H6+XX", with a place name after it ignored) near [reference]. */
    fun decode(input: String, reference: GeoPoint?): GeoPoint? {
        val code = input.trim().split(Regex("""[\s,]+""")).firstOrNull()?.uppercase() ?: return null
        val plus = code.indexOf('+')
        if (plus < 0 || plus > 8 || plus % 2 != 0 || code.count { it == '+' } != 1) return null
        val digits = code.removeRange(plus, plus + 1)
        if (digits.isEmpty() || digits.any { it !in ALPHABET } || code.length - plus - 1 == 1) return null
        if (plus == 8) return decodeFull(digits)
        val ref = reference ?: return null
        val padding = 8 - plus
        val resolution = 20.0.pow(2 - padding / 2)
        val prefix = encode(GeoPoint(floor(ref.lat / resolution) * resolution, floor(ref.lon / resolution) * resolution))
            .replace("+", "").substring(0, padding)
        val c = decodeFull(prefix + digits) ?: return null
        var lat = c.lat
        var lon = c.lon
        val half = resolution / 2
        if (ref.lat + half < lat && lat - resolution >= -90) lat -= resolution
        else if (ref.lat - half > lat && lat + resolution <= 90) lat += resolution
        if (ref.lon + half < lon) lon -= resolution else if (ref.lon - half > lon) lon += resolution
        return GeoPoint(lat, (lon + 540) % 360 - 180)
    }

    private fun decodeFull(digits: String): GeoPoint? {
        if (digits.length < 2 || ALPHABET.indexOf(digits[0]) > 8 || ALPHABET.indexOf(digits[1]) > 17) return null
        var lat = -90.0
        var lon = -180.0
        var res = 20.0
        var latRes = 20.0
        var lonRes = 20.0
        val pairs = minOf(digits.length, 10)
        for (i in 0 until pairs) {
            val v = ALPHABET.indexOf(digits[i])
            if (i % 2 == 0) {
                lat += v * res; latRes = res
            } else {
                lon += v * res; lonRes = res; res /= 20
            }
        }
        if (pairs % 2 == 1) return null
        // Grid refinement digits after the 10th (4 columns × 5 rows each).
        for (i in 10 until digits.length) {
            val v = ALPHABET.indexOf(digits[i])
            latRes /= 5
            lonRes /= 4
            lat += (v / 4) * latRes
            lon += (v % 4) * lonRes
        }
        return GeoPoint(lat + latRes / 2, lon + lonRes / 2)
    }
}

// ---- Maidenhead (amateur radio grid locator) ---------------------------------------------------------

internal object Maidenhead {
    fun encode(p: GeoPoint): String {
        val lon = (p.lon + 180).coerceIn(0.0, 360 - 1e-9)
        val lat = (p.lat + 90).coerceIn(0.0, 180 - 1e-9)
        val sb = StringBuilder()
        sb.append('A' + (lon / 20).toInt()).append('A' + (lat / 10).toInt())
        sb.append('0' + ((lon % 20) / 2).toInt()).append('0' + (lat % 10).toInt())
        sb.append('a' + ((lon % 2) * 12).toInt()).append('a' + ((lat % 1) * 24).toInt())
        return sb.toString()
    }

    private val pattern = Regex("""^([A-R]{2})(\d{2})(?:([A-X]{2})(\d{2})?)?$""")

    fun decode(text: String): GeoPoint? {
        val m = pattern.find(text) ?: return null
        var lon = (m.groupValues[1][0] - 'A') * 20.0 + (m.groupValues[2][0] - '0') * 2.0
        var lat = (m.groupValues[1][1] - 'A') * 10.0 + (m.groupValues[2][1] - '0') * 1.0
        var w = 2.0
        var h = 1.0
        if (m.groupValues[3].isNotEmpty()) {
            w /= 24; h /= 24
            lon += (m.groupValues[3][0] - 'A') * w
            lat += (m.groupValues[3][1] - 'A') * h
            if (m.groupValues[4].isNotEmpty()) {
                w /= 10; h /= 10
                lon += (m.groupValues[4][0] - '0') * w
                lat += (m.groupValues[4][1] - '0') * h
            }
        }
        return GeoPoint(lat + h / 2 - 90, lon + w / 2 - 180)
    }
}

// ---- degrees in their many spellings -----------------------------------------------------------------

internal object Degrees {
    private class Part(var hemisphere: Char? = null, val numbers: MutableList<Double> = ArrayList(), var negative: Boolean = false)

    fun parse(upper: String): Coords.Parsed? {
        val s = upper
            .replace(Regex("""\b(LAT(ITUDE)?|LON(G|GITUDE)?|LNG)\b[:=]?"""), " ")
            .replace(Regex("""[°º˚]"""), "° ")
            .replace(Regex("""''|[″”“"]"""), "\" ")
            .replace(Regex("""[′’‘'´`]"""), "' ")
        // Anything but numbers, hemisphere letters, unit marks and separators: not a coordinate.
        if (Regex("""[^0-9NSEW.\-+°'"\s,;:/]""").containsMatchIn(s)) return null
        val tokens = Regex("""([NSEW])|([+-]?\d+(?:\.\d+)?)|(°)""").findAll(s).toList()
        if (tokens.isEmpty()) return null
        val prefixStyle = tokens.first().groupValues[1].isNotEmpty()
        val parts = ArrayList<Part>()
        var current = Part()
        var degreeMarks = 0
        for (t in tokens) {
            val letter = t.groupValues[1]
            val number = t.groupValues[2]
            when {
                letter.isNotEmpty() -> if (prefixStyle) {
                    if (current.hemisphere != null || current.numbers.isNotEmpty()) parts += current
                    current = Part(hemisphere = letter[0])
                } else {
                    current.hemisphere = letter[0]
                    parts += current
                    current = Part()
                }
                number.isNotEmpty() -> {
                    if (current.numbers.isEmpty() && number.startsWith("-")) current.negative = true
                    current.numbers += abs(number.toDouble())
                }
                else -> degreeMarks++ // a ° mark: the number before it starts a new part (handled below)
            }
        }
        if (current.hemisphere != null || current.numbers.isNotEmpty()) parts += current
        // No hemisphere letters: split the numbers in two halves (2, 4 or 6 numbers).
        val pair: List<Part> = if (parts.size == 1 && parts[0].hemisphere == null) {
            val nums = parts[0].numbers
            if (nums.size !in setOf(2, 4, 6)) return null
            val signs = Regex("""[+-]?\d+(?:\.\d+)?""").findAll(s).map { it.value.startsWith("-") }.toList()
            val k = nums.size / 2
            listOf(
                Part(numbers = nums.subList(0, k).toMutableList(), negative = signs[0]),
                Part(numbers = nums.subList(k, nums.size).toMutableList(), negative = signs[k]),
            )
        } else {
            parts
        }
        if (pair.size != 2) return null
        val values = pair.map { valueOf(it) ?: return null }
        val (a, b) = pair
        val latFirst = when {
            a.hemisphere in setOf('N', 'S') && b.hemisphere in setOf('E', 'W', null) -> true
            a.hemisphere in setOf('E', 'W') && b.hemisphere in setOf('N', 'S', null) -> false
            a.hemisphere == null && b.hemisphere in setOf('E', 'W') -> true
            a.hemisphere == null && b.hemisphere in setOf('N', 'S') -> false
            a.hemisphere == null && b.hemisphere == null -> true
            else -> return null
        }
        val lat = if (latFirst) values[0] else values[1]
        val lon = if (latFirst) values[1] else values[0]
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val format = when (pair.maxOf { it.numbers.size }) {
            1 -> CoordFormat.DD
            2 -> CoordFormat.DDM
            else -> CoordFormat.DMS
        }
        return Coords.Parsed(GeoPoint(lat, lon), format)
    }

    private fun valueOf(p: Part): Double? {
        val n = p.numbers
        if (n.isEmpty() || n.size > 3) return null
        if (n.size >= 2 && (n[1] >= 60 || n[0] != floor(n[0]))) return null
        if (n.size == 3 && (n[2] >= 60 || n[1] != floor(n[1]))) return null
        val v = n[0] + n.getOrElse(1) { 0.0 } / 60 + n.getOrElse(2) { 0.0 } / 3600
        val negative = p.negative || p.hemisphere == 'S' || p.hemisphere == 'W'
        return if (negative) -v else v
    }
}
