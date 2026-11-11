package app.maparound.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MeshTest {
    private fun area(ring: FloatArray, tris: IntArray): Float {
        var a = 0f
        for (t in tris.indices step 3) {
            val (i, j, k) = Triple(tris[t], tris[t + 1], tris[t + 2])
            a += ((ring[2 * j] - ring[2 * i]) * (ring[2 * k + 1] - ring[2 * i + 1]) - (ring[2 * j + 1] - ring[2 * i + 1]) * (ring[2 * k] - ring[2 * i])) / 2
        }
        return a
    }

    @Test
    fun earClippingCoversConvexAndConcavePolygons() {
        val square = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)
        val t1 = Buildings.triangulate(square)
        assertEquals(6, t1.size)
        assertEquals(100f, area(square, t1), 1e-3f)
        // L shape, clockwise and closed (first point repeated), as OSM ways often are.
        val l = floatArrayOf(0f, 0f, 0f, 20f, 10f, 20f, 10f, 10f, 20f, 10f, 20f, 0f, 0f, 0f)
        val t2 = Buildings.triangulate(l)
        assertEquals(12, t2.size) // 6 corners -> 4 triangles
        assertEquals(300f, area(l, t2), 1e-3f)
        for (i in t2) assertTrue(i < 6)
    }

    @Test
    fun buildingHeightsFromTags() {
        assertEquals(25f, Buildings.heightOf(mapOf("building" to "yes", "height" to "25 m")), 0f)
        assertEquals(10f, Buildings.heightOf(mapOf("building" to "yes", "building:levels" to "3")), 0f)
        assertEquals(11.5f, Buildings.heightOf(mapOf("building" to "yes", "building:levels" to "3", "roof:levels" to "1")), 0f)
        assertEquals(3f, Buildings.heightOf(mapOf("building" to "garage")), 0f)
        assertEquals(7f, Buildings.heightOf(mapOf("building" to "yes")), 0f)
    }

    @Test
    fun extrudedBoxHasWallsAndRoofOnTheLowestGround() {
        val box = Footprint(listOf(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)), 12f)
        val v = Buildings.extrude(listOf(box)) { x, _ -> 100f + x } // ground rises to the east
        val verts = v.size / Buildings.STRIDE
        assertEquals(4 * 6 + 6, verts) // 4 walls of 2 triangles + a 2-triangle roof
        for (i in 0 until verts) assertEquals("stands on the lowest corner", 100f, v[i * Buildings.STRIDE + 2], 0f)
        // Walls point outwards: the wall along y=0 faces south (-y).
        val southWall = (0 until 24).map { it * Buildings.STRIDE }.first { abs(v[it + 1]) < 1e-6f && abs(v[it + 7 * 0 + 5] + 1f) < 1e-6f }
        assertEquals(-1f, v[southWall + 5], 1e-6f)
        val roof = (24 until 30).map { it * Buildings.STRIDE }
        assertTrue(roof.all { v[it + 3] == 12f && v[it + 6] == 1f })
    }
}
