package app.maparound.party.link

import app.maparound.party.PartyKey
import app.maparound.sensors.GeoFix
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

/** Parties on a simulated network: radios hear the others in range, an MQTT server echoes to all. */
class LinkPartyTest {
    private val partyKey = PartyKey.create("Trip")
    private val t0 = 1_795_000_000_000L
    private val here = GridPoint.of(31.7681, 35.1584)

    private class Phone(val name: String, val party: LinkParty, val conns: Map<ConnectionId, ConnectionKind>)

    private inner class Net {
        var nowMs = t0
        val phones = ArrayList<Phone>()
        val sent = ArrayList<Pair<Phone, LinkOut>>()
        val events = HashMap<String, MutableList<LinkEvent>>()

        fun phone(name: String, id: Int, vararg conns: Pair<ConnectionId, ConnectionKind>): Phone {
            val party = LinkParty(partyKey, id, LinkProfile(name, 0x2E7D32), random = Random(id))
            conns.forEach { (c, k) -> party.addConnection(c, k, nowMs) }
            return Phone(name, party, conns.toMap()).also { phones += it }
        }

        fun events(p: Phone): MutableList<LinkEvent> = events.getOrPut(p.name) { ArrayList() }

        /** Delivers [outs] from [from], and what they cause, until nothing more goes. */
        fun send(from: Phone, outs: List<LinkOut>) {
            val queue = ArrayDeque(outs.map { from to it })
            while (queue.isNotEmpty()) {
                val (src, out) = queue.removeFirst()
                sent += src to out
                val kind = src.conns.getValue(out.to)
                for (p in phones) {
                    if (out.to !in p.conns || (p === src && kind != ConnectionKind.ONLINE)) continue
                    val r = p.party.receive(out.bytes, out.to, nowMs)
                    events(p) += r.events
                    r.out.forEach { queue += p to it }
                }
            }
        }

        fun act(p: Phone, r: LinkResult) {
            events(p) += r.events
            send(p, r.out)
        }

        fun run(seconds: Int, before: (Int) -> Unit = {}) {
            repeat(seconds) { s ->
                nowMs += 1000
                before(s)
                for (p in phones) act(p, p.party.tick(LinkDevice(nowMs, batteryPct = 80)))
            }
        }

        fun sentBy(p: Phone, type: Int, on: ConnectionId? = null) =
            sent.filter { (src, out) -> src === p && out.header.type == type && (on == null || out.to == on) }
    }

    private val ONLINE = ConnectionKind.ONLINE
    private val LORA = ConnectionKind.LORA

