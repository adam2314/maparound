package app.maparound.three

import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertTrue
import app.maparound.tools.Elevation
import kotlin.math.abs
import kotlin.test.Test

class GlobeTest {
    @Test
    fun ecefRoundTrips() {
        for ((lat, lon, h) in listOf(Triple(0.0, 0.0, 0.0), Triple(47.14, 9.52, 1500.0), Triple(-33.9, 151.2, 30.0), Triple(78.2, 15.6, 0.0), Triple(10.0, -75.0, 400_000.0))) {
            val g = Wgs84.geodetic(Wgs84.ecef(lat, lon, h))
            assertEquals(lat, g[0], 1e-7)
            assertEquals(lon, g[1], 1e-7)
            assertEquals(h, g[2], 0.01)
        }
        // The equator at 0°/0° is the semi-major axis on x.
        val p = Wgs84.ecef(0.0, 0.0, 0.0)
        assertEquals(6_378_137.0, p.x, 0.001)
    }

    @Test
    fun upEastNorthAreAFrame() {
        val lat = 42.5
        val lon = 1.5
        val up = Wgs84.up(lat, lon)
        val e = Wgs84.east(lon)
        val n = Wgs84.north(lat, lon)
        assertEquals(0.0, up dot e, 1e-12)
        assertEquals(0.0, up dot n, 1e-12)
        assertEquals(0.0, e dot n, 1e-12)
        // Up is the way height grows.
        val dir = (Wgs84.ecef(lat, lon, 100.0) - Wgs84.ecef(lat, lon, 0.0)).normalized()
        assertEquals(1.0, dir dot up, 1e-9)
        // North is the way latitude grows.
        val dn = (Wgs84.ecef(lat + 0.001, lon, 0.0) - Wgs84.ecef(lat, lon, 0.0)).normalized()
        assertTrue(dn dot n > 0.999)
    }

    @Test
    fun cameraLooksAtItsTarget() {
        val cam = GlobeCamera(47.14, 9.52, 600.0, 5_000.0, yaw = 30.0, pitch = 40.0)
        assertEquals(5_000.0, (cam.eye - cam.target).length, 0.01)
        val vp = Mat4.multiply(Mat4.perspective(45.0, 0.5, 10.0, 100_000.0), cam.viewMatrix())
        val t = cam.target - cam.eye
        val c = clip(vp, t.x, t.y, t.z)
        assertEquals(0.0, c[0] / c[3], 1e-4)
        assertEquals(0.0, c[1] / c[3], 1e-4)
        // Seen from above the ground, at the chosen tilt.
        val alt = Wgs84.geodetic(cam.eye)[2]
        assertEquals(600.0 + 5_000.0 * kotlin.math.sin(40.0 * kotlin.math.PI / 180), alt, 2.0)
    }

    @Test
    fun farAwayTheCameraLooksStraightDownAtTheGlobe() {
        val cam = GlobeCamera(30.0, 10.0, 0.0, 15_000_000.0, yaw = 70.0, pitch = 30.0)
        assertEquals(90.0, cam.effectivePitch, 1e-9)
        val g = Wgs84.geodetic(cam.eye)
        assertEquals(30.0, g[0], 0.01)
        assertEquals(10.0, g[1], 0.01)
        assertTrue(cam.horizonSine() < -0.9)
        // Straight down works too (up is parallel to the view).
        assertFalse(cam.viewMatrix().any { it.isNaN() })
    }

    @Test
    fun panningFollowsTheFinger() {
        val cam = GlobeCamera(47.0, 9.0, 0.0, 10_000.0, yaw = 0.0, pitch = 90.0)
        // Finger down (dy > 0): the ground moves down, the view goes north. Finger left: east.
        assertTrue(cam.panned(0.0, 100.0, 45.0, 1000.0).first > 47.0)
        assertTrue(cam.panned(-100.0, 0.0, 45.0, 1000.0).second > 9.0)
        // Yaw 90°: the eye is east of the target, looking west; a finger down goes west.
        val west = GlobeCamera(47.0, 9.0, 0.0, 10_000.0, yaw = 90.0, pitch = 60.0)
        assertTrue((west.eye - west.target) dot Wgs84.east(9.0) > 0)
        assertTrue(west.panned(0.0, 100.0, 45.0, 1000.0).second < 9.0)
    }

