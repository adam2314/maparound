package app.maparound.tools

import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint
import app.maparound.shared.util.toRadians
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.math.cos
import kotlin.math.exp
import kotlin.test.Test

class SightTest {
    private val a = GeoPoint(31.0, 35.0)
    private val b = Geodesy.destination(a, 90.0, 10_000.0) // 10 km east

    /** Flat 100 m plain with a 300 m-high hill (gaussian) 5 km east of a. */
    private val hill = Elevation { lat, lon ->
        val dx = (lon - 35.0) * 111_320 * cos(toRadians(31.0)) - 5_000
        val dy = (lat - 31.0) * 110_574
        (100 + 300 * exp(-(dx * dx + dy * dy) / (2 * 600.0 * 600.0))).toFloat()
    }
    private val flat = Elevation { _, _ -> 100f }

    @Test
    fun bearingDistanceAndDestination() {
        assertEquals(90.0, Geodesy.bearing(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0)), 1e-9)
        assertEquals(0.0, Geodesy.bearing(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 1e-9)
        assertEquals(10_000.0, Geo.distance(a, b), 0.5)
        // Destination and bearing are consistent both ways.
        val p = Geodesy.destination(a, 237.5, 12_345.0)
        assertEquals(237.5, Geodesy.bearing(a, p), 1e-6)
        assertEquals(12_345.0, Geo.distance(a, p), 0.5)
        assertEquals(1600, Geodesy.mils(90.0))
        assertEquals(6.8, Geodesy.curvatureDrop(10_000.0), 0.1)
    }

    @Test
    fun hillBlocksTheView() {
        val los = LineOfSight.compute(hill, a, b, 1.7, 1.7)!!
        assertFalse(los.targetVisible)
        val o = los.obstacle!!
        assertEquals("blocked by the hill top", 5_000.0, o.distanceM, 100.0)
        assertTrue("hill rises far above the sight line", los.marginM < -250)
        // Ground right behind the hill is hidden, the hill's near slope is visible.
        assertTrue(los.samples.first { it.distanceM > 4_000 }.visible)
        assertFalse(los.samples.first { it.distanceM > 6_000 }.visible)
    }

    @Test
    fun clearOverFlatGroundOrFromHighEnough() {
        // Two standing people see each other over 4 km of flat ground (horizon ~5 km each).
        val los = LineOfSight.compute(flat, a, Geodesy.destination(a, 90.0, 4_000.0), 1.7, 1.7)!!
        assertTrue(los.targetVisible)
        assertNull(los.obstacle)
        assertTrue("clearance over flat ground", los.marginM in 0.5..1.7)
        // From 1000 m up (e.g. a mountain top / drone) the hill no longer blocks.
        assertTrue(LineOfSight.compute(hill, a, b, 1000.0, 1.7)!!.targetVisible)
        // Over 50 km of flat sea-level ground two 1.7 m people can't see each other (curvature).
        val far = Geodesy.destination(a, 90.0, 50_000.0)
        assertFalse(LineOfSight.compute({ _, _ -> 0f }, a, far, 1.7, 1.7)!!.targetVisible)
    }

    @Test
    fun radioFormulas() {
        // Free-space loss: 10 km at 868 MHz is about 111 dB; doubling the distance adds 6 dB.
        assertEquals(111.2, LineOfSight.freeSpaceLossDb(10_000.0, 868.0), 0.1)
        assertEquals(6.02, LineOfSight.freeSpaceLossDb(20_000.0, 868.0) - LineOfSight.freeSpaceLossDb(10_000.0, 868.0), 0.01)
        // Knife edge (ITU-R P.526): grazing (v = 0) costs about 6 dB, well clear costs nothing.
        assertEquals(6.0, LineOfSight.knifeEdgeLossDb(0.0), 0.1)
        assertEquals(0.0, LineOfSight.knifeEdgeLossDb(-1.0), 0.0)
        assertEquals(13.9, LineOfSight.knifeEdgeLossDb(1.0), 0.1)
        // Radio bends round the Earth more than light: less drop at the same distance.
        assertTrue(Geodesy.curvatureDrop(10_000.0, Geodesy.RADIO_K) < Geodesy.curvatureDrop(10_000.0))
        assertEquals(5.9, Geodesy.curvatureDrop(10_000.0, Geodesy.RADIO_K), 0.1)
    }