    @Test
    fun membersMeetAndSeeEachOther() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "mqtt" to ONLINE)
        val b = net.phone("Dana", 0x0B0B0B0B, "mqtt" to ONLINE)
        net.run(2)
        assertEquals("Dana", a.party.members[0x0B0B0B0B]?.name)
        assertEquals("Adam", b.party.members[0x0A0A0A0A]?.name)
        assertTrue(net.events(a).any { it is LinkEvent.Joined })
        a.party.onFix(fix(31.7681, 35.1584, net.nowMs))
        net.run(1)
        val seen = b.party.members.getValue(0x0A0A0A0A).position
        assertNotNull(seen)
        assertEquals(here, seen!!.at)
    }

    @Test
    fun anSosIsSeenAndRepeatedOnTheRadioUntilThen() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "radio" to LORA)
        val b = net.phone("Dana", 0x0B0B0B0B, "radio" to LORA)
        net.run(2)
        net.act(a, a.party.sos(true, "Twisted ankle", net.nowMs))
        assertTrue(net.events(b).any { it is LinkEvent.SosRaised && it.text == "Twisted ankle" })
        net.run(11)
        assertTrue(net.events(a).any { it is LinkEvent.SosSeen && it.member.id == 0x0B0B0B0B })
        assertEquals(setOf(0x0B0B0B0B), a.party.sosSeenBy)
        // Seen: repeated every 10 minutes, not every minute.
        net.run(9 * 60)
        assertEquals(1, net.sentBy(a, LinkType.SOS).size)
        net.run(60)
        assertEquals(2, net.sentBy(a, LinkType.SOS).size)
    }

    @Test
    fun anSosNobodyGotIsRepeatedEveryMinute() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "radio" to LORA)
        net.act(a, a.party.sos(true, "Help", net.nowMs))
        net.run(3 * 60)
        assertEquals(4, net.sentBy(a, LinkType.SOS).size)
        net.act(a, a.party.sos(false, "", net.nowMs))
        net.run(3 * 60)
        assertEquals(5, net.sentBy(a, LinkType.SOS).size)
    }

    @Test
    fun oneMessageOverTwoConnectionsCountsOnce() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "mqtt" to ONLINE, "radio" to LORA)
        val b = net.phone("Dana", 0x0B0B0B0B, "mqtt" to ONLINE, "radio" to LORA)
        net.run(2)
        net.act(a, a.party.text("Water at the spring", net.nowMs))
        net.run(5)
        assertEquals(1, net.events(b).count { it is LinkEvent.TextReceived })
        assertEquals(setOf("mqtt", "radio"), b.party.members.getValue(0x0A0A0A0A).heard.keys)
        // Dana's phone was about to put it on the radio, and heard it there first.
        assertTrue(net.sentBy(b, LinkType.TEXT).isEmpty())
    }

    @Test
    fun aWrongClockIsDroppedAndSaidOnce() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "mqtt" to ONLINE)
        val ahead = LinkEnvelope.seal(LinkKey.of(partyKey), 0x0B0B0B0B, net.nowMs / 1000 + 3600, 1, LinkMessage.Text("From the future"))
        assertEquals(listOf<LinkEvent>(LinkEvent.ClockWrong(0x0B0B0B0B)), a.party.receive(ahead, "mqtt", net.nowMs).events)
        assertTrue(a.party.receive(ahead.copyOf().also { it[13] = 9 }, "mqtt", net.nowMs).events.isEmpty())
        assertTrue(a.party.chat.isEmpty())
        val now = LinkEnvelope.seal(LinkKey.of(partyKey), 0x0B0B0B0B, net.nowMs / 1000, 2, LinkMessage.Text("Fixed"))
        a.party.receive(now, "mqtt", net.nowMs)
        assertFalse(a.party.members.getValue(0x0B0B0B0B).clockWrong)
        assertEquals("Fixed", a.party.chat.single().text)
    }

    @Test
    fun aGatewayJoinsTheRadioAndTheInternet() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "radio" to LORA)
        val g = net.phone("Gil", 0x06060606, "radio" to LORA, "mqtt" to ONLINE)
        val c = net.phone("Carmel", 0x0C0C0C0C, "mqtt" to ONLINE)
        net.run(5)
        assertEquals("Adam", c.party.members[0x0A0A0A0A]?.name)
        assertEquals("Carmel", a.party.members[0x0C0C0C0C]?.name)
        net.act(c, c.party.text("Meet at the car park", net.nowMs))
        net.run(4)
        assertEquals("Meet at the car park", a.party.chat.single().text)
        // My relays coming back from the server aren't "heard online".
        assertEquals(setOf("radio"), g.party.members.getValue(0x0A0A0A0A).heard.keys)
        assertEquals(setOf("mqtt"), c.party.members.getValue(0x0A0A0A0A).heard.keys)
        // Carmel drives a winding road: many positions online, at most one a minute onto the radio.
        val start = net.sent.size
        var lat = 31.7
        var lon = 35.2
        net.run(180) { s ->
            val heading = if ((s / 15) % 2 == 0) 30.0 else 120.0
            lat += 14 * cos(heading * PI / 180) / 111_320
            lon += 14 * sin(heading * PI / 180) / (111_320 * cos(lat * PI / 180))
            c.party.onFix(fix(lat, lon, net.nowMs, kmh = 50.0, bearing = heading))
        }
        val online = net.sent.drop(start).count { (p, o) -> p === c && o.header.type == LinkType.POS }
        val relayed = net.sent.drop(start).count { (p, o) -> p === g && o.to == "radio" && o.header.type == LinkType.POS }
        assertTrue("$online online", online >= 10)
        assertTrue("$relayed relayed", relayed in 2..4)
        assertTrue(a.party.members.getValue(0x0C0C0C0C).position != null)
        assertTrue(net.sent.none { (_, o) -> o.to == "mqtt" && o.header.type in setOf(LinkType.DIGEST, LinkType.WANT) })
    }

    @Test
    fun marksTravelBetweenTheRadioAndTheInternet() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "radio" to LORA)
        val g = net.phone("Gil", 0x06060606, "radio" to LORA, "mqtt" to ONLINE)
        val c = net.phone("Carmel", 0x0C0C0C0C, "mqtt" to ONLINE)
        net.run(5)
        net.act(a, a.party.createMark(net.nowMs, Geom.Point(here), Style(icon = MarkIcon.WATER), MarkText("Spring")))
        val id = MarkId(0x0A0A0A0A, 1)
        assertNotNull(g.party.marks[id])
        net.run(6)
        assertEquals("Spring", c.party.marks[id]?.text?.value?.name)
        // Online, an edit goes as the mark's whole state, to its retained topic.
        net.act(c, c.party.editMark(id, net.nowMs, text = MarkText("Spring, dry in summer")))
        assertEquals(id, net.sent.last { (p, _) -> p === c }.second.snapshot)
        net.run(2)
        assertEquals("Spring, dry in summer", a.party.marks[id]?.text?.value?.name)
        assertEquals(0x0C0C0C0C, a.party.marks[id]?.lastEditor)
        // A long line drawn online reaches the radio in parts, from the gateway.
        val line = Geom.Path(false, wander(250, 80.0))
        net.act(c, c.party.createMark(net.nowMs, line, text = MarkText("Tomorrow")))
        net.run(3)
        val lineId = MarkId(0x0C0C0C0C, 1)
        assertEquals(line, a.party.marks[lineId]?.geom?.value)
        assertTrue(net.sent.filter { (p, o) -> p === g && o.to == "radio" }.all { it.second.bytes.size <= LinkEnvelope.LORA_MAX })
        assertEquals(a.party.marks.summary(), c.party.marks.summary())
        assertEquals(g.party.marks.summary(), c.party.marks.summary())
    }

    @Test
    fun aLatecomerCatchesUpOnTheRadio() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "radio" to LORA)
        val c = net.phone("Carmel", 0x0C0C0C0C, "radio" to LORA)
        net.run(2)
        repeat(6) { i -> net.act(a, a.party.createMark(net.nowMs, Geom.Point(GridPoint.of(31.7 + i * 0.01, 35.2)), text = MarkText("Mark $i"))) }
        assertEquals(a.party.marks.summary(), c.party.marks.summary())
        // Dana comes later, with nothing.
        val b = net.phone("Dana", 0x0B0B0B0B, "radio" to LORA)
        val before = net.sent.size
        net.run(90)
        assertEquals(a.party.marks.summary(), b.party.marks.summary())
        val after = net.sent.drop(before)
        val marksSent = after.count { (_, o) -> o.header.type == LinkType.MARK }
        val digestsFromAOrC = after.count { (p, o) -> p !== b && o.header.type == LinkType.DIGEST }
        // Adam and Carmel hold the same: one of them answers, not both.
        assertEquals(6, marksSent)
        assertEquals(1, digestsFromAOrC)
    }

    @Test
    fun aShapeMissingAPartIsAskedFor() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "radio" to LORA)
        val b = net.phone("Dana", 0x0B0B0B0B, "radio" to LORA)
        net.run(2)
        val r = a.party.createMark(net.nowMs, Geom.Path(false, wander(300, 70.0)))
        val parts = r.out.filter { it.to == "radio" }
        assertTrue(parts.size >= 3)
        // One part is lost on the air.
        net.send(a, parts.filterIndexed { i, _ -> i != 1 })
        val id = MarkId(0x0A0A0A0A, 1)
        assertNull(b.party.marks[id]?.geom)
        net.run(45)
        assertEquals(a.party.marks[id]?.geom?.value, b.party.marks[id]?.geom?.value)
        assertTrue(net.sentBy(b, LinkType.WANT).isNotEmpty())
    }

    @Test
    fun partyLiveReachesEveryoneAndEnds() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "mqtt" to ONLINE)
        val b = net.phone("Dana", 0x0B0B0B0B, "mqtt" to ONLINE)
        net.run(2)
        assertEquals(LinkMode.NORMAL, a.party.connections.single().mode?.mode)
        net.act(b, b.party.startLive(3600, net.nowMs))
        val live = a.party.live
        assertEquals(net.nowMs / 1000 + 3600, live?.until)
        assertEquals(0x0B0B0B0B, live?.by)
        assertTrue(net.events(a).any { it is LinkEvent.LiveChanged && it.live != null })
        net.run(1)
        assertEquals(LinkMode.LIVE, a.party.connections.single().mode?.mode)
        // Twelve hours at most, whatever a packet says.
        val long = LinkEnvelope.seal(LinkKey.of(partyKey), 0x0B0B0B0B, net.nowMs / 1000, 999, LinkMessage.Live(net.nowMs / 1000 + 86_400))
        a.party.receive(long, "mqtt", net.nowMs)
        assertEquals(net.nowMs / 1000 + LinkMessage.Live.MAX_SECONDS, a.party.live?.until)
        net.run(1)
        net.act(a, a.party.stopLive(net.nowMs))
        assertNull(b.party.live?.takeIf { it.until > net.nowMs / 1000 })
        assertTrue(net.events(b).any { it is LinkEvent.LiveChanged && it.live == null })
        // An older LIVE coming late changes nothing.
        val old = LinkEnvelope.seal(LinkKey.of(partyKey), 0x0B0B0B0B, net.nowMs / 1000 - 60, 1000, LinkMessage.Live(net.nowMs / 1000 + 600))
        b.party.receive(old, "mqtt", net.nowMs)
        assertEquals(0L, b.party.live?.until)
    }

    @Test
    fun anOldSnapshotIsAnsweredWithMine() {
        val net = Net()
        val a = net.phone("Adam", 0x0A0A0A0A, "mqtt" to ONLINE)
        val b = net.phone("Dana", 0x0B0B0B0B, "mqtt" to ONLINE)
        net.run(2)
        val created = a.party.createMark(net.nowMs, Geom.Point(here), text = MarkText("Camp"))
        val oldSnapshot = created.out.single().bytes
        net.act(a, created)
        net.run(1)
        net.act(a, a.party.editMark(MarkId(0x0A0A0A0A, 1), net.nowMs, text = MarkText("Camp 2")))
        // The server still holds the first state, and gives it to Dana as she reconnects.
        val before = net.sent.size
        net.events(b) += b.party.receive(oldSnapshot, "mqtt", net.nowMs, snapshot = true).events
        net.run(6)
        val answer = net.sent.drop(before).filter { (p, o) -> p === b && o.snapshot != null }
        assertEquals(1, answer.size)
        val state = LinkEnvelope.open(LinkKey.of(partyKey), answer.single().second.bytes)!!.message as LinkMessage.Mark
        assertEquals("Camp 2", state.text?.value?.name)
    }

    private fun fix(lat: Double, lon: Double, timeMs: Long, kmh: Double = 0.0, bearing: Double? = null) =
        GeoFix(lat, lon, 700.0, 4f, (kmh / 3.6).toFloat(), null, bearing?.toFloat(), timeMs, timeMs - t0, true)

    private fun wander(n: Int, stepM: Double, random: Random = Random(n)): List<GridPoint> {
        var lat = here.latDeg
        var lon = here.lonDeg
        var heading = 0.0
        return List(n) {
            heading += random.nextDouble(-0.6, 0.6)
            lat += stepM * cos(heading) / 111_320
            lon += stepM * sin(heading) / (111_320 * cos(lat * PI / 180))
            GridPoint.of(lat, lon)
        }.distinct()
    }
}
