package app.maparound.tools

import app.maparound.testing.assertTrue
import kotlinx.datetime.toInstant
import kotlin.math.abs
import kotlin.test.Test

/** Against dates known from almanacs: equinox and solstice sunrises, eclipses' new and full moons. */
class AstroTest {
    private fun utc(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        kotlinx.datetime.LocalDateTime(y, mo, d, h, mi).toInstant(kotlinx.datetime.TimeZone.UTC).toEpochMilliseconds()

    private fun near(expected: Long, actual: Long?, minutes: Int, what: String) {
        assertTrue("$what: ${actual?.let { (it - expected) / 60_000.0 }} min off", actual != null && abs(actual - expected) <= minutes * 60_000L)
    }

    @Test
    fun theSunAtTheEquinoxOnTheEquator() {
        val day = Astro.day(utc(2026, 3, 20), 0.0, 0.0)
        near(utc(2026, 3, 20, 6, 4), day.sunrise?.timeMs, 3, "sunrise")
        near(utc(2026, 3, 20, 18, 11), day.sunset?.timeMs, 3, "sunset")
        assertTrue("${day.sunrise?.azimuthDeg}", abs(day.sunrise!!.azimuthDeg - 90) < 1.5)
        assertTrue("${day.sunset?.azimuthDeg}", abs(day.sunset!!.azimuthDeg - 270) < 1.5)
        // Twilight: about 20 minutes either side there.
        assertTrue(day.firstLight!!.timeMs < day.sunrise!!.timeMs - 15 * 60_000)
        assertTrue(day.lastLight!!.timeMs > day.sunset!!.timeMs + 15 * 60_000)
        // Near the zenith at noon.
        assertTrue(Astro.sun(utc(2026, 3, 20, 12, 7), 0.0, 0.0).altitudeDeg > 88)
    }

    @Test
    fun midsummerInJerusalem() {
        val day = Astro.day(utc(2026, 6, 20, 21), 31.7683, 35.2137) // local midnight (UTC+3)
        near(utc(2026, 6, 21, 2, 34), day.sunrise?.timeMs, 4, "sunrise")
        near(utc(2026, 6, 21, 16, 47), day.sunset?.timeMs, 4, "sunset")
    }

    @Test
    fun theNorthPoleHasNoSunriseInSummer() {
        val day = Astro.day(utc(2026, 6, 21), 89.9, 0.0)
        assertTrue(day.sunrise == null && day.sunset == null)
        assertTrue(Astro.sun(utc(2026, 6, 21), 89.9, 0.0).altitudeDeg > 20)
    }

    @Test
    fun newMoonsOfTheEclipses() {
        // The annular eclipse of 17 Feb 2026, the total one of 12 Aug 2026.
        near(utc(2026, 2, 17, 12, 1), Astro.nextNewMoon(utc(2026, 2, 1)), 90, "February's new moon")
        near(utc(2026, 8, 12, 17, 37), Astro.nextNewMoon(utc(2026, 7, 20)), 90, "August's new moon")
        // Just after one, the next is a lunation later.
        near(utc(2026, 9, 11, 3, 27), Astro.nextNewMoon(utc(2026, 8, 13)), 180, "September's new moon")
    }

    @Test
    fun theMoonFullAtTheLunarEclipse() {
        // The total lunar eclipse of 3 Mar 2026.
        val full = Astro.moonLight(utc(2026, 3, 3, 11, 33))
        assertTrue("${full.fraction}", full.fraction > 0.99)
        assertTrue("${full.phase}", abs(full.phase - 0.5) < 0.02)
        val new = Astro.moonLight(utc(2026, 2, 17, 12, 1))
        assertTrue("${new.fraction}", new.fraction < 0.01)
        // A week after the new moon: about half lit, waxing.
        val quarter = Astro.moonLight(utc(2026, 2, 24, 12))
        assertTrue("${quarter.fraction}", quarter.fraction in 0.4..0.65)
        assertTrue("${quarter.phase}", quarter.phase in 0.2..0.3)
    }

    @Test
    fun aFullMoonRisesAsTheSunSets() {
        val day = Astro.day(utc(2026, 3, 2, 22), 31.7683, 35.2137)
        val rise = day.moonrise!!.timeMs
        assertTrue("${(rise - day.sunset!!.timeMs) / 60_000}", abs(rise - day.sunset!!.timeMs) < 60 * 60_000)
        // Opposite the sun: rising in the east.
        assertTrue("${day.moonrise!!.azimuthDeg}", day.moonrise!!.azimuthDeg in 60.0..120.0)
    }
}
