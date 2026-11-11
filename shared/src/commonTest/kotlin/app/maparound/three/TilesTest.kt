package app.maparound.three

import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertTrue
import app.maparound.tools.Elevation
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

class TilesTest {
    @Test
    fun tileMath() {
        val t = TileId(16, TileId.xOf(35.37, 16), TileId.yOf(31.56, 16))
        assertTrue(31.56 in t.south..t.north && 35.37 in t.west..t.east)
        assertEquals(TileId(15, t.x / 2, t.y / 2), t.parent)
        assertTrue(t.parent!!.contains(t))
        assertTrue(t.children.all { t.contains(it) && it.parent == t })
        assertFalse(t.contains(t.parent!!))
        assertEquals(t.ancestorAt(12), t.parent!!.parent!!.parent!!.parent)
        assertEquals(521.05, TileId.sizeM(16, 31.56), 0.1)
    }

    @Test
    fun detailNearTheCameraAndCoarseFarAway() {
        val frame = LocalFrame(31.56, 35.37)
        // Looking north from 3 km south, 2 km up.
        val pitch = toRadians(35.0)
        val d = 3_500.0
        val eyeX = 0.0
        val eyeY = -d * cos(pitch)
        val eyeZ = d * sin(pitch)
        val sel = TileSelector.select(frame, eyeX, eyeY, eyeZ, 0.0, 0.0, 0.0, rangeM = 25_000.0, maxZoom = 17)
        println("roots=${sel.roots.size} z${sel.roots.first().z} leaves=${sel.leaves.size} nodes=${sel.nodes.size} zooms=${sel.leaves.groupingBy { it.z }.eachCount().toSortedMap()}")
        assertTrue("bounded: ${sel.leaves.size}", sel.leaves.size in 10..140)
        // Leaves never overlap: no leaf contains another.
        for (a in sel.leaves) for (b in sel.leaves) if (a != b) assertFalse("$a overlaps $b", a.contains(b))
        // Every leaf's ancestors are nodes (drawn while it loads).
        for (l in sel.leaves) {
            var p = l.parent
            while (p != null && p.z >= sel.roots.first().z) { assertTrue("ancestor $p of $l", p in sel.nodes); p = p.parent }
        }
        val near = sel.leaves.first { 31.56 - 0.01 in it.south..it.north && 35.37 in it.west..it.east }
        val far = sel.leaves.filter { it.south > 31.56 + 0.15 }
        assertTrue("far tiles", far.isNotEmpty())
        assertTrue("near ${near.z} vs far ${far.maxOf { it.z }}", near.z > far.maxOf { it.z })
        // Nothing far behind the camera (well south of the eye).
        assertTrue("behind: " + sel.leaves.filter { it.north < 31.56 - 0.12 }, sel.leaves.none { it.north < 31.56 - 0.12 })
    }

    @Test
    fun tileMeshWithSkirts() {
        val frame = LocalFrame(31.56, 35.37)
        val t = TileId(15, TileId.xOf(35.37, 15), TileId.yOf(31.56, 15))
        val slope = Elevation { _, lon -> ((lon - 35.0) * 10_000).toFloat() } // rising eastwards
        val (v, has) = TileMesh.build(slope, t, frame, 17)
        assertTrue(has)
        assertEquals(TileMesh.STRIDE * TileMesh.vertexCount(17), v.size)
        val idx = TileMesh.indices(17)
        assertEquals(6 * 16 * 16 + 4 * 6 * 16, idx.size)
        assertTrue(idx.all { it in 0 until TileMesh.vertexCount(17) })
        // Corners line up with the tile bounds; texture coordinates span 0..1.
        assertEquals(frame.x(t.west), v[0], 0.01f)
        assertEquals(frame.y(t.north), v[1], 0.01f)
        val last = (17 * 17 - 1) * TileMesh.STRIDE
        assertEquals(frame.x(t.east), v[last], 0.01f)
        assertEquals(frame.y(t.south), v[last + 1], 0.01f)
        assertEquals(1f, v[last + 6], 0f)
        assertEquals(1f, v[last + 7], 0f)
        // Slope east: normal leans west (negative x); skirts hang below their edge vertex.
        assertTrue(v[8 * TileMesh.STRIDE + 3] < 0f)
        val skirt0 = 17 * 17 * TileMesh.STRIDE
        assertTrue(v[skirt0 + 2] < v[2] - 4f)
        // Neighbouring tiles share their edge exactly (no cracks at equal detail).
        val east = TileId(t.z, t.x + 1, t.y)
        val (w, _) = TileMesh.build(slope, east, frame, 17)
        for (r in 0 until 17) {
            val a = (r * 17 + 16) * TileMesh.STRIDE
            val b = (r * 17) * TileMesh.STRIDE
            assertEquals(v[a], w[b], 0.01f)
            assertEquals(v[a + 1], w[b + 1], 0.01f)
            assertEquals(v[a + 2], w[b + 2], 0.01f)
        }
    }
}
