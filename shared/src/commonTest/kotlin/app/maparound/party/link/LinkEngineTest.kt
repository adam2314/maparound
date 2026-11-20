package app.maparound.party.link

import app.maparound.party.PartyKey
import app.maparound.routing.Geo
import app.maparound.sensors.GeoFix
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

class LinkEngineTest {
    private val me = 0x1234ABCD
    private val dana = 0x0BADCAFE
    private val t0 = 1_795_000_000L
    private val start = GridPoint.of(31.7681, 35.1584)

    // Dead reckoning.

    @Test
    fun deadReckoningCarriesOnForTwoMinutes() {
        val pos = LinkMessage.Pos(start, speedKmh = 36, heading = 0)
        val after10 = DeadReckoning.predict(pos, t0, (t0 + 10) * 1000)
        assertTrue("${Geo.distance(start.latDeg, start.lonDeg, after10.lat, after10.lon)}", abs(Geo.distance(start.latDeg, start.lonDeg, after10.lat, after10.lon) - 100) < 0.5)
        assertTrue(after10.lat > start.latDeg)
        val after5min = DeadReckoning.predict(pos, t0, (t0 + 300) * 1000)
        assertTrue(abs(Geo.distance(start.latDeg, start.lonDeg, after5min.lat, after5min.lon) - 1200) < 5)
        val east = DeadReckoning.predict(pos.copy(heading = 64), t0, (t0 + 10) * 1000)
        assertTrue(east.lon > start.lonDeg && abs(east.lat - start.latDeg) < 1e-7)
        // Still, slow, or without a heading: nothing is predicted.
        for (p in listOf(pos.copy(speedKmh = 1), pos.copy(heading = null), pos.copy(speedKmh = null))) {
            val q = DeadReckoning.predict(p, t0, (t0 + 60) * 1000)
            assertEquals(start.latDeg, q.lat)
            assertEquals(start.lonDeg, q.lon)
        }
    }

    // Sending positions.

    /** A trip of fixes once a second: [legs] of (seconds, km/h, heading), from [start]. */
    private fun trip(vararg legs: Triple<Int, Double, Double>, accuracy: Float = 4f): List<GeoFix> {
        var lat = start.latDeg
        var lon = start.lonDeg
        var t = 0
        val out = ArrayList<GeoFix>()
        for ((seconds, kmh, heading) in legs) {
            repeat(seconds) {
                out += GeoFix(lat, lon, 700.0, accuracy, (kmh / 3.6).toFloat(), null, heading.toFloat(), (t0 + t) * 1000, t * 1000L, true)
                val d = kmh / 3.6
                lat += d * cos(heading * PI / 180) / 111_320
                lon += d * sin(heading * PI / 180) / (111_320 * cos(lat * PI / 180))
                t++
            }
        }
        return out
    }

    private data class Sent(val at: Int, val out: OutgoingPos, val reason: PositionSender.Reason)

    /** Feeds [fixes] to a sender on [kind], asking once a second; [ctx] per second. */
    private fun drive(
        kind: ConnectionKind, fixes: List<GeoFix>, seconds: Int = fixes.size,
        ctx: (Int) -> SendContext = { SendContext((t0 + it) * 1000, ModeInfo(LinkMode.NORMAL, true), ModeInfo(LinkMode.NORMAL, true)) },
    ): List<Sent> {
        val sender = PositionSender(kind)
        val sent = ArrayList<Sent>()
        for (s in 0 until seconds) {
            fixes.getOrNull(s)?.let(sender::onFix)
            sender.next(ctx(s))?.let { sent += Sent(s, it, sender.lastReason!!) }
        }
        return sent
    }

    @Test
    fun aSteadyDriveSendsOnlyHeartbeats() {
        val sent = drive(ConnectionKind.ONLINE, trip(Triple(300, 50.0, 0.0)))
        assertEquals(PositionSender.Reason.FIRST, sent[0].reason)
        assertTrue("${sent.map { it.reason }}", sent.drop(1).all { it.reason == PositionSender.Reason.HEARTBEAT })
        assertEquals(listOf(0, 60, 120, 180, 240), sent.map { it.at })
    }