    @Test
    fun fresnelZoneOverFlatGround() {
        // 10 km at 868 MHz: wavelength 0.345 m, zone radius halfway √(λ·d/4) = 29.4 m.
        val los = LineOfSight.compute(flat, a, b, 10.0, 10.0, frequencyMHz = 868.0)!!
        val f = los.fresnel!!
        assertEquals(0.3454, f.wavelengthM, 0.0005)
        assertEquals(29.4, f.midRadiusM, 0.2)
        assertEquals(f.midRadiusM, f.radiusAt(5_000.0, 10_000.0), 1e-9)
        assertEquals(0.0, f.radiusAt(0.0, 10_000.0), 0.0)
        // Masts 10 m high see each other, but the flat ground (bulging ~1.5 m with radio's
        // curvature halfway) fills most of the zone: line of sight is not a clear radio path.
        assertTrue(los.targetVisible)
        assertTrue("${f.clearance}", f.clearance in 0.1..0.4)
        assertTrue("extra loss ${f.obstructionLossDb}", f.obstructionLossDb in 1.0..6.0)
        // Every sample between the ends knows its share of the zone; the ends don't.
        assertTrue(los.samples.drop(1).dropLast(1).none { it.zone.isNaN() })
        assertTrue(los.samples.last().zone.isNaN())
        assertEquals(los.eyeM, los.lineAt(0.0), 1e-9)

        // On 40 m masts the zone is clear and the loss is free space alone.
        val high = LineOfSight.compute(flat, a, b, 40.0, 40.0, frequencyMHz = 868.0)!!.fresnel!!
        assertTrue(high.clearance >= LineOfSight.Fresnel.RULE)
        assertEquals(0.0, high.obstructionLossDb, 0.0)
        assertEquals(high.freeSpaceLossDb, high.totalLossDb, 0.0)
        // A lower frequency has a wider zone: the same masts are tighter at 145 MHz.
        val vhf = LineOfSight.compute(flat, a, b, 40.0, 40.0, frequencyMHz = 145.0)!!.fresnel!!
        assertTrue(vhf.midRadiusM > 2 * high.midRadiusM)
        assertTrue(vhf.clearance < high.clearance)
    }

    @Test
    fun hillInTheWayOfARadioLink() {
        val los = LineOfSight.compute(hill, a, b, 2.0, 2.0, frequencyMHz = 433.0)!!
        val f = los.fresnel!!
        assertFalse(los.targetVisible)
        assertTrue(f.clearance < 0)
        assertEquals("tightest at the hill top", 5_000.0, f.tightest!!.distanceM, 100.0)
        // ~300 m into a ~42 m zone: a big diffraction loss.
        assertTrue("${f.obstructionLossDb}", f.obstructionLossDb > 25)
        // The light check is unchanged by a radio link's existence.
        assertNull(LineOfSight.compute(hill, a, b, 2.0, 2.0)!!.fresnel)
    }

    @Test
    fun findsHigherGroundNextToTheObserver() {
        val top = Geodesy.destination(a, 90.0, 5_000.0) // the hill top
        val below = Geodesy.destination(top, 200.0, 60.0)
        val (p, z) = highestNear(hill, below)!!
        assertTrue("higher than where we stand", z > hill.at(below.lat, below.lon) + 1)
        assertTrue("towards the top", Geo.distance(p, top) < 30)
        // On flat ground nothing is higher.
        val (_, flatZ) = highestNear(flat, a)!!
        assertEquals(100.0, flatZ, 1e-6)
    }

    @Test
    fun viewshedShadowBehindTheHill() {
        // From a 20 m tower (horizon ~17 km) so only the hill hides anything.
        val vs = Viewshed.compute(hill, a, 9_000.0, 20.0, 0.0, size = 201)!!
        assertEquals(201, vs.size)
        fun cellAt(p: GeoPoint): Int {
            val x = Geodesy.mercX(p.lon)
            val y = Geodesy.mercY(p.lat)
            val c = ((x - vs.minX) / (vs.maxX - vs.minX) * vs.size).toInt()
            val r = ((vs.maxY - y) / (vs.maxY - vs.minY) * vs.size).toInt()
            return vs.cells[r * vs.size + c].toInt()
        }
        assertEquals("behind the hill (east)", 0, cellAt(Geodesy.destination(a, 90.0, 7_500.0)))
        assertEquals("open ground west", 1, cellAt(Geodesy.destination(a, 270.0, 7_500.0)))
        assertEquals("north is open", 1, cellAt(Geodesy.destination(a, 0.0, 7_500.0)))
        assertEquals("outside the circle", -1, vs.cells[0].toInt())
        val f = vs.visibleFraction
        assertTrue("most ground visible, a shadow behind the hill: $f", f in 0.85..0.99)
        // Standing on a plain, ground beyond the ~5 km horizon is hidden.
        val low = Viewshed.compute(flat, a, 9_000.0, 1.7, 0.0, size = 201)!!
        fun lowAt(dist: Double): Int {
            val p = Geodesy.destination(a, 270.0, dist)
            val c = ((Geodesy.mercX(p.lon) - low.minX) / (low.maxX - low.minX) * low.size).toInt()
            return low.cells[(low.size / 2) * low.size + c].toInt()
        }
        assertEquals(1, lowAt(3_000.0))
        assertEquals(0, lowAt(7_500.0))
        assertNotNull(Viewshed.compute(flat, a, 1_000.0, 1.7, 0.0, size = 51))
    }
}
