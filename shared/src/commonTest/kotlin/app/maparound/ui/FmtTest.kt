package app.maparound.ui

import app.maparound.shared.util.localDecimalSeparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FmtTest {
    @Test
    fun formats() {
        val d = localDecimalSeparator()
        assertEquals("31.77670, 35.23450", Fmt.latLon(31.7767, 35.2345))
        assertEquals("31°46′36.1″N  35°14′04.2″E", Fmt.dms(31.7767, 35.2345))
        assertEquals("33°51′24.5″S  151°12′55.1″E", Fmt.dms(-33.8568, 151.2153))
        assertEquals("950 m", Fmt.distance(950f))
        assertEquals("12${d}4 km", Fmt.distance(12_400f))
        assertEquals("250 km", Fmt.distance(250_000f))
        assertEquals("3${d}1", Fmt.oneDecimal(3.14))
        assertEquals("13 min", Fmt.duration(754))
        assertEquals("4 h 10 min", Fmt.duration(15_000))
        assertEquals("1:05:09", Fmt.clock(3909))
        assertEquals("NNE", Fmt.cardinal(20f))
        assertEquals("N", Fmt.cardinal(355f))
        assertEquals("830 B", Fmt.fileSize(830))
        assertEquals("4${d}2 kB", Fmt.fileSize(4_200))
        assertEquals("530 MB", Fmt.fileSize(530_000_000))
        assertEquals("1${d}2 GB", Fmt.fileSize(1_234_000_000))
        assertEquals("1${d}0 MB", Fmt.fileSize(999_999)) // rounds up into the next unit
        val now = 1_790_000_000_000 // Sep 2026
        assertEquals("Just now", Fmt.relativeTime(now - 20_000, now))
        assertEquals("5 min ago", Fmt.relativeTime(now - 5 * 60_000, now))
        assertTrue(Fmt.date(1_700_000_000_000, now).endsWith(", 2023")) // another year shows it
    }
}
