package app.maparound.stats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeZonesTest {
    /** A zone for [pack]: its polygons (outer ring, holes), rings of (lat, lon) degrees. */
    private class Z(val name: String, val polygons: List<List<List<Pair<Double, Double>>>>)

    /** timezones.bin as tools/timezones/make_timezones.py writes it. */
    private fun pack(vararg zs: Z): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(v: Long) {
            var n = v
            while (true) {
                val b = (n and 0x7F).toInt()
                n = n ushr 7
                if (n != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); return }
            }
        }
        fun zigzag(v: Int) = varint(((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL)
        "MATZ".forEach { out += it.code.toByte() }
        out += 1.toByte()
        varint(zs.size.toLong())
        for (z in zs) {
            val b = z.name.encodeToByteArray()
            varint(b.size.toLong())
            b.forEach { out += it }
            varint(z.polygons.size.toLong())
            for (rings in z.polygons) {
                varint(rings.size.toLong())
                for (ring in rings) {
                    varint(ring.size.toLong())
                    var plat = 0
                    var plon = 0
                    for (p in ring) {
                        val y = kotlin.math.round(p.first * 1000.0).toInt()
                        val x = kotlin.math.round(p.second * 1000.0).toInt()
                        zigzag(y - plat); zigzag(x - plon)
                        plat = y; plon = x
                    }
                }
            }
        }
        return out.toByteArray()
    }

    private fun square(s: Double, w: Double, n: Double, e: Double): List<Pair<Double, Double>> = listOf(s to w, s to e, n to e, n to w)

    // A zone with a hole, and another zone in the hole.
    private val zones = TimeZones.decode(
        pack(
            Z("Africa/Johannesburg", listOf(listOf(square(-35.0, 16.0, -22.0, 33.0), square(-30.0, 22.0, -27.0, 27.0)))),
            Z("Africa/Maseru", listOf(listOf(square(-30.0, 22.0, -27.0, 27.0)))),
            Z("Etc/GMT-2", listOf(listOf(square(-40.0, 33.0, -30.0, 40.0)), listOf(square(-50.0, 0.0, -45.0, 10.0)))),
        ),
    )

    @Test fun decodesZonesAndPolygons() {
        assertEquals(listOf("Africa/Johannesburg", "Africa/Maseru", "Etc/GMT-2"), zones.map { it.name })
        assertEquals(2, zones[0].polygons.first().size)
        assertEquals(2, zones[2].polygons.size)
        assertTrue(TimeZones.decode("nope".encodeToByteArray()).isEmpty())
    }

    @Test fun findsTheZoneAtAPlaceWithHoles() {
        assertEquals("Africa/Johannesburg", TimeZones.at(zones, -26.2, 28.0)?.name)
        // In Johannesburg's hole: Maseru's.
        assertEquals("Africa/Maseru", TimeZones.at(zones, -29.3, 25.0)?.name)
        assertEquals("Etc/GMT-2", TimeZones.at(zones, -47.0, 5.0)?.name)
        assertNull(TimeZones.at(zones, 10.0, 10.0))
    }

    @Test fun classesAndOffsets() {
        assertEquals(0, TimeZones.classOf(-12 * 3600))
        assertEquals(12, TimeZones.classOf(0))
        assertEquals(17, TimeZones.classOf(5 * 3600 + 1800))
        assertEquals(9, TimeZones.classOf(-(3 * 3600 + 1800)))
        assertEquals(26, TimeZones.classOf(14 * 3600))
        assertEquals(27, TimeZones.classes.size)
        assertEquals("+5:30", TimeZones.offset(5 * 3600 + 1800))
        assertEquals("−3:30", TimeZones.offset(-(3 * 3600 + 1800)))
        assertEquals("+2", TimeZones.offset(7200))
        assertEquals("0", TimeZones.offset(0))
        // Neighbouring hours differ.
        assertTrue(TimeZones.colors.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun findsTheNextClockChangeToTheMinute() {
        val from = 1_700_000_000_000L
        val change = from + 37L * 86_400_000L + 3_600_000L * 5 - (from % 60_000L)
        val c = assertNotNull(TimeZones.nextChange(from) { t -> if (t < change) 7200 else 10800 })
        assertEquals(change, c.atMillis)
        assertEquals(10800, c.offsetSeconds)
        assertNull(TimeZones.nextChange(from) { 3600 })
        assertNull(TimeZones.nextChange(from) { null })
    }

    @Test fun placeNames() {
        assertEquals("Buenos Aires" to "America · Argentina", TimeZones.place("America/Argentina/Buenos_Aires"))
        assertEquals("Jerusalem" to "Asia", TimeZones.place("Asia/Jerusalem"))
        assertNull(TimeZones.place("Etc/GMT-2"))
    }

    @Test fun geoJsonHasZonesLabelsAndWinding() {
        val json = TimeZones.geoJson(zones, mapOf("Africa/Johannesburg" to 7200, "Africa/Maseru" to 7200), hidden = setOf(-1))
        assertTrue("\"tap\":\"data:z:Africa/Johannesburg\"" in json)
        assertTrue("\"label\":\"+2\"" in json)
        // Unknown offset (the sea zone here) hidden as "no data".
        assertTrue("Etc/GMT-2" !in json)
        // The outer ring anticlockwise (from south-west, east first), the hole clockwise; rings closed.
        assertTrue("[[[[16,-35],[33,-35],[33,-22],[16,-22],[16,-35]],[[22,-30],[22,-27],[27,-27],[27,-30],[22,-30]]]]" in json)
    }

    @Test fun labelsGoInsideTheZone() {
        for (z in zones) {
            val (lat, lon) = assertNotNull(TimeZones.labelPoint(z))
            assertTrue(z.contains(lat, lon), z.name)
        }
        // The line through the middle of Johannesburg crosses its hole: the label is beside it.
        val (lat, lon) = TimeZones.labelPoint(zones[0])!!
        assertNull(TimeZones.at(zones.drop(1), lat / 1000.0, lon / 1000.0))
    }
}
