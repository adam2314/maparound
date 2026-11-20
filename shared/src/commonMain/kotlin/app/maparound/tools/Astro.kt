package app.maparound.tools

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// Plain Kotlin: where the sun and moon are, when they rise and set, how much of the moon is lit.
// The usual low-precision formulas (the sun to about 0.01°, the moon to a few tenths of a degree):
// rise and set times to a minute or two, the new moon to within an hour. Unit-tested against
// known dates.

object Astro {
    /** Where a body is in the sky: degrees from north (clockwise), and above the horizon. */
    data class Sky(val azimuthDeg: Double, val altitudeDeg: Double)

    /** A moment of the day and where the body is then (its azimuth, for a rise or a set). */
    data class Event(val timeMs: Long, val azimuthDeg: Double)

    /**
     * A day at a place, from [Astro.day]'s start: dawn and dusk (civil twilight, the sun 6° down:
     * first and last light), sunrise and sunset, moonrise and moonset; null for what doesn't happen
     * that day (the polar day or night, a day the moon doesn't rise).
     */
    data class Day(
        val firstLight: Event?,
        val sunrise: Event?,
        val sunset: Event?,
        val lastLight: Event?,
        val moonrise: Event?,
        val moonset: Event?,
    )

    /** The moon now: the part of it lit (0 to 1) and its phase (0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter). */
    data class MoonLight(val fraction: Double, val phase: Double)

    fun sun(timeMs: Long, lat: Double, lon: Double): Sky = sky(sunEquatorial(days(timeMs)), timeMs, lat, lon, 0.0)

    fun moon(timeMs: Long, lat: Double, lon: Double): Sky {
        val m = moonEquatorial(days(timeMs))
        // Seen from the surface, the moon is lower by its parallax (up to about 1°).
        val parallax = asin(EARTH_RADIUS_KM / m.distanceKm)
        return sky(m, timeMs, lat, lon, parallax)
    }

