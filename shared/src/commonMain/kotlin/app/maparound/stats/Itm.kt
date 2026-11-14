package app.maparound.stats

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Israel's grid (the Israeli Transverse Mercator, EPSG:2039) to latitude and longitude: the
 * Central Bureau of Statistics gives its localities' places in it. Snyder's inverse transverse
 * Mercator on GRS80; the Israel 1993 datum's offset from WGS84 (a few metres) is left out.
 */
object Itm {
    private const val A = 6378137.0
    private const val F = 1 / 298.257222101
    private const val K0 = 1.0000067
    private const val FALSE_EASTING = 219529.584
    private const val FALSE_NORTHING = 626907.390
    private const val LAT0 = 31.7343936111 * PI / 180
    private const val LON0 = 35.2045169444 * PI / 180

    private val e2 = F * (2 - F)
    private val ep2 = e2 / (1 - e2)

    /** (lat, lon) in degrees of the grid's [east] and [north], metres. */
    fun toLatLon(east: Double, north: Double): Pair<Double, Double> {
        val m = meridian(LAT0) + (north - FALSE_NORTHING) / K0
        val mu = m / (A * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val sin1 = sin(phi1)
        val cos1 = cos(phi1)
        val c1 = ep2 * cos1 * cos1
        val t1 = tan(phi1).let { it * it }
        val n1 = A / sqrt(1 - e2 * sin1 * sin1)
        val r1 = A * (1 - e2) / (1 - e2 * sin1 * sin1).pow(1.5)
        val d = (east - FALSE_EASTING) / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = LON0 + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos1
        return lat * 180 / PI to lon * 180 / PI
    }

    /** The meridian arc from the equator to [phi] (radians), metres. */
    private fun meridian(phi: Double): Double = A * (
        (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256) * phi -
            (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2.pow(3) / 1024) * sin(2 * phi) +
            (15 * e2 * e2 / 256 + 45 * e2.pow(3) / 1024) * sin(4 * phi) -
            (35 * e2.pow(3) / 3072) * sin(6 * phi)
        )

    /**
     * The Central Bureau of Statistics' locality coordinate: east and north in metres as one
     * number, six digits each (191143642000: east 191 143, north 642 000). Null when it isn't one.
     */
    fun fromCbs(code: Long): Pair<Double, Double>? {
        val east = code / 1_000_000
        val north = code % 1_000_000
        if (east !in 100_000..300_000 || north !in 350_000..820_000) return null
        return toLatLon(east.toDouble(), north.toDouble())
    }
}