    @Test
    fun selectionIsDetailedNearbyAndSkipsTheFarSide() {
        val cam = GlobeCamera(46.5, 8.0, 2_000.0, 6_000.0, yaw = 0.0, pitch = 35.0)
        val vp = Mat4.multiply(Mat4.perspective(45.0, 0.5, 50.0, 500_000.0), cam.viewMatrix())
        val sel = GlobeTileSelector.select(cam.eye, vp, maxZoom = 17)
        assertTrue(sel.leaves.isNotEmpty())
        val maxZ = sel.leaves.maxOf { it.z }
        val minZ = sel.leaves.minOf { it.z }
        assertTrue("$minZ..$maxZ", maxZ >= 13 && minZ <= 10)
        // Coarse toward the horizon: few tiles in all.
        assertTrue("${sel.leaves.size} leaves", sel.leaves.size <= 150)
        // Nothing from the other side of the Earth.
        assertTrue(sel.leaves.filter { (it.west + it.east) / 2 < -100 || (it.west + it.east) / 2 > 120 }.toString(),
            sel.leaves.none { (it.west + it.east) / 2 < -100 || (it.west + it.east) / 2 > 120 })
        // The tile under the target is among the most detailed.
        val under = sel.leaves.filter { 46.5 in it.south..it.north && 8.0 in it.west..it.east }
        assertTrue("$under (max $maxZ)", under.isNotEmpty() && under.all { it.z >= maxZ - 2 })
    }

    @Test
    fun fromSpaceItsTheVisibleHemisphereInBigTiles() {
        val cam = GlobeCamera(20.0, 0.0, 0.0, 18_000_000.0, yaw = 0.0, pitch = 45.0)
        val vp = Mat4.multiply(Mat4.perspective(45.0, 0.5, 100_000.0, 60_000_000.0), cam.viewMatrix())
        val sel = GlobeTileSelector.select(cam.eye, vp, maxZoom = 17)
        assertTrue(sel.leaves.all { it.z <= 4 })
        assertTrue(sel.leaves.any { 0.0 in it.west..it.east && 20.0 in it.south..it.north })
        assertTrue(sel.leaves.none { it.west >= 120 || it.east <= -120 })
    }

    @Test
    fun meshesSitOnTheEllipsoidWithTheirHeights() {
        val dem = Elevation { _, _ -> 1234f }
        val t = TileId(14, TileId.xOf(9.52, 14), TileId.yOf(47.14, 14))
        val m = GlobeMesh.build(dem, t)
        assertEquals(33, m.n)
        assertTrue(m.hasTerrain)
        assertEquals(GlobeMesh.STRIDE * TileMesh.vertexCount(33), m.vertices.size)
        // A corner: the tile's north-west corner, relative to the centre, height kept.
        val v = m.vertices
        val p = Vec3(v[0].toDouble(), v[1].toDouble(), v[2].toDouble()) + m.center
        val g = Wgs84.geodetic(p)
        assertEquals(t.north, g[0], 1e-5)
        assertEquals(t.west, g[1], 1e-5)
        assertEquals(1234f, v[6], 0f)
        // Flat ground: no slope; skirts hang below.
        assertEquals(0f, v[7], 1e-6f)
        val skirt = (33 * 33) * GlobeMesh.STRIDE
        assertTrue(v[skirt + 6] < 1234f)
        // Small numbers near the centre (float precision).
        assertTrue(abs(v[0]) < 2_000 && abs(v[1]) < 2_000 && abs(v[2]) < 2_000)
    }