    @Test
    fun aBendSendsAtOnce() {
        val sent = drive(ConnectionKind.ONLINE, trip(Triple(30, 50.0, 0.0), Triple(30, 50.0, 90.0)))
        val bend = sent.first { it.at > 0 }
        assertEquals(PositionSender.Reason.ERROR, bend.reason)
        assertTrue("${bend.at}", bend.at in 31..32)
    }

    @Test
    fun stoppingSendsAtOnce() {
        val sent = drive(ConnectionKind.ONLINE, trip(Triple(40, 5.0, 0.0), Triple(30, 0.0, 0.0)))
        val stop = sent.first { it.at > 0 }
        assertEquals(PositionSender.Reason.STOP_START, stop.reason)
        assertEquals(40, stop.at)
        assertEquals(0, stop.out.pos.speedKmh)
    }

    @Test
    fun withoutAFixAHeartbeatStillGoesAndAFixComingBackGoesAtOnce() {
        val fixes = trip(Triple(1, 0.0, 0.0))
        val back = trip(Triple(1, 0.0, 0.0)).single().copy(timeMs = (t0 + 700) * 1000)
        val sender = PositionSender(ConnectionKind.LORA)
        val normal = ModeInfo(LinkMode.NORMAL, true)
        fun ctx(s: Int) = SendContext((t0 + s) * 1000, normal, normal, batteryPct = 64)
        sender.onFix(fixes[0])
        assertNotNull(sender.next(ctx(0)))
        for (s in 1 until 600) assertNull("$s", sender.next(ctx(s)))
        val heartbeat = sender.next(ctx(600))!!
        assertEquals(PositionSender.Reason.HEARTBEAT, sender.lastReason)
        assertEquals(LinkMessage.Pos.NO_FIX, heartbeat.pos.accuracyM)
        assertFalse(heartbeat.pos.isGoodFix)
        assertEquals(64, heartbeat.pos.batteryPct)
        assertEquals(t0 + 600, heartbeat.time)
        sender.onFix(back)
        assertNotNull(sender.next(ctx(700)))
        assertEquals(PositionSender.Reason.FIX_BACK, sender.lastReason)
    }

    @Test
    fun aLivelierModeSendsAtOnceAndSaysSo() {
        val normal = ModeInfo(LinkMode.NORMAL, true)
        val live = ModeInfo(LinkMode.LIVE, true)
        val sent = drive(ConnectionKind.ONLINE, trip(Triple(30, 0.0, 0.0))) { s ->
            SendContext((t0 + s) * 1000, if (s >= 10) live else normal, normal)
        }
        val up = sent[1]
        assertEquals(10, up.at)
        assertEquals(PositionSender.Reason.MODE, up.reason)
        assertEquals(live, up.out.pos.mode)
        assertNull(sent[0].out.pos.mode)
    }

    @Test
    fun anSosOnTheRadioSendsEvery30Seconds() {
        val live = ModeInfo(LinkMode.LIVE, true)
        val sent = drive(ConnectionKind.LORA, trip(Triple(130, 0.0, 0.0))) { s ->
            SendContext((t0 + s) * 1000, live, live, sos = true)
        }
        assertEquals(listOf(0, 30, 60, 90, 120), sent.map { it.at })
        assertTrue(sent.all { it.out.pos.sos })
    }

    @Test
    fun theBudgetHoldsAPositionBackUntilThereIsRoom() {
        val sent = drive(ConnectionKind.ONLINE, trip(Triple(30, 50.0, 0.0), Triple(30, 50.0, 90.0))) { s ->
            SendContext((t0 + s) * 1000, ModeInfo(LinkMode.NORMAL, true), ModeInfo(LinkMode.NORMAL, true), canSend = s == 0 || s >= 40)
        }
        assertEquals(listOf(0, 40), sent.take(2).map { it.at })
    }

