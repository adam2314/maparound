package app.maparound.map

import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import kotlin.math.abs
import kotlin.test.Test

class WorldGeometryTest {
    private fun area(c: IntArray): Double {
        var s = 0.0
        val n = c.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += c[2 * i + 1].toDouble() * c[2 * j] - c[2 * j + 1].toDouble() * c[2 * i]
        }
        return abs(s) / 2
    }

    /** A square 0..10 (lat and lon, in whole units). */
    private val square = intArrayOf(0, 0, 0, 10, 10, 10, 10, 0)

    @Test
    fun clipsRings() {
        val ring = ClipRing(square)
        assertTrue(ring.counterClockwise)
        // Inside the box: unchanged; outside: nothing.
        assertTrue(ring.clip(GeoBox(-5, -5, 15, 15)).single().contentEquals(square))
        assertTrue(ring.clip(GeoBox(20, 20, 30, 30)).isEmpty())
        // Half of it.
        assertEquals(50.0, area(ring.clip(GeoBox(-5, -5, 5, 15)).single()), 0.01)
        // A box inside the square: the box itself.
        assertEquals(4.0, area(ring.clip(GeoBox(2, 2, 4, 4)).single()), 0.01)
        // Clockwise rings are cut the same way and stay clockwise.
        val cw = ClipRing(intArrayOf(0, 0, 10, 0, 10, 10, 0, 10))
        assertTrue(!cw.counterClockwise)
        val piece = cw.clip(GeoBox(-5, -5, 5, 15)).single()
        assertEquals(50.0, area(piece), 0.01)
        assertTrue(!ClipRing(piece).counterClockwise)
    }

    @Test
    fun cutsAUShapeIntoTwoPiecesWithoutACorridor() {
        // A U opening north: arms at lon 0..10 and 20..30, joined below lat 10.
        val u = intArrayOf(0, 0, 0, 30, 40, 30, 40, 20, 10, 20, 10, 10, 40, 10, 40, 0)
        val ring = ClipRing(u)
        // A box across both arms, above the bottom: two separate pieces, nothing in the gap.
        val pieces = ring.clip(GeoBox(20, -5, 30, 35))
        assertEquals(2, pieces.size)
        assertEquals(200.0, pieces.sumOf { area(it) }, 0.01)
        for (p in pieces) {
            for (k in p.indices step 2) assertTrue("point in the gap", p[k + 1] <= 10 || p[k + 1] >= 20)
        }
        // A box that also takes the bottom: one piece again.
        assertEquals(1, ring.clip(GeoBox(5, -5, 30, 35)).size)
    }

    @Test
    fun clipsBigRingsTheSameWithFarRunsSkipped() {
        // A wiggly coast of 2000 points around a 20°×20° block; clip a small box on its edge.
        val pts = ArrayList<Int>()
        for (i in 0 until 500) { pts += 0; pts += i * 40_000 + (i % 7) * 1_000 }                 // south edge, lon 0 → 20°
        for (i in 0 until 500) { pts += i * 40_000 + (i % 5) * 1_000; pts += 20_000_000 }        // east edge
        for (i in 0 until 500) { pts += 20_000_000; pts += 20_000_000 - i * 40_000 }             // north edge
        for (i in 0 until 500) { pts += 20_000_000 - i * 40_000; pts += (i % 3) * 1_000 }        // west edge
        val coords = pts.toIntArray()
        val box = GeoBox(-500_000, 9_000_000, 500_000, 10_000_000)
        val pieces = ClipRing(coords).clip(box)
        assertEquals(1, pieces.size)
        // The part of the block in the box: lat 0 … 0.5°, lon 9° … 10°, give or take the wiggle.
        assertEquals(500_000.0 * 1_000_000, area(pieces[0]), 500_000.0 * 1_000_000 * 0.02)
        // A box well inside the block: the box.
        assertEquals(4, ClipRing(coords).clip(GeoBox(5_000_000, 5_000_000, 6_000_000, 6_000_000)).single().size / 2)
    }

    @Test
    fun clipsLines() {
        // In, out, back in: two parts.
        val line = intArrayOf(0, 0, 0, 20, 20, 20, 20, 5, 5, 5)
        val parts = clipLine(line, GeoBox(-1, -1, 10, 10))
        assertEquals(2, parts.size)
        assertTrue(parts[0].contentEquals(intArrayOf(0, 0, 0, 10)))
        assertTrue(parts[1].contentEquals(intArrayOf(10, 5, 5, 5)))
        assertTrue(clipLine(line, GeoBox(100, 100, 200, 200)).isEmpty())
    }

    @Test
    fun cutsBoxesOutOfLines() {
        val line = intArrayOf(0, 0, 0, 30)
        val parts = cutOutBox(line, GeoBox(-5, 10, 5, 20))
        assertEquals(2, parts.size)
        assertTrue(parts[0].contentEquals(intArrayOf(0, 0, 0, 10)))
        assertTrue(parts[1].contentEquals(intArrayOf(0, 20, 0, 30)))
        assertTrue(cutOutBox(line, GeoBox(-5, -5, 5, 35)).isEmpty())
        assertTrue(cutOutBox(line, GeoBox(10, 10, 20, 20)).single().contentEquals(line))
    }

    @Test
    fun subtractsBoxes() {
        val tile = GeoBox(0, 0, 100, 100)
        // A hole in the middle: four rectangles around it, covering the rest exactly.
        val parts = subtractBoxes(tile, listOf(GeoBox(40, 40, 60, 60)))
        assertEquals(4, parts.size)
        assertEquals(100.0 * 100 - 20 * 20, parts.sumOf { (it.maxLat - it.minLat).toDouble() * (it.maxLon - it.minLon) }, 0.0)
        // Covered wholly: nothing left; a hole elsewhere: the tile.
        assertTrue(subtractBoxes(tile, listOf(GeoBox(-10, -10, 110, 110))).isEmpty())
        assertEquals(listOf(tile), subtractBoxes(tile, listOf(GeoBox(200, 200, 300, 300))))
        // Two overlapping holes.
        val two = subtractBoxes(tile, listOf(GeoBox(-10, -10, 50, 50), GeoBox(30, 30, 110, 60)))
        val area = two.sumOf { (it.maxLat - it.minLat).toDouble() * (it.maxLon - it.minLon) }
        assertEquals(100.0 * 100 - 50 * 50 - (100 - 30) * (60 - 30) + (50 - 30) * (50 - 30), area, 0.0)
    }

    @Test
    fun joinsPiecesOfALine() {
        val out = ArrayList<IntArray>()
        val j = LineJoiner { _, c -> out += c }
        val a = "a"
        j.add(a, intArrayOf(0, 0, 1, 1))
        j.add(a, intArrayOf(1, 1, 2, 2))
        j.add(a, intArrayOf(5, 5, 6, 6)) // a gap: a new line
        j.add("b", intArrayOf(6, 6, 7, 7)) // another line
        j.flush()
        assertEquals(3, out.size)
        assertTrue(out[0].contentEquals(intArrayOf(0, 0, 1, 1, 2, 2)))
    }
}