    /** Rolling ground: no two neighbours alike, no straight runs (so any misplaced sample shows). */
    private val hills = Elevation { lat, lon -> (1000 + 400 * kotlin.math.sin(lat * 37.0) * kotlin.math.cos(lon * 23.0) + 90 * kotlin.math.sin(lon * 151.0)).toFloat() }

    private fun height(m: GlobeTileMesh, r: Int, c: Int) = m.vertices[(r * m.n + c) * GlobeMesh.STRIDE + 6]
    private fun parentHeight(m: GlobeTileMesh, r: Int, c: Int) = m.vertices[(r * m.n + c) * GlobeMesh.STRIDE + 11]

    @Test
    fun aFinerTileGrowsFromItsParentsShape() {
        // Same grid size (zoom 14 in 13): every other point is one of the parent's, the ones between
        // halfway along the parent's edges and its cells' diagonals.
        for (t in TileId(13, TileId.xOf(9.52, 13), TileId.yOf(47.14, 13)).children) {
            val parent = GlobeMesh.build(hills, t.parent!!)
            val child = GlobeMesh.build(hills, t)
            val half = (parent.n - 1) / 2
            val r0 = (t.y - 2 * t.parent!!.y) * half
            val c0 = (t.x - 2 * t.parent!!.x) * half
            // Inside (its edges don't grow, see below).
            for (i in 1 until half) for (j in 1 until half) {
                assertEquals(height(parent, r0 + i, c0 + j), parentHeight(child, 2 * i, 2 * j), 0.01f)
            }
            // Between two of the parent's points along a row: halfway between their heights.
            assertEquals((height(parent, r0 + 1, c0 + 1) + height(parent, r0 + 1, c0 + 2)) / 2, parentHeight(child, 2, 3), 0.01f)
            // Across a cell, on its diagonal (top-right to bottom-left): halfway between those two.
            assertEquals((height(parent, r0 + 1, c0 + 2) + height(parent, r0 + 2, c0 + 1)) / 2, parentHeight(child, 3, 3), 0.01f)
            // Its own heights are its own: they differ from the parent's between the parent's points.
            assertTrue(abs(height(child, 3, 3) - parentHeight(child, 3, 3)) > 0.01f)
            // Its edges stay where they end up, meeting the neighbours whatever they show.
            for (k in 0 until child.n) for ((r, c) in listOf(0 to k, child.n - 1 to k, k to 0, k to child.n - 1)) {
                assertEquals(height(child, r, c), parentHeight(child, r, c), 0f)
            }
        }
        // Zoom 12 in 11: the parent's grid is coarser (17 points), every fourth point is one of its.
        val t = TileId(12, TileId.xOf(9.52, 12), TileId.yOf(47.14, 12))
        val parent = GlobeMesh.build(hills, t.parent!!)
        val child = GlobeMesh.build(hills, t)
        assertEquals(17, parent.n)
        assertEquals(33, child.n)
        val r0 = (t.y - 2 * t.parent!!.y) * 8
        val c0 = (t.x - 2 * t.parent!!.x) * 8
        for (i in 1 until 8) for (j in 1 until 8) assertEquals(height(parent, r0 + i, c0 + j), parentHeight(child, 4 * i, 4 * j), 0.01f)
        // The skirt hangs below the parent's heights too.
        val skirt = child.n * child.n * GlobeMesh.STRIDE
        assertTrue(child.vertices[skirt + 11] < parentHeight(child, 0, 0))
    }

    @Test
    fun underAFlatParentItGrowsFromZero() {
        // Zoom 7 is the first with terrain: its parent (6) is flat.
        val t = TileId(7, TileId.xOf(9.52, 7), TileId.yOf(47.14, 7))
        val m = GlobeMesh.build(hills, t)
        assertTrue(m.hasTerrain)
        assertEquals(0f, parentHeight(m, 5, 5), 0f)
        assertTrue(height(m, 5, 5) > 100f)
    }
}