    @Test
    fun positionsOnTheRadioCarryTheirWayAndFit() {
        // A zigzag walk: a turn every 40 s, for 20 minutes.
        val legs = List(30) { Triple(40, 5.0, if (it % 2 == 0) 20.0 else 110.0) }.toTypedArray()
        val fixes = trip(*legs)
        val sent = drive(ConnectionKind.LORA, fixes)
        val key = LinkKey.of(PartyKey.create("Walk"))
        assertTrue("${sent.size}", sent.size >= 10)
        var prevTime = -1L
        for (s in sent) {
            val bytes = LinkEnvelope.seal(key, me, s.out.time, 1, s.out.pos)
            assertTrue("${bytes.size}", bytes.size <= ConnectionKind.LORA.positionMax)
            val trail = s.out.pos.trail
            assertTrue(trail.zipWithNext().all { (a, b) -> a.time > b.time })
            assertTrue(trail.all { it.time > prevTime && it.time < s.out.time })
            // Each point is on the way: within a few metres of the fix of that second.
            for (p in trail) {
                val f = fixes[(p.time - t0).toInt()]
                assertTrue(Geo.distance(p.at.latDeg, p.at.lonDeg, f.lat, f.lon) < 3)
            }
            prevTime = s.out.time
        }
        assertTrue("the turns are in the trails", sent.sumOf { it.out.pos.trail.size } >= 10)
    }

    // Auto.

    @Test
    fun autoFollowsTheRules() {
        val base = AutoInputs(nowS = t0, batteryPct = 60)
        fun target(i: AutoInputs, pinned: LinkMode? = null) = AutoMode.target(i, pinned)
        assertEquals(LinkMode.NORMAL, target(base))
        assertEquals(LinkMode.LIVE, target(base.copy(sosInParty = true)))
        assertEquals(LinkMode.LIVE, target(base.copy(moving = true, nearestMemberM = 250.0)))
        assertEquals(LinkMode.NORMAL, target(base.copy(moving = false, nearestMemberM = 250.0)))
        assertEquals(LinkMode.LIVE, target(base.copy(moving = true, meetingPointM = 900.0)))
        assertEquals(LinkMode.LIVE, target(base.copy(moving = true, charging = true)))
        assertEquals(LinkMode.NORMAL, target(base.copy(batteryPct = 25, moving = true, nearestMemberM = 10.0)))
        assertEquals(LinkMode.LIVE, target(base.copy(batteryPct = 25, charging = true, moving = true, nearestMemberM = 10.0)))
        assertEquals(LinkMode.SAVER, target(base.copy(batteryPct = 20)))
        assertEquals(LinkMode.SAVER, target(base.copy(stillForS = 600)))
        assertEquals(LinkMode.SAVER, target(base.copy(aloneForS = 1800)))
        assertEquals(LinkMode.NORMAL, target(base.copy(sosInParty = true, meshBusy = true)))
        assertEquals(LinkMode.SAVER, target(base.copy(meshBusy = true)))
        // My SOS, and party Live (unless the battery is low), override everything, a pin too.
        assertEquals(LinkMode.LIVE, target(base.copy(mySos = true, batteryPct = 5, meshBusy = true), LinkMode.SAVER))
        assertEquals(LinkMode.LIVE, target(base.copy(partyLiveUntil = t0 + 60), LinkMode.SAVER))
        assertEquals(LinkMode.NORMAL, target(base.copy(partyLiveUntil = t0 + 60, batteryPct = 15)))
        assertEquals(LinkMode.NORMAL, target(base.copy(partyLiveUntil = t0 + 60, meshBusy = true)))
        assertEquals(LinkMode.SAVER, target(base.copy(partyLiveUntil = t0), LinkMode.SAVER))
        assertEquals(LinkMode.SAVER, target(base.copy(sosInParty = true), LinkMode.SAVER))
    }

