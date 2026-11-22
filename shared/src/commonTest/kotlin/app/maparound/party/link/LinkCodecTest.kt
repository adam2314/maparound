package app.maparound.party.link

import app.maparound.party.PartyKey
import app.maparound.shared.crypto.Gcm
import app.maparound.shared.crypto.aesGcmSeal
import app.maparound.shared.crypto.randomBytes
import app.maparound.shared.util.ByteReader
import app.maparound.shared.util.ByteWriter
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class LinkCodecTest {
    private val party = PartyKey.create("Desert run")
    private val key = LinkKey.of(party)
    private val me = 0x1234ABCD
    private val dana = 0x0BADCAFE
    private val now = 1_795_000_000L
    private val here = GridPoint.of(31.7681, 35.1584)

    private fun roundTrip(m: LinkMessage, sender: Int = me, time: Long = now, seq: Int = 7): Pair<Int, LinkPacket> {
        val bytes = LinkEnvelope.seal(key, sender, time, seq, m)
        val packet = must(LinkEnvelope.open(key, bytes))
        assertEquals(LinkHeader(m.type, 0, key.tag, sender, time, seq), packet.header)
        return bytes.size to packet
    }

    private fun <T> must(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    // The spec's sizes (docs/LINK_PROTOCOL.md §9).

    @Test
    fun positionsAreAsSmallAsTheSpecSays() {
        val base = LinkMessage.Pos(here, accuracyM = 5, batteryPct = 80, heading = LinkMessage.Pos.heading(90.0))
        val (size, packet) = roundTrip(base)
        assertEquals(32, size)
        assertEquals(base, packet.message)
        val full = base.copy(speedKmh = 52, altM = -412, charging = true, sos = true)
        val (fullSize, fullPacket) = roundTrip(full)
        assertEquals(35, fullSize)
        assertEquals(full, fullPacket.message)
        assertEquals(29, roundTrip(LinkMessage.Pos(here)).first)
    }

    @Test
    fun aPositionCarriesItsModeAndTrail() {
        val trail = listOf(
            TrailPoint(GridPoint.of(31.7679, 35.1580), now - 20),
            TrailPoint(GridPoint.of(31.7672, 35.1571), now - 61),
            TrailPoint(GridPoint.of(31.7660, 35.1569), now - 118),
        )
        val m = LinkMessage.Pos(here, accuracyM = 4, heading = 200, speedKmh = 5, mode = ModeInfo(LinkMode.LIVE, auto = true), trail = trail)
        val (size, packet) = roundTrip(m)
        assertEquals(m, packet.message)
        // 33 bytes of position, speed and mode, a count, and 3–5 bytes a point.
        assertTrue("$size", size in 33 + 1 + 3 * 3..33 + 1 + 3 * 5)
        assertFailsWith<IllegalArgumentException> { LinkEnvelope.seal(key, me, now, 1, m.copy(trail = trail.reversed())) }
    }

    @Test
    fun helloSizeAndRoundTrip() {
        val hello = LinkMessage.Hello(
            "Noa B", 0x2E7D32, icon = 3, mode = ModeInfo(LinkMode.SAVER, auto = false), radio = true,
            meshNode = 0xDEADBEEFL, marks = MarksSummary(12, 0x7F00FF01),
        )
        val (size, packet) = roundTrip(hello)
        assertEquals(42, size)
        assertEquals(hello, packet.message)
        val live = hello.copy(gateway = true, meshNode = null, marks = MarksSummary(0, 0), live = LiveInfo(now + 3600, now - 5, dana))
        assertEquals(live, roundTrip(live).second.message)
    }

    @Test
    fun marksAreAsSmallAsTheSpecSays() {
        val id = MarkId(me, 1)
        val create = LinkMessage.Mark(
            id,
            geom = Reg(Geom.Point(here), now, me),
            style = Reg(Style(icon = MarkIcon.WATER), now, me),
            text = Reg(MarkText("Water"), now, me),
        )
        val (createSize, created) = roundTrip(create)
        assertEquals(47, createSize)
        assertEquals(create, created.message)
        val move = LinkMessage.Mark(id, geom = Reg(Geom.Point(GridPoint.of(31.77, 35.16)), now, dana))
        assertEquals(36, roundTrip(move, sender = dana).first)
        val delete = LinkMessage.Mark(id, alive = Reg(false, now, dana))
        assertEquals(30, roundTrip(delete, sender = dana).first)
    }

    @Test
    fun otherPacketsAreAsSmallAsTheSpecSays() {
        val ack = LinkMessage.Ack(listOf(PacketRef(dana, now - 3, 65_535)))
        assertEquals(32 to ack, roundTrip(ack).let { it.first to it.second.message })
        val live = LinkMessage.Live(now + 3600)
        assertEquals(26 to live, roundTrip(live).let { it.first to it.second.message })
        val fps = List(80) { Random.nextInt() }
        val digest = Marks.digest(fps).single()
        assertEquals(188, roundTrip(digest).first)
        assertEquals(digest, roundTrip(digest).second.message)
    }

    // The envelope.

    @Test
    fun theEnvelopeKeepsOthersOut() {
        val bytes = LinkEnvelope.seal(key, me, now, 1, LinkMessage.Text("Water at the spring"))
        assertNull(LinkEnvelope.open(LinkKey.of(PartyKey.create("Another")), bytes))
        for (i in bytes.indices) {
            if (i == 1) continue // the hop bits are the one thing a relay may change
            val forged = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull("byte $i", LinkEnvelope.open(key, forged))
        }
        val typeChanged = bytes.copyOf().also { it[1] = LinkType.SOS.toByte() }
        assertNull(LinkEnvelope.open(key, typeChanged))
        assertNull(LinkEnvelope.open(key, bytes.copyOf(bytes.size - 1)))
        assertNull(LinkEnvelope.header(byteArrayOf(0x4D, 0xA1.toByte()) + bytes.copyOfRange(2, bytes.size)))
        assertNull(LinkEnvelope.header(bytes.copyOf().also { it[1] = (it[1].toInt() or 0x40).toByte() }))
        assertFailsWith<IllegalArgumentException> { LinkEnvelope.seal(key, 0, now, 1, LinkMessage.Leave) }
    }

    @Test
    fun relaysCountHopsWithoutBreakingThePacket() {
        var bytes = LinkEnvelope.seal(key, me, now, 70_001, LinkMessage.Sos(true, "Twisted ankle"))
        for (hops in 1..LinkEnvelope.MAX_HOPS) {
            bytes = must(LinkEnvelope.relayed(bytes))
            val packet = must(LinkEnvelope.open(key, bytes))
            assertEquals(hops, packet.header.hops)
            assertEquals(70_001 and 0xFFFF, packet.header.seq)
            assertEquals(0, packet.header.priority)
            assertEquals(LinkMessage.Sos(true, "Twisted ankle"), packet.message)
        }
        assertNull(LinkEnvelope.relayed(bytes))
    }

    @Test
    fun aNewerTypeIsRelayedButNotRead() {
        val bytes = LinkEnvelope.seal(key, me, now, 1, LinkMessage.Unknown(14, byteArrayOf(1, 2, 3)))
        val packet = must(LinkEnvelope.open(key, bytes))
        val unknown = packet.message as LinkMessage.Unknown
        assertEquals(14, unknown.type)
        assertContentEquals(byteArrayOf(1, 2, 3), unknown.body)
        assertNotNull(LinkEnvelope.relayed(bytes))
    }

    @Test
    fun aFixedPacketForOtherImplementations() {
        // A known answer: if this changes, the wire format changed.
        val secret = ByteArray(32) { it.toByte() }
        val k = LinkKey(secret)
        val bytes = LinkEnvelope.seal(
            k, 0x01020304, 1_800_000_000L, 0x0506,
            LinkMessage.Pos(GridPoint.of(31.7681, 35.1584), accuracyM = 5, batteryPct = 80, heading = 64),
        )
        assertEquals(KNOWN_POSITION, bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    @Test
    fun gcmShortTagsAreTheFullTagsFirstBytes() {
        val k = randomBytes(32)
        val nonce = randomBytes(12)
        for (len in listOf(0, 1, 15, 16, 17, 40)) {
            val plain = randomBytes(len)
            val full = aesGcmSeal(k, nonce, byteArrayOf(9), plain)
            val short = Gcm.seal(k, nonce, byteArrayOf(9), plain, tagSize = 8)
            assertContentEquals(full.copyOf(len + 8), short)
            assertContentEquals(plain, Gcm.open(k, nonce, byteArrayOf(9), short, tagSize = 8))
            assertNull(Gcm.open(k, nonce, byteArrayOf(8), short, tagSize = 8))
        }
    }

    // The grid and the bytes.

    @Test
    fun theGridIsFinerThanAPhonesGps() {
        repeat(1000) {
            val lat = Random.nextDouble(-89.9, 89.9)
            val lon = Random.nextDouble(-180.0, 180.0)
            val p = GridPoint.of(lat, lon)
            assertTrue(abs(p.latDeg - lat) <= 45.0 / Grid.SCALE)
            assertTrue(abs(p.lonDeg - lon) <= 90.0 / Grid.SCALE || abs(abs(p.lonDeg - lon) - 360) <= 90.0 / Grid.SCALE)
        }
        assertEquals(Grid.SCALE - 1, GridPoint.of(90.0, 0.0).lat)
        assertEquals(-Grid.SCALE, GridPoint.of(-90.0, 0.0).lat)
        assertEquals(-Grid.SCALE, GridPoint.of(0.0, 180.0).lon)
        // Across the antimeridian a step is a step, not the world round.
        val east = GridPoint.of(-17.0, 179.9999)
        val west = GridPoint.of(-17.0, -179.9999)
        assertEquals(2, ByteWriter().delta(east, west).size)
        assertEquals(west, ByteReader(ByteWriter().delta(east, west).toByteArray()).delta(east))
    }

    @Test
    fun varintsAndStrings() {
        for (v in listOf(0L, 1L, -1L, 63L, -64L, 64L, 300L, -300L, Int.MAX_VALUE.toLong(), 1L shl 40, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(v, ByteReader(ByteWriter().zz(v).toByteArray()).zz())
        }
        assertEquals(1, ByteWriter().zz(-64).size)
        assertEquals(2, ByteWriter().zz(64).size)
        // Cut on a character, never in one: Hebrew letters are two bytes, the emoji four.
        val hebrew = "נקודת מים ליד המעיין, שלושה ק״מ מהחניה ומעבר לגשר"
        val cut = utf8Cut(hebrew, MarkText.NAME_MAX_BYTES)
        assertTrue(cut.size <= MarkText.NAME_MAX_BYTES)
        assertTrue(hebrew.startsWith(cut.decodeToString()))
        assertEquals("a", utf8Cut("a😀", 4).decodeToString())
        val (_, packet) = roundTrip(LinkMessage.Text("x".repeat(300)))
        assertEquals(LinkMessage.Text.TEXT_MAX, (packet.message as LinkMessage.Text).text.length)
    }

    // Marks.

    @Test
    fun registersCarryTheirVersionAndEditor() {
        val m = LinkMessage.Mark(
            MarkId(dana, 300),
            geom = Reg(Geom.Circle(here, 750), now - 86_400, dana),
            style = Reg(Style(rgb = 0xC62828, width = Width.THICK, line = LineStyle.DASHED, fill = Fill.LIGHT), now - 60, me),
            text = Reg(MarkText("Search area", "North of the wadi"), now + 2, dana), // a clock ahead: back < 0
            alive = Reg(true, now - 10, 0x7FFFFFFF),
        )
        // Sent by me: my register costs no editor id, the others' do.
        val (_, packet) = roundTrip(m)
        assertEquals(m, packet.message)
    }

    @Test
    fun shapesRoundTrip() {
        val line = Geom.Path(false, walk(120, 40.0))
        val area = Geom.Path(true, walk(5, 300.0))
        for (g in listOf(line, area)) {
            val m = LinkMessage.Mark(MarkId(me, 2), geom = Reg(g, now, me))
            assertEquals(m, roundTrip(m).second.message)
        }
        assertFailsWith<IllegalArgumentException> {
            LinkEnvelope.seal(key, me, now, 1, LinkMessage.Mark(MarkId(me, 3), geom = Reg(Geom.Path(true, walk(2, 10.0)), now, me)))
        }
    }

    @Test
    fun aLongLineIsSplitIntoPartsThatFitLoRa() {
        val path = Geom.Path(false, walk(400, 60.0))
        val mark = LinkMessage.Mark(
            MarkId(me, 4),
            geom = Reg(path, now - 30, me),
            style = Reg(Style(rgb = 0x1E88E5), now - 30, me),
            text = Reg(MarkText("Tomorrow's way", "Along the ridge, then down the gully"), now - 30, dana),
        )
        val packets = Marks.split(mark, me, now)
        assertTrue("${packets.size}", packets.size in 2..Part.MAX)
        val received = packets.map { m ->
            val bytes = LinkEnvelope.seal(key, me, now, 1, m)
            assertTrue("${bytes.size}", bytes.size <= LinkEnvelope.LORA_MAX)
            LinkEnvelope.open(key, bytes)!!.message as LinkMessage.Mark
        }
        // The other registers ride with part 0; the parts make the line again.
        assertEquals(mark.style, received[0].style)
        assertEquals(mark.text, received[0].text)
        assertTrue(received.all { it.geom!!.version == now - 30 })
        assertEquals(path, Marks.join(received.shuffled().map { it.geom!!.value as Geom.Path }))
        assertNull(Marks.join(received.drop(1).map { it.geom!!.value as Geom.Path }))
        // Online it goes whole.
        assertEquals(listOf(mark), Marks.split(mark, me, now, LinkEnvelope.ONLINE_MAX))
        assertFailsWith<IllegalArgumentException> {
            Marks.split(LinkMessage.Mark(MarkId(me, 5), geom = Reg(Geom.Path(false, walk(1000, 2000.0)), now, me)), me, now)
        }
    }

    @Test
    fun aLongNoteWithAShapeStillFits() {
        val mark = LinkMessage.Mark(
            MarkId(dana, 9),
            geom = Reg(Geom.Path(true, walk(60, 50.0)), now - 400_000, dana),
            style = Reg(Style(icon = MarkIcon.DANGER, rgb = 0xFF0000), now - 400_000, dana),
            text = Reg(MarkText("ש".repeat(20), "ק".repeat(60)), now - 400_000, dana),
            alive = Reg(true, now - 400_000, dana),
        )
        val packets = Marks.split(mark, me, now)
        packets.forEach { assertTrue(LinkEnvelope.seal(key, me, now, 1, it).size <= LinkEnvelope.LORA_MAX) }
        val back = packets.map { LinkEnvelope.open(key, LinkEnvelope.seal(key, me, now, 1, it))!!.message as LinkMessage.Mark }
        assertEquals(mark.text, back.firstNotNullOf { it.text })
        assertEquals(mark.geom!!.value, Marks.join(back.mapNotNull { it.geom?.value as? Geom.Path }))
    }

    @Test
    fun fingerprintsSeeEveryChange() {
        val m = LinkMessage.Mark(MarkId(me, 1), geom = Reg(Geom.Point(here), now, me), text = Reg(MarkText("Camp"), now, me))
        val fp = Marks.fingerprint(m)
        assertEquals(fp, Marks.fingerprint(m.copy()))
        for (changed in listOf(
            m.copy(geom = Reg(Geom.Point(GridPoint(here.lat + 1, here.lon)), now, me)),
            m.copy(text = Reg(MarkText("Camp"), now + 1, me)),
            m.copy(text = Reg(MarkText("Camp"), now, dana)),
            m.copy(text = Reg(MarkText("Camp 2"), now, me)),
            m.copy(alive = Reg(false, now, me)),
            m.copy(id = MarkId(me, 2)),
        )) {
            assertFalse("$changed", Marks.fingerprint(changed) == fp)
        }
    }

    @Test
    fun summariesAndDigestPages() {
        val fps = List(500) { Random.nextInt() }
        assertEquals(Marks.summary(fps), Marks.summary(fps.shuffled()))
        assertEquals(500, Marks.summary(fps).count)
        assertFalse(Marks.summary(fps) == Marks.summary(fps.drop(1)))
        val pages = Marks.digest(fps)
        assertTrue(pages.size >= 7)
        assertTrue(pages.all { it.fp16s.size <= LinkMessage.Digest.MAX_PER_PAGE && it.pages == pages.size })
        assertEquals(fps.map { it and 0xFFFF }.sorted(), pages.flatMap { it.fp16s }.sorted())
        pages.forEach { assertTrue(LinkEnvelope.seal(key, me, now, 1, it).size <= LinkEnvelope.LORA_MAX) }
        assertEquals(MarksSummary(0, 0), Marks.summary(emptyList()))
        assertEquals(listOf(LinkMessage.Digest(MarksSummary(0, 0), 0, 1, emptyList())), Marks.digest(emptyList()))
    }

    @Test
    fun wantsRoundTrip() {
        val want = LinkMessage.Want(listOf(WantEntry.Fingerprint(0xBEEF), WantEntry.Parts(MarkId(dana, 77), 0b1010), WantEntry.Fingerprint(1)))
        assertEquals(want, roundTrip(want).second.message)
    }

    /** A wandering line of [n] vertices about [stepM] apart, from [here]. */
    private fun walk(n: Int, stepM: Double, random: Random = Random(n)): List<GridPoint> {
        var lat = here.latDeg
        var lon = here.lonDeg
        var heading = 0.0
        return List(n) {
            heading += random.nextDouble(-0.6, 0.6)
            lat += stepM * cos(heading) / 111_320
            lon += stepM * sin(heading) / (111_320 * cos(lat * kotlin.math.PI / 180))
            GridPoint.of(lat, lon)
        }.distinct()
    }

    private companion object {
        /**
         * Secret 00 01 … 1f, sender 01020304, time 1 800 000 000, seq 0506: a position at
         * 31.7681, 35.1584, ±5 m, 80% battery, heading east. Also made independently from the spec
         * with WebCrypto (Node), byte for byte.
         */
        const val KNOWN_POSITION = "a2016625010203046b49d20005066f83d166fd9024676a6b89a1be6e1ae0d917"
    }
}
