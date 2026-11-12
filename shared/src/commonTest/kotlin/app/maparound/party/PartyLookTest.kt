package app.maparound.party

import app.maparound.shared.crypto.aesGcmSeal
import app.maparound.shared.crypto.randomBytes
import app.maparound.shared.util.ByteWriter
import app.maparound.testing.assertEquals
import app.maparound.testing.assertTrue
import kotlin.math.hypot
import kotlin.test.Test

class PartyLookTest {
    private val key = PartyKey.create("Jeep trip")
    private val teal = 0xFF00897B.toInt()

    @Test
    fun helloCarriesIconAndAnyColour() {
        val mixed = 0xFF7A33C1.toInt()
        for (hello in listOf(PartyMessage.Hello("Dana", teal, 0xDEADBEEFL, PartyIcon.OFFROAD.code), PartyMessage.Hello("Omer", mixed, 0, PartyIcon.EBIKE.code))) {
            val back = Envelope.decode(key, Envelope.encode(key, 7, 1, 0, hello))!!.message
            assertEquals(hello, back)
        }
    }

    @Test
    fun helloFromAFirstVersionStillReads() {
        // What the first versions sent: colour index, node, name; nothing after.
        val header = ByteWriter(18).byte(0x4D).byte(0xA1).int(key.tag).int(9).int(1).bytes(randomBytes(4)).toByteArray()
        val name = "Noa".encodeToByteArray()
        val plain = ByteWriter(32).byte(2).int(0).byte(5).int(0).byte(name.size).bytes(name).toByteArray()
        val packet = header + aesGcmSeal(key.secret, header.copyOfRange(6, 18), header, plain)
        val hello = Envelope.decode(key, packet)!!.message as PartyMessage.Hello
        assertEquals("Noa", hello.name)
        assertEquals(teal, hello.color) // index 5 of the first palette
        assertEquals(0, hello.icon)

        // And they get the nearest of their colours from a new one.
        assertEquals(5, PartyPalette.nearestLegacy(teal))
        assertEquals(5, PartyPalette.nearestLegacy(0xFF00796B.toInt())) // a darker teal
        PartyPalette.LEGACY.forEachIndexed { i, c -> assertEquals(i, PartyPalette.nearestLegacy(c)) }
    }

    @Test
    fun membersTakeTheirLookFromHello() {
        val engine = PartyEngine(key, myId = 1)
        engine.receive(Envelope.encode(key, 2, 1, 0, PartyMessage.Hello("Dana", teal, 0, PartyIcon.HIKE.code)), Via.ONLINE)
        val dana = engine.members[2]!!
        assertEquals(teal, dana.color)
        assertEquals(PartyIcon.HIKE, PartyIcon.of(dana.icon))
        // An icon from a newer version shows the initial.
        assertEquals(PartyIcon.LETTER, PartyIcon.of(250))
    }

    @Test
    fun everyIconIsAShapeInsideItsBox() {
        val codes = PartyIcon.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        for (icon in PartyIcon.entries) {
            val paths = PartyIcons.paths(icon)
            if (icon == PartyIcon.LETTER) {
                assertTrue(paths.isEmpty())
                assertEquals("", PartyIcons.svg(icon.code))
                continue
            }
            val points = paths.flatMap { p -> p.subpaths.flatMap { s -> s.toList() } }
            assertTrue(icon.name, points.size >= 12)
            assertTrue(icon.name, points.all { it in -0.5f..24.5f })
            val area = paths.sumOf { p -> p.subpaths.sumOf { abs(area(it)) } }
            assertTrue("${icon.name} covers $area", area > 40)
            val svg = PartyIcons.svg(icon.code)
            assertTrue(icon.name, (svg.startsWith("N ") || svg.startsWith("E ")) && "M " in svg && svg.endsWith("Z"))
        }
    }

    @Test
    fun theFourByFourHasHolesForTheNonZeroRule() {
        val subs = PartyIcons.paths(PartyIcon.OFFROAD).single().subpaths
        // Body, window, wheel, hub, wheel, hub: outlines one way round, holes the other.
        val signs = subs.map { area(it) > 0 }
        assertEquals(listOf(true, false, true, false, true, false), signs)
        assertEquals(6, subs.size)
    }

    @Test
    fun arcsFollowTheCircle() {
        // Half a circle round (1, 0), clockwise on screen from the left: over the top (y < 0).
        val pts = ArrayList<Pair<Float, Float>>()
        IconGeometry.arcPoints(0f, 0f, 1f, 1f, 0f, large = false, sweep = true, x2 = 2f, y2 = 0f) { x, y -> pts += x to y }
        assertEquals(2f to 0f, pts.last())
        assertTrue(pts.all { (x, y) -> abs(hypot((x - 1).toDouble(), y.toDouble()) - 1) < 1e-4 })
        assertTrue(pts.any { (_, y) -> y < -0.99f })
        // The other way round goes underneath.
        pts.clear()
        IconGeometry.arcPoints(0f, 0f, 1f, 1f, 0f, large = false, sweep = false, x2 = 2f, y2 = 0f) { x, y -> pts += x to y }
        assertTrue(pts.any { (_, y) -> y > 0.99f })
        // Radii too small for the distance grow to fit (SVG rule): still a half circle.
        pts.clear()
        IconGeometry.arcPoints(0f, 0f, 0.2f, 0.2f, 0f, large = false, sweep = true, x2 = 2f, y2 = 0f) { x, y -> pts += x to y }
        assertTrue(pts.any { (_, y) -> y < -0.99f })
    }

    /** Signed area (shoelace), positive for clockwise on screen (y down). */
    private fun area(s: FloatArray): Double {
        var a = 0.0
        val n = s.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += s[2 * i].toDouble() * s[2 * j + 1] - s[2 * j].toDouble() * s[2 * i + 1]
        }
        return a / 2
    }

    private fun abs(v: Double) = kotlin.math.abs(v)
}