    fun moonLight(timeMs: Long): MoonLight {
        val d = days(timeMs)
        val s = sunEquatorial(d)
        val m = moonEquatorial(d)
        val elongation = acos(
            (sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra)).coerceIn(-1.0, 1.0),
        )
        val incidence = atan2(SUN_DISTANCE_KM * sin(elongation), m.distanceKm - SUN_DISTANCE_KM * cos(elongation))
        // The phase by how far the moon is ahead of the sun along the ecliptic: 0 at the new moon (the conjunction).
        val phase = ((m.lambda - s.lambda) / (2 * PI)).mod(1.0)
        return MoonLight((1 + cos(incidence)) / 2, phase)
    }

    /** The next new moon after [afterMs] (to within an hour or so). */
    fun nextNewMoon(afterMs: Long): Long {
        // The phase runs from 0 up to 1 and wraps at the new moon: find the wrap, then narrow it down.
        var t = afterMs
        var p = moonLight(t).phase
        while (true) {
            val next = t + 6 * HOUR_MS
            val q = moonLight(next).phase
            if (q < p - 0.5) {
                var lo = t
                var hi = next
                while (hi - lo > 60_000) {
                    val mid = (lo + hi) / 2
                    if (moonLight(mid).phase > 0.5) lo = mid else hi = mid
                }
                return hi
            }
            t = next
            p = q
            if (t - afterMs > 40 * DAY_MS) return afterMs // can't happen: a lunation is 29.5 days
        }
    }

    /**
     * The day that starts at [startMs] (local midnight, say) at ([lat], [lon]): sunrise, sunset,
     * twilight, moonrise and moonset, each the first in the 24 hours from then.
     */
    fun day(startMs: Long, lat: Double, lon: Double): Day {
        val sunUp = { t: Long -> sun(t, lat, lon).altitudeDeg }
        val moonUp = { t: Long -> moon(t, lat, lon).altitudeDeg }
        fun sunAt(t: Long) = Event(t, sun(t, lat, lon).azimuthDeg)
        fun moonAt(t: Long) = Event(t, moon(t, lat, lon).azimuthDeg)
        return Day(
            firstLight = crossing(startMs, sunUp, CIVIL_DEG, rising = true)?.let(::sunAt),
            sunrise = crossing(startMs, sunUp, SUNRISE_DEG, rising = true)?.let(::sunAt),
            sunset = crossing(startMs, sunUp, SUNRISE_DEG, rising = false)?.let(::sunAt),
            lastLight = crossing(startMs, sunUp, CIVIL_DEG, rising = false)?.let(::sunAt),
            moonrise = crossing(startMs, moonUp, MOONRISE_DEG, rising = true)?.let(::moonAt),
            moonset = crossing(startMs, moonUp, MOONRISE_DEG, rising = false)?.let(::moonAt),
        )
    }

    /** The first time in the 24 hours from [startMs] that [altitude] crosses [threshold] going up ([rising]) or down. */
    private fun crossing(startMs: Long, altitude: (Long) -> Double, threshold: Double, rising: Boolean): Long? {
        var t = startMs
        var a = altitude(t) - threshold
        while (t < startMs + DAY_MS) {
            val next = t + STEP_MS
            val b = altitude(next) - threshold
            if ((rising && a < 0 && b >= 0) || (!rising && a >= 0 && b < 0)) {
                // Between the two samples it's near enough a straight line.
                return t + ((next - t) * (a / (a - b))).toLong()
            }
            t = next
            a = b
        }
        return null
    }

    // The formulas.

    /** Right ascension and declination, from the ecliptic longitude [lambda] (radians). */
    private class Equatorial(val ra: Double, val dec: Double, val distanceKm: Double, val lambda: Double)

    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val STEP_MS = 10 * 60_000L
    private const val J2000_MS = 946_728_000_000L // 2000-01-01 12:00 UTC
    private const val OBLIQUITY = 23.4397 * RAD
    private const val EARTH_RADIUS_KM = 6378.14
    private const val SUN_DISTANCE_KM = 149_598_000.0

    /** The sun's centre this far below the horizon: sunrise and sunset (refraction and its radius), civil twilight. */
    private const val SUNRISE_DEG = -0.833
    private const val CIVIL_DEG = -6.0

    /** The moon's centre at its rise and set (refraction and its radius; parallax is in [moon]). */
    private const val MOONRISE_DEG = -0.833 + 0.27

    private fun days(timeMs: Long) = (timeMs - J2000_MS) / DAY_MS.toDouble()

    private fun equatorial(lambda: Double, beta: Double, distanceKm: Double) = Equatorial(
        atan2(sin(lambda) * cos(OBLIQUITY) - tan(beta) * sin(OBLIQUITY), cos(lambda)),
        asin(sin(beta) * cos(OBLIQUITY) + cos(beta) * sin(OBLIQUITY) * sin(lambda)),
        distanceKm,
        lambda,
    )

    private fun sunEquatorial(d: Double): Equatorial {
        val m = RAD * (357.5291 + 0.98560028 * d)
        val center = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val perihelion = RAD * 102.9372
        return equatorial(m + center + perihelion + PI, 0.0, SUN_DISTANCE_KM)
    }

    /** The moon, with the largest of Meeus' periodic terms (chapter 47): to about 0.1°. */
    private fun moonEquatorial(d: Double): Equatorial {
        val l = 218.3164477 + 13.17639648 * d
        val dd = RAD * (297.8501921 + 12.19074912 * d) // mean elongation
        val ms = RAD * (357.5291092 + 0.98560028 * d) // the sun's mean anomaly
        val mm = RAD * (134.9633964 + 13.06499295 * d) // the moon's
        val f = RAD * (93.2720950 + 13.22935024 * d) // argument of latitude
        val longitude = l + 6.288774 * sin(mm) + 1.274027 * sin(2 * dd - mm) + 0.658314 * sin(2 * dd) +
            0.213618 * sin(2 * mm) - 0.185116 * sin(ms) - 0.114332 * sin(2 * f) + 0.058793 * sin(2 * dd - 2 * mm) +
            0.057066 * sin(2 * dd - ms - mm) + 0.053322 * sin(2 * dd + mm) + 0.045758 * sin(2 * dd - ms) -
            0.040923 * sin(ms - mm) - 0.034720 * sin(dd) - 0.030383 * sin(ms + mm)
        val latitude = 5.128122 * sin(f) + 0.280602 * sin(mm + f) + 0.277693 * sin(mm - f) + 0.173237 * sin(2 * dd - f) +
            0.055413 * sin(2 * dd - mm + f) + 0.046271 * sin(2 * dd - mm - f)
        val distance = 385_000.56 - 20_905.355 * cos(mm) - 3_699.111 * cos(2 * dd - mm) - 2_955.968 * cos(2 * dd) - 569.925 * cos(2 * mm)
        return equatorial(RAD * longitude, RAD * latitude, distance)
    }

    /** Where [e] is in the sky at ([lat], [lon]), [lowerBy] radians lower (the moon's parallax). */
    private fun sky(e: Equatorial, timeMs: Long, lat: Double, lon: Double, lowerBy: Double): Sky {
        val d = days(timeMs)
        val siderealTime = RAD * (280.16 + 360.9856235 * d) + lon * RAD
        val h = siderealTime - e.ra
        val phi = lat * RAD
        val altitude = asin(sin(phi) * sin(e.dec) + cos(phi) * cos(e.dec) * cos(h)) - lowerBy
        // Measured from south, westward; turned to from north, clockwise.
        val fromSouth = atan2(sin(h), cos(h) * sin(phi) - tan(e.dec) * cos(phi))
        return Sky((fromSouth / RAD + 180).mod(360.0), altitude / RAD)
    }
}
