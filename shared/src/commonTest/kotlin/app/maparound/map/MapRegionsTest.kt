package app.maparound.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapRegionsTest {
    /** regions.bin as tools/regions/make_regions.py writes it: (path, rings of (lat, lon) in degrees). */
    private fun pack(vararg regions: Pair<String, List<List<Pair<Double, Double>>>>): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(v: Long) {
            var n = v
            while (true) {
                val b = (n and 0x7F).toInt()
                n = n ushr 7
                if (n != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); return }
            }
        }
        fun zigzag(n: Int) = varint(((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL)
        "MARG".encodeToByteArray().forEach { out += it }
        out += 1
        varint(regions.size.toLong())
        for ((path, rings) in regions) {
            val name = path.encodeToByteArray()
            varint(name.size.toLong())
            name.forEach { out += it }
            varint(rings.size.toLong())
            for (ring in rings) {
                varint(ring.size.toLong())
                var pLat = 0
                var pLon = 0
                for ((la, lo) in ring) {
                    val lat = kotlin.math.round(la * 1000).toInt()
                    val lon = kotlin.math.round(lo * 1000).toInt()
                    zigzag(lat - pLat)
                    zigzag(lon - pLon)
                    pLat = lat
                    pLon = lon
                }
            }
        }
        return out.toByteArray()
    }

    private fun box(s: Double, w: Double, n: Double, e: Double) = listOf(s to w, s to e, n to e, n to w)

    @Test
    fun whichMapIsMadeForAPlace() {
        val regions = MapRegions.decode(
            pack(
                "europe/germany" to listOf(box(47.4, 6.0, 55.0, 15.0)),
                // A state with a hole (as Brandenburg has Berlin).
                "europe/germany/brandenburg" to listOf(box(51.4, 11.3, 53.5, 14.8), box(52.3, 13.0, 52.7, 13.8)),
                "europe/germany/berlin" to listOf(box(52.3, 13.0, 52.7, 13.8)),
                "europe/liechtenstein" to listOf(box(47.0, 9.4, 47.3, 9.7)),
            ),
        )!!
        assertEquals(4, regions.size)
        // The most specific first.
        assertEquals(listOf("europe/germany/brandenburg", "europe/germany"), regions.at(52.0, 14.0).map { it.path })
        assertEquals(listOf("europe/germany/berlin", "europe/germany"), regions.at(52.5, 13.4).map { it.path })
        assertEquals(listOf("europe/germany"), regions.at(48.1, 11.6).map { it.path })
        assertEquals(listOf("europe/liechtenstein"), regions.at(47.15, 9.55).map { it.path })
        assertTrue(regions.at(0.0, 0.0).isEmpty())

        val berlin = regions.at(52.5, 13.4).first()
        assertEquals("Berlin", berlin.title)
        assertEquals("Berlin, Germany", berlin.label)
        assertEquals("europe__germany__berlin.map", berlin.fileName)
        assertEquals("Liechtenstein", regions.at(47.15, 9.55).first().label)
        assertEquals("europe/liechtenstein", regions.byFileName("europe__liechtenstein.map")?.path)
        assertNull(regions.byFileName("my-own.map"))

        assertNull(MapRegions.decode("MABL\u0001".encodeToByteArray()))
        assertNull(MapRegions.decode(ByteArray(3)))

        // A border line across Liechtenstein's box: only what's outside the region stays.
        val li = regions.byFileName("europe__liechtenstein.map")!!
        val line = intArrayOf(47_150_000, 9_000_000, 47_150_000, 10_000_000)
        // Exactly at the outline (no margin), then with the 2 km the world's coarser lines get.
        val exact = li.cutOut(line, marginM = 0.0)
        assertEquals(2, exact.size)
        assertEquals(listOf(47_150_000, 9_000_000), exact[0].take(2))
        assertEquals(9_400_000, exact[0][exact[0].size - 1])
        assertEquals(9_700_000, exact[1][1])
        assertEquals(10_000_000, exact[1].last())
        val parts = li.cutOut(line)
        assertEquals(2, parts.size)
        // 2 km at 47° is about 0.026° of longitude; the pieces are about a kilometre.
        assertTrue(parts[0].last() in 9_355_000..9_380_000, "${parts[0].last()}")
        assertTrue(parts[1][1] in 9_720_000..9_745_000, "${parts[1][1]}")
        // Well away: untouched; inside: gone.
        val away = intArrayOf(40_000_000, 9_000_000, 40_000_000, 10_000_000)
        assertEquals(listOf(away.toList()), li.cutOut(away).map { it.toList() })
        assertTrue(li.cutOut(intArrayOf(47_100_000, 9_500_000, 47_200_000, 9_600_000)).isEmpty())
        // Where a map draws: in its region, not just its box.
        val cover = Coverage(GeoBox(46_900_000, 9_300_000, 47_400_000, 9_800_000), li)
        assertTrue(cover.contains(47_150_000, 9_550_000))
        assertTrue(!cover.contains(47_350_000, 9_750_000))
    }
}