    @Test
    fun autoGoesUpAtOnceAndDownAfterFiveMinutes() {
        val auto = AutoMode()
        val calm = AutoInputs(nowS = t0, batteryPct = 80)
        assertEquals(ModeInfo(LinkMode.NORMAL, true), auto.update(calm))
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 10, sosInParty = true)).mode)
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 20)).mode)
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 20 + 299)).mode)
        // The reason came back meanwhile: the wait starts again once it's gone.
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 330, sosInParty = true)).mode)
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 340)).mode)
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 600)).mode)
        assertEquals(LinkMode.NORMAL, auto.update(calm.copy(nowS = t0 + 640)).mode)
        // A pin applies at once, and says it isn't Auto.
        auto.pinned = LinkMode.SAVER
        assertEquals(ModeInfo(LinkMode.SAVER, false), auto.update(calm.copy(nowS = t0 + 641)))
        assertEquals(LinkMode.LIVE, auto.update(calm.copy(nowS = t0 + 650, partyLiveUntil = t0 + 700)).mode)
        assertEquals(LinkMode.SAVER, auto.update(calm.copy(nowS = t0 + 701, partyLiveUntil = t0 + 700)).mode)
    }

    // Marks.

    private fun point(lat: Double, lon: Double) = Geom.Point(GridPoint.of(lat, lon))

    /** Delivers [m] to [book] as it would travel: sealed by [sender] and opened. */
    private fun deliver(book: MarkBook, m: LinkMessage.Mark, sender: Int, now: Long = t0, via: ConnectionId = "mqtt") =
        book.receive(travel(m, sender, now), via, now)

    private val key = LinkKey.of(PartyKey.create("Marks"))

    private fun travel(m: LinkMessage.Mark, sender: Int, now: Long): LinkMessage.Mark =
        LinkEnvelope.open(key, LinkEnvelope.seal(key, sender, now, 1, m))!!.message as LinkMessage.Mark

    @Test
    fun theClockNeverGoesBackAndIgnoresBrokenClocks() {
        val book = MarkBook(me)
        assertEquals(t0, book.version(t0))
        assertEquals(t0 + 1, book.version(t0))
        assertEquals(t0 + 2, book.version(t0 - 50))
        val far = LinkMessage.Mark(MarkId(dana, 1), geom = Reg(point(31.0, 35.0), t0 + 86_400, dana))
        book.receive(far, "mqtt", t0)
        assertEquals(t0 + 3, book.version(t0))
        val ahead = LinkMessage.Mark(MarkId(dana, 2), geom = Reg(point(31.0, 35.0), t0 + 500, dana))
        book.receive(ahead, "mqtt", t0)
        assertEquals(t0 + 501, book.version(t0))
    }

    @Test
    fun editsMergeRegisterByRegisterTheSameEverywhere() {
        val mine = MarkBook(me)
        val hers = MarkBook(dana)
        val created = mine.create(t0, point(31.77, 35.16), Style(icon = MarkIcon.CAMP), MarkText("Camp"))
        assertEquals(MarkId(me, 1), created.id)
        assertTrue(deliver(hers, created, me).changed)
        // At the same time: I move it, she renames it. Both changes stay, on both phones.
        val moved = mine.edit(created.id, t0 + 60, geom = point(31.771, 35.161))
        val renamed = hers.edit(created.id, t0 + 60, text = MarkText("Camp 2"))
        assertEquals(t0 + 60, moved.geom!!.version)
        deliver(hers, moved, me, t0 + 61)
        deliver(mine, renamed, dana, t0 + 61)
        assertEquals(mine.fingerprints(), hers.fingerprints())
        assertEquals("Camp 2", mine[created.id]!!.text!!.value.name)
        assertEquals(point(31.771, 35.161), hers[created.id]!!.geom!!.value)
        // Both rename it at the same version: the higher editor id wins, in any order.
        val a = mine.edit(created.id, t0 + 120, text = MarkText("Mine"))
        val b = hers.edit(created.id, t0 + 120, text = MarkText("Hers"))
        assertEquals(a.text!!.version, b.text!!.version)
        deliver(mine, b, dana, t0 + 121)
        deliver(hers, a, me, t0 + 121)
        val winner = if (me.toUInt() > dana.toUInt()) "Mine" else "Hers"
        assertEquals(winner, mine[created.id]!!.text!!.value.name)
        assertEquals(winner, hers[created.id]!!.text!!.value.name)
        assertEquals(mine.summary(), hers.summary())
        // Who changed it last, and that an old copy is behind.
        assertEquals(if (winner == "Mine") me else dana, mine[created.id]!!.lastEditor)
        assertTrue(hers.receive(created, "lora", t0 + 130).behind)
        assertFalse(hers.receive(hers[created.id]!!.full(), "lora", t0 + 130).behind)
    }

    @Test
    fun deletingAndBringingBack() {
        val mine = MarkBook(me)
        val hers = MarkBook(dana)
        val m = mine.create(t0, point(31.0, 35.0), text = MarkText("Hazard"))
        deliver(hers, m, me)
        val deleted = hers.delete(m.id, t0 + 10)
        val lateEdit = LinkMessage.Mark(m.id, text = Reg(MarkText("Hazard!"), t0 + 5, me))
        deliver(mine, deleted, dana, t0 + 11)
        deliver(mine, lateEdit, me, t0 + 11)
        assertFalse(mine[m.id]!!.isShown)
        assertTrue(mine[m.id]!!.full().alive!!.value == false)
        val back = mine.edit(m.id, t0 + 20, alive = true)
        deliver(hers, back, me, t0 + 21)
        assertTrue(hers[m.id]!!.isShown)
        assertEquals("Hazard!", mine[m.id]!!.text!!.value.name)
    }

    @Test
    fun theMeetingPoint() {
        val book = MarkBook(me)
        val set = book.setMeeting(t0, GridPoint.of(31.5, 35.5), "Spring")
        assertEquals(MarkId.MEETING, set.id)
        assertEquals(MarkIcon.FLAG, set.style!!.value.icon)
        assertNull(set.alive)
        book.clearMeeting(t0 + 10)
        assertFalse(book[MarkId.MEETING]!!.isShown)
        val again = book.setMeeting(t0 + 20, GridPoint.of(31.6, 35.6), "Car park")
        assertEquals(true, again.alive?.value)
        assertTrue(book[MarkId.MEETING]!!.isShown)
    }

    @Test
    fun marksFollowTheirConnections() {
        val book = MarkBook(me)
        val onRadio = LinkMessage.Mark(MarkId(dana, 1), geom = Reg(point(31.0, 35.0), t0, dana))
        val both = LinkMessage.Mark(MarkId(dana, 2), geom = Reg(point(31.1, 35.0), t0, dana))
        book.receive(onRadio, "lora:1", t0)
        book.receive(both, "lora:1", t0)
        book.receive(both, "mqtt:public", t0)
        val mine = book.create(t0, point(31.2, 35.0))
        fun shown(vararg visible: String) = book.shown { it in visible }.map { it.id }.toSet()
        assertEquals(setOf(onRadio.id, both.id, mine.id), shown("lora:1", "mqtt:public"))
        assertEquals(setOf(both.id, mine.id), shown("mqtt:public"))
        assertEquals(setOf(mine.id), shown())
    }

    @Test
    fun aShapeInPartsCountsOnceAllHaveCome() {
        val mine = MarkBook(me)
        val hers = MarkBook(dana)
        val line = Geom.Path(false, walk(300, 70.0))
        val m = mine.create(t0, line, Style(rgb = 0x1E88E5), MarkText("Tomorrow"))
        val parts = Marks.split(m, me, t0)
        assertTrue(parts.size >= 3)
        fun index(p: LinkMessage.Mark) = (p.geom?.value as? Geom.Path)?.part?.index
        val held = parts.filter { it.geom != null }[1]
        for (p in parts.shuffled(Random(3))) if (p !== held) deliver(hers, p, me, t0, "lora:1")
        assertNull(hers[m.id]!!.geom)
        val missing = hers.missingParts(t0 + 31).single()
        assertEquals(m.id, missing.id)
        assertEquals(1 shl index(held)!!, missing.missing)
        assertTrue(hers.missingParts(t0 + 5).isEmpty())
        // The missing part comes from someone else, cut from the same mark at another time.
        val again = Marks.split(mine[m.id]!!.full(), 0x55555555, t0 + 90_000)
        assertEquals(parts.map { it.geom }, again.map { it.geom })
        deliver(hers, again.first { index(it) == index(held) }, 0x55555555, t0 + 90_000, "mqtt")
        assertEquals(line, hers[m.id]!!.geom!!.value)
        assertEquals(mine.fingerprints(), hers.fingerprints())
        assertTrue(hers.missingParts(t0 + 100).isEmpty())
    }

    @Test
    fun catchingUpByDigests() {
        val mine = MarkBook(me)
        val hers = MarkBook(dana)
        val made = List(5) { mine.create(t0 + it, point(31.0 + it * 0.01, 35.0), text = MarkText("Mark $it")) }
        made.take(3).forEach { deliver(hers, it, me) }
        val herOwn = hers.create(t0 + 10, point(30.9, 35.0), text = MarkText("Her own"))
        val herEdit = hers.edit(made[2].id, t0 + 11, text = MarkText("Mark 2, moved on"))
        assertFalse(mine.summary() == hers.summary())
        // She hears my HELLO's summary differ, and compares my DIGEST pages with hers.
        val wants = ArrayList<WantEntry>()
        val toSend = ArrayList<MarkId>()
        for (page in mine.digest()) {
            val (want, send) = hers.compare(travel(page))
            want.forEach { wants += WantEntry.Fingerprint(it) }
            toSend += send
        }
        assertEquals(setOf(herOwn.id, herEdit.id), toSend.toSet())
        val answer = mine.wanted(LinkMessage.Want(wants))
        assertEquals(setOf(made[2].id, made[3].id, made[4].id), answer.map { it.id }.toSet())
        answer.forEach { deliver(hers, it, me, t0 + 20, "lora:1") }
        toSend.forEach { deliver(mine, hers[it]!!.full(), dana, t0 + 20, "lora:1") }
        assertEquals(mine.summary(), hers.summary())
        assertEquals("Mark 2, moved on", mine[made[2].id]!!.text!!.value.name)
    }

    private fun travel(page: LinkMessage.Digest): LinkMessage.Digest =
        LinkEnvelope.open(key, LinkEnvelope.seal(key, me, t0, 1, page))!!.message as LinkMessage.Digest

    @Test
    fun theSenderSaysWhenItsNextPositionIsDue() {
        val sender = PositionSender(ConnectionKind.ONLINE)
        val normal = ModeInfo(LinkMode.NORMAL, true)
        fun ctx(s: Int) = SendContext((t0 + s) * 1000, normal, normal)
        // Nothing before a fix: only a fix starts it.
        assertEquals(Long.MAX_VALUE, sender.nextDueMs(t0 * 1000, LinkMode.NORMAL, sos = false))
        val fixes = trip(Triple(30, 5.0, 0.0), Triple(30, 5.0, 90.0))
        sender.onFix(fixes[0])
        assertEquals(t0 * 1000, sender.nextDueMs(t0 * 1000, LinkMode.NORMAL, sos = false))
        assertNotNull(sender.next(ctx(0)))
        // Then the heartbeat: Normal online, 60 s; Saver, 5 minutes; an SOS, Live's 15 s.
        assertEquals((t0 + 60) * 1000, sender.nextDueMs((t0 + 1) * 1000, LinkMode.NORMAL, sos = false))
        assertEquals((t0 + 300) * 1000, sender.nextDueMs((t0 + 1) * 1000, LinkMode.SAVER, sos = false))
        assertEquals((t0 + 15) * 1000, sender.nextDueMs((t0 + 1) * 1000, LinkMode.NORMAL, sos = true))
        // A position due but too soon after the last waits for the gap (5 s), not for a fix.
        val sent = sender.next(ctx(1))
        assertNull(sent)
        sender.onFix(fixes[1].copy(lat = fixes[1].lat + 0.001))
        assertNull(sender.next(ctx(2)))
        assertEquals((t0 + 5) * 1000, sender.nextDueMs((t0 + 2) * 1000, LinkMode.NORMAL, sos = false))
        assertNotNull(sender.next(ctx(5)))
    }

    // The radio's airtime.

    @Test
    fun theRadioSaysWhenItsNextPacketCanGo() {
        val budget = AirtimeBudget()
        assertEquals(0L, budget.nextMs(0))
        repeat(3) { budget.take(0) }
        assertEquals(20_000L, budget.nextMs(0))
        assertEquals(20_000L, budget.nextMs(5_000))
        val queue = RadioQueue(AirtimeBudget())
        assertNull(queue.nextMs(0))
        fun seal(m: LinkMessage) = LinkEnvelope.seal(key, me, t0, Random.nextInt(65_536), m)
        repeat(4) { queue.add(seal(LinkMessage.Text("Message $it"))) }
        while (queue.poll(0) != null) Unit
        assertEquals(20_000L, queue.nextMs(0))
        // An SOS goes outside the budget: now.
        queue.add(seal(LinkMessage.Sos(true, "Help")))
        assertEquals(0L, queue.nextMs(0))
    }

    @Test
    fun theBudgetAllowsBurstsThenOneEvery20Seconds() {
        val budget = AirtimeBudget()
        repeat(3) { assertTrue(budget.take(0)) }
        assertFalse(budget.take(0))
        assertFalse(budget.take(19_000))
        assertTrue(budget.take(20_000))
        assertFalse(budget.take(20_000))
        assertTrue(budget.available(80_000))
        repeat(3) { assertTrue(budget.take(200_000)) }
    }

    @Test
    fun theRadioQueueSendsUrgentFirstAndReplacesOldPositions() {
        val queue = RadioQueue(AirtimeBudget())
        fun seal(m: LinkMessage, sender: Int = me) = LinkEnvelope.seal(key, sender, t0, Random.nextInt(65_536), m)
        val oldPos = seal(LinkMessage.Pos(start))
        val newPos = seal(LinkMessage.Pos(GridPoint.of(31.8, 35.2)))
        val herPos = seal(LinkMessage.Pos(start), dana)
        val hello = seal(LinkMessage.Hello("Me", 0x123456))
        val mark = seal(LinkMessage.Mark(MarkId(me, 1), alive = Reg(false, t0, me)))
        val answer = seal(LinkMessage.Mark(MarkId(me, 2), alive = Reg(false, t0, me)))
        val sos = seal(LinkMessage.Sos(true, "Help"))
        val ack = seal(LinkMessage.Ack(listOf(PacketRef(dana, t0, 1))))
        listOf(oldPos, hello, herPos, newPos).forEach { assertTrue(queue.add(it)) }
        assertTrue(queue.add(answer, bulk = true))
        assertTrue(queue.add(mark))
        assertTrue(queue.add(sos))
        assertFalse(queue.add(byteArrayOf(0x4D, 0xA1.toByte(), 1, 2)))
        assertEquals(6, queue.size) // my older position was replaced
        val order = generateSequence { queue.poll(0) }.toList()
        assertEquals(listOf(sos, hello, mark, herPos).map { it.toList() }, order.map { it.toList() })
        // The budget is spent: an ACK still goes, the rest waits.
        queue.add(ack)
        assertEquals(ack.toList(), queue.poll(0)!!.toList())
        assertNull(queue.poll(1000))
        assertEquals(newPos.toList(), queue.poll(20_000)!!.toList())
        assertEquals(answer.toList(), queue.poll(40_000)!!.toList())
    }

    /** A wandering line of [n] vertices about [stepM] apart. */
    private fun walk(n: Int, stepM: Double, random: Random = Random(n)): List<GridPoint> {
        var lat = start.latDeg
        var lon = start.lonDeg
        var heading = 0.0
        return List(n) {
            heading += random.nextDouble(-0.6, 0.6)
            lat += stepM * cos(heading) / 111_320
            lon += stepM * sin(heading) / (111_320 * cos(lat * PI / 180))
            GridPoint.of(lat, lon)
        }.distinct()
    }
}
