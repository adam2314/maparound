package app.maparound.stats

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The web app's timezones.bin (tools/timezones/make_timezones.py): known places, and their clocks
 * (here by Java's time-zone rules; the web app uses the browser's).
 */
class TimeZonesFileTest {
    private fun zoneOffsetSeconds(zone: String, epochMillis: Long): Int? =
        java.util.TimeZone.getTimeZone(zone).takeIf { it.id == zone }?.getOffset(epochMillis)?.div(1000)

    private val zones by lazy {
        TimeZones.decode(File("../webApp/src/wasmJsMain/resources/timezones.bin").readBytes())
    }

    @Test fun placesAreInTheirZones() {
        assertTrue(zones.size > 400)
        val places = listOf(
            Triple(31.78, 35.22, "Asia/Jerusalem"),
            Triple(51.5, -0.12, "Europe/London"),
            Triple(40.71, -74.0, "America/New_York"),
            Triple(-33.87, 151.21, "Australia/Sydney"),
            Triple(28.61, 77.21, "Asia/Kolkata"),
            // Lesotho, inside South Africa: a hole in Johannesburg's zone.
            Triple(-29.31, 27.48, "Africa/Maseru"),
            Triple(-26.2, 28.05, "Africa/Johannesburg"),
            // The open sea.
            Triple(30.0, -40.0, "Etc/GMT+3"),
        )
        for ((lat, lon, name) in places) assertEquals(name, TimeZones.at(zones, lat, lon)?.name, "$lat, $lon")
    }

    @Test fun offsetsAndChangesByThePlatformsRules() {
        // 1 Jan 2026, 12:00 UTC: winter in Israel (UTC+2), India +5:30 all year.
        val jan = 1_767_268_800_000L
        assertEquals(7200, zoneOffsetSeconds("Asia/Jerusalem", jan))
        assertEquals(19800, zoneOffsetSeconds("Asia/Kolkata", jan))
        assertEquals(null, zoneOffsetSeconds("Nowhere/Atlantis", jan))
        // Israel's next change: forward an hour, on the Friday before the last Sunday of March, 02:00 local.
        val c = assertNotNull(TimeZones.nextChange(jan) { zoneOffsetSeconds("Asia/Jerusalem", it) })
        assertEquals(10800, c.offsetSeconds)
        assertEquals(1_774_569_600_000L, c.atMillis) // 27 Mar 2026, 00:00 UTC
        assertEquals(null, TimeZones.nextChange(jan) { zoneOffsetSeconds("Asia/Kolkata", it) })
        // Every zone in the file is one the time-zone rules know.
        val unknown = zones.map { it.name }.filter { zoneOffsetSeconds(it, jan) == null }
        assertTrue(unknown.isEmpty(), unknown.toString())
    }
}
