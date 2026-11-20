package app.maparound.party.link

import app.maparound.data.MemoryStore
import app.maparound.party.Mesh
import app.maparound.party.MeshRadio
import app.maparound.party.PartyController
import app.maparound.party.PartyEvent
import app.maparound.party.PartyKey
import app.maparound.party.PartyLayers
import app.maparound.party.PartyRadio
import app.maparound.party.TransportStatus
import app.maparound.party.Via
import app.maparound.sensors.GeoFix
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The party as the screens drive it, on Link v1: phones with their own kept settings, on a fake
 * broker (FakeNet.kt) and fake radios. Each runs as Android runs it, in a "service" that attaches
 * and ticks it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkSessionTest {
    private val t0 = 1_795_000_000_000L
    private val public = "wss://broker.example/mqtt"
    private val connected = TransportStatus.State.CONNECTED
    private val off = TransportStatus.State.OFF

    /** Radios that hear each other. */
    private class Air(val loop: FakeLoop) {
        val on = ArrayList<Radio>()

        inner class Radio(val listener: MeshRadio.Listener) : PartyRadio {
            override fun start() {
                on += this
                loop.post { listener.onStatus(TransportStatus(TransportStatus.State.CONNECTED, "T-Echo")) }
            }

            override fun stop() {
                on -= this
                listener.onStatus(TransportStatus(TransportStatus.State.OFF))
            }

            override fun send(payload: ByteArray) {
                val bytes = payload.copyOf()
                loop.post { on.filter { it !== this }.forEach { it.listener.onPartyPacket(bytes) } }
            }
        }

        /** A plain Meshtastic radio's position, heard by all. */
        fun node(num: Long, name: String, at: Mesh.Position) = loop.post { on.toList().forEach { it.listener.onNode(num, name, name.take(4), at) } }
    }

    private inner class Net(val test: TestScope) {
        var nowMs = t0
        val loop = FakeLoop()
        val internet = FakeInternet(loop).also { it.broker(public) }
        val air = Air(loop)
        val devices = ArrayList<Device>()

        fun device(name: String) = Device(name, this).also { devices += it }

        fun step(seconds: Int = 1) = repeat(seconds) {
            nowMs += 1000
            devices.forEach { it.session.tick() }
            loop.drain()
        }

        fun drain() = loop.drain()
    }

    private inner class Device(val name: String, val net: Net) {
        val store = MemoryStore()
        var online = true
        val events = ArrayList<PartyEvent>()
        lateinit var session: LinkSession

        init {
            open()
        }

        /** The app (re)started: a session from what's kept. */
        fun open() {
            if (::session.isInitialized) session.detach()
            session = LinkSession(
                store, net.internet.sockets(name) { online },
                radios = { _, _, listener -> net.air.Radio(listener) },
                publicServers = listOf(public),
                battery = { 80 },
                clock = { net.nowMs },
                // As Android's service: started, it attaches the session (and ticks it: Net.step).
                service = { on -> if (on) session.attach() },
                scope = net.test.backgroundScope,
            )
            val s = session
            net.test.backgroundScope.launch(UnconfinedTestDispatcher(net.test.testScheduler)) { s.events.collect { events += it } }
        }

        val state get() = session.state.value

        fun fix(lat: Double, lon: Double) =
            session.onFix(GeoFix(lat, lon, 700.0, 4f, 0f, null, null, net.nowMs, net.nowMs - t0, true))

        fun joinOf(other: Device, myName: String) = session.join(PartyKey.parse(other.state.party!!.toLink())!!, myName)
    }

    @Test
    fun twoPhonesInAParty() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        a.session.create("Hike", "Dana")
        net.drain()
        b.joinOf(a, "Ido")
        net.step(2)
        assertEquals(listOf("Ido"), a.state.members.map { it.name })
        assertEquals(listOf("Dana"), b.state.members.map { it.name })
        assertEquals(TransportStatus(connected, "broker.example"), a.state.online)
        assertTrue(a.events.any { it is PartyEvent.Joined && it.member.name == "Ido" })
        // Positions.
        a.fix(31.7681, 35.1584)
        net.step(2)
        val seen = b.state.members.single().position!!
        assertEquals(31.7681, seen.lat, 1e-4)
        assertEquals(35.1584, seen.lon, 1e-4)
        assertEquals(setOf(Via.ONLINE), b.state.members.single().heard.keys)
        // Messages, mine shown too.
        b.session.sendText("Water at the spring")
        net.drain()
        assertEquals("Water at the spring", a.state.chat.single().text)
        assertEquals("Water at the spring", b.state.chat.single().text)
        assertTrue(a.events.any { it is PartyEvent.TextReceived && it.text == "Water at the spring" })
        // The meeting point: announced to the others, not to me.
        a.session.setMeeting(31.77, 35.16, "Car park")
        net.drain()
        assertEquals("Car park", b.state.meeting?.label)
        assertEquals("Car park", a.state.meeting?.label)
        assertTrue(b.events.any { it is PartyEvent.MeetingSet && it.meeting?.label == "Car park" })
        assertTrue(a.events.none { it is PartyEvent.MeetingSet })
        a.session.clearMeeting()
        net.drain()
        assertNull(b.state.meeting)
        // An SOS.
        a.session.sos(true, "Ankle")
        net.drain()
        assertTrue(a.state.mySos)
        assertTrue(b.state.members.single().sos)
        assertTrue(b.events.any { it is PartyEvent.SosRaised && it.text == "Ankle" })
        // Ido leaves.
        b.session.leave()
        net.drain()
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(b.state.party)
        assertNull(b.store.getString("link"))
        assertTrue(a.events.any { it is PartyEvent.Left && it.member.name == "Ido" })
        assertTrue(a.state.members.isEmpty())
    }

    @Test
    fun aRestartCarriesOn() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        a.session.create("Hike", "Dana")
        b.joinOf(a, "Ido")
        net.step(2)
        a.session.setMeeting(31.77, 35.16, "Car park")
        a.session.party!!.createMark(net.nowMs, Geom.Point(GridPoint.of(31.7, 35.2)))
        repeat(20) { a.session.sendText("Message $it") }
        net.step(1)
        val id = a.state.me!!.id
        val seq = a.session.party!!.seq
        // The app closes and opens again.
        a.open()
        a.session.resumeIfWasActive()
        net.step(2)
        assertEquals(id, a.state.me!!.id)
        assertEquals("Dana", a.state.me!!.name)
        // Its sequence numbers start past those used before, and its marks' counter where it was.
        val gap = (a.session.party!!.seq - seq) and 0xFFFF
        assertTrue("$gap", gap in 1..1_100)
        assertEquals(2L, a.session.party!!.marks.nextCounter)
        // The server gave back the meeting point; to Ido, Dana is still one member.
        assertEquals("Car park", a.state.meeting?.label)
        assertEquals(listOf("Ido"), a.state.members.map { it.name })
        assertEquals(listOf("Dana"), b.state.members.map { it.name })
    }

    @Test
    fun theRadioCarriesThePartyOffline() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        for ((d, radio) in listOf(a to "AA:01", b to "BB:02")) {
            d.session.setOnlineEnabled(false)
            d.session.setRadio(radio, "T-Echo")
        }
        a.session.create("Off grid", "Dana")
        b.joinOf(a, "Ido")
        net.step(3)
        assertEquals(connected, a.state.radio.state)
        assertEquals(off, a.state.online.state)
        assertEquals("T-Echo", a.state.radioName)
        assertEquals(listOf("Ido"), a.state.members.map { it.name })
        assertEquals(setOf(Via.LORA), a.state.members.single().heard.keys)
        // A tracker in the car, heard on the radio.
        net.air.node(0x1234, "Car tracker", Mesh.Position(31.7, 35.2, 700, null))
        net.drain()
        assertEquals("Car tracker", a.state.meshNodes.single().name)
        a.session.forgetRadio()
        assertEquals(off, a.state.radio.state)
        assertNull(a.state.radioName)
    }

    @Test
    fun itTicksWhenSomethingIsDueAndFixesFollowTheMode() = runTest {
        // Real time here (virtual): the session ticks itself, as on the phone.
        val clock = { t0 + testScheduler.currentTime }
        val loop = FakeLoop()
        val internet = FakeInternet(loop).also { it.broker(public) }
        lateinit var s: LinkSession
        s = LinkSession(
            MemoryStore(), internet.sockets("A"), publicServers = listOf(public), battery = { 80 }, clock = clock,
            service = { on -> if (on) s.attach() }, scope = backgroundScope,
        )
        backgroundScope.launch {
            while (true) {
                loop.drain()
                delay(100)
            }
        }
        s.create("Hike", "Dana")
        // Idle for 10 minutes: about every 30 s (and for the server's pings), not every second.
        advanceTimeBy(10 * 60_000L)
        assertEquals(connected, s.state.value.online.state)
        assertTrue("${s.ticks}", s.ticks in 15..60)
        assertEquals(LinkSession.FIX_STILL_MS, s.fixEveryMs.value)
        // Walking, a fix every 5 s: each is looked at as it comes, and fixes come as often as Normal online needs.
        val before = s.ticks
        var lat = 31.7
        backgroundScope.launch {
            repeat(60) {
                lat += 0.00006
                s.onFix(GeoFix(lat, 35.2, 700.0, 4f, 1.4f, null, 0f, clock(), 0, true))
                delay(5_000)
            }
        }
        advanceTimeBy(5 * 60_000L)
        assertTrue("${s.ticks - before}", s.ticks - before in 60..130)
        assertEquals(5_000L, s.fixEveryMs.value)
        // An SOS: as often as can be. Hidden: no fixes at all.
        s.sos(true, "Help")
        assertEquals(LinkSession.FIX_FAST_MS, s.fixEveryMs.value)
        s.sos(false)
        s.setSharing(false)
        assertNull(s.fixEveryMs.value)
    }

    @Test
    fun onlineGoesOffAndOnAndMovesToTheClubsServer() = runTest {
        val net = Net(this)
        val club = "wss://club.example/mqtt"
        net.internet.broker(club)
        val a = net.device("A")
        a.session.create("Hike", "Dana")
        net.step(1)
        assertEquals(connected, a.state.online.state)
        a.session.setOnlineEnabled(false)
        net.drain()
        assertEquals(off, a.state.online.state)
        assertTrue(net.internet.broker(public).clients.isEmpty())
        a.session.setOnlineEnabled(true)
        net.step(1)
        assertEquals(connected, a.state.online.state)
        a.session.setServer(club)
        net.step(1)
        assertEquals(TransportStatus(connected, "club.example"), a.state.online)
        assertEquals(listOf(club), a.state.party!!.servers)
        assertEquals(club, a.session.defaultServer)
        assertTrue(net.internet.broker(public).clients.isEmpty())
        assertEquals(1, net.internet.broker(club).clients.size)
        // The club's own server knows Dana's id by her token; the public ones never got one.
        val credential = net.internet.broker(club).connects.last().second!!
        assertEquals(a.session.party!!.credential(), credential)
        assertTrue(credential, Regex("[0-9a-f]{4}[.][0-9a-f]{8}[.][A-Za-z0-9_-]{22}").matches(credential))
        assertTrue(net.internet.broker(public).connects.all { it.second == null })
        // The token stays with the party: the same after a restart.
        val token = a.session.party!!.token
        a.open()
        assertEquals(token, a.session.party!!.token)
    }

    @Test
    fun onePhoneInTwoParties() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        val c = net.device("C")
        b.session.create("Hike", "Ido")
        c.session.create("Club run", "Noa")
        net.drain()
        a.joinOf(b, "Dana")
        a.joinOf(c, "Dana")
        net.step(2)
        // Both at once, on one connection to the public server; the one joined last is shown.
        assertEquals(listOf("Hike", "Club run"), a.session.parties.value.map { it.party!!.name })
        assertEquals("Club run", a.state.party!!.name)
        assertEquals(1, net.internet.broker(public).clients.count { it.owner == "A" })
        assertEquals(listOf("Dana"), b.state.members.map { it.name })
        assertEquals(listOf("Dana"), c.state.members.map { it.name })
        // A different id in each: nothing links them.
        val ids = a.session.parties.value.map { it.me!!.id }
        assertTrue(ids[0] != ids[1])
        assertEquals(ids[0], b.state.members.single().id)
        assertEquals(ids[1], c.state.members.single().id)
        // Each party's messages stay in it, and say whose they are.
        b.session.sendText("Water at the spring")
        net.drain()
        assertEquals(listOf("Water at the spring"), a.session.parties.value[0].chat.map { it.text })
        assertTrue(a.state.chat.isEmpty())
        assertTrue(a.events.any { it is PartyEvent.TextReceived && it.party?.name == "Hike" })
        // The other one shown: my actions go to it.
        a.session.select(a.session.parties.value[0].party!!)
        assertEquals("Hike", a.state.party!!.name)
        a.session.sendText("On my way")
        net.drain()
        assertEquals("On my way", b.state.chat.last().text)
        assertTrue(c.state.chat.isEmpty())
        // An SOS goes to both, and its end.
        a.session.sos(true, "Ankle")
        net.drain()
        assertTrue(b.state.members.single().sos)
        assertTrue(c.state.members.single().sos)
        a.session.sos(false)
        net.drain()
        assertFalse(b.state.members.single().sos)
        assertFalse(c.state.members.single().sos)
        // The app closes and opens again: both back, with the same ids, the same one shown.
        a.open()
        a.session.resumeIfWasActive()
        net.step(2)
        assertEquals(ids, a.session.parties.value.map { it.me!!.id })
        assertEquals("Hike", a.state.party!!.name)
        assertTrue(a.session.parties.value.all { it.active })
        // Leaving the shown one: the other carries on, and is shown.
        a.session.leave()
        net.drain()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("Club run"), a.session.parties.value.map { it.party!!.name })
        assertEquals("Club run", a.state.party!!.name)
        assertTrue(b.state.members.isEmpty())
        assertEquals(listOf("Dana"), c.state.members.map { it.name })
        assertEquals(connected, a.state.online.state)
        // Off in one, on in the other: still connected for it.
        a.joinOf(b, "Dana")
        net.step(1)
        a.session.setActive(false)
        net.step(1)
        assertFalse(a.state.active)
        assertTrue(a.session.parties.value.single { it.party!!.name == "Club run" }.active)
        assertEquals(1, net.internet.broker(public).clients.count { it.owner == "A" })
    }

    @Test
    fun twoPartiesOnTheClubsServerShareAConnectionAndBothAreKnown() = runTest {
        val net = Net(this)
        val club = "wss://club.example/mqtt"
        net.internet.broker(club)
        val a = net.device("A")
        a.session.setServer(club)
        a.session.create("Hike", "Dana")
        a.session.create("Club run", "Dana")
        net.step(2)
        val broker = net.internet.broker(club)
        assertEquals(1, broker.clients.count { it.owner == "A" })
        // One connection, both parties' ids and tokens on it.
        val credentials = broker.connects.last().second!!.split(',').toSet()
        assertEquals(a.session.parties.value.size, credentials.size)
        assertTrue(a.state.online.state == connected)
        // Moving the shown one to the public servers: the club keeps the other.
        a.session.setServer(null)
        net.step(2)
        // (Its connection doesn't reconnect only to drop the moved party's id: the server knew it already.)
        assertEquals(1, broker.clients.single { it.owner == "A" }.subs.size)
        assertEquals(TransportStatus(connected, "broker.example"), a.state.online)
    }

    @Test
    fun atMostFourPartiesAndTheSameInviteTwiceIsOne() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        b.session.create("Hike", "Ido")
        a.joinOf(b, "Dana")
        a.joinOf(b, "Dana")
        assertEquals(1, a.session.parties.value.size)
        repeat(5) { a.session.create("Party $it", "Dana") }
        assertEquals(PartyController.MAX_PARTIES, a.session.parties.value.size)
        assertEquals("Party 2", a.state.party!!.name)
    }

    @Test
    fun theOnePartyKeptBeforeBecomesTheFirst() = runTest {
        val net = Net(this)
        val key = PartyKey.create("Hike", null)
        val a = net.device("A")
        // As the versions with one party kept it.
        a.store.putString("link", key.toLink())
        a.store.putInt("my_id", 0x1234ABCD)
        a.store.putString("link_seq", "4000")
        a.store.putString("link_token", "tokenOfTheOldVersion1")
        a.store.putBoolean("sharing", false)
        a.store.putBoolean("active", true)
        a.store.remove("parties")
        a.open()
        assertEquals("Hike", a.state.party!!.name)
        assertEquals(0x1234ABCD, a.state.me!!.id)
        assertFalse(a.state.sharing)
        assertEquals("tokenOfTheOldVersion1", a.session.party!!.token)
        assertEquals(4000, a.session.party!!.seq)
        assertNull(a.store.getString("link"))
        a.session.resumeIfWasActive()
        net.step(1)
        assertTrue(a.state.active)
    }

    @Test
    fun eachPartyHasItsColourAndLayersAndTheConnectionsAreListed() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        b.session.create("Hike", "Ido")
        a.joinOf(b, "Dana")
        a.session.create("Club run", "Dana")
        net.step(2)
        val (hike, run) = a.session.parties.value
        assertTrue(hike.color != run.color)
        // One connection for both, and who came through it.
        val conn = a.state.connections.single()
        assertEquals(LinkSession.PUBLIC, conn.id)
        assertEquals(1, conn.members)
        assertEquals(connected, conn.status.state)
        // What the map shows is kept.
        a.session.setConnectionShown(LinkSession.PUBLIC, false)
        a.session.setLayers(hike.party!!, PartyLayers(members = false))
        a.session.setNodesShown(false)
        a.open()
        assertEquals(setOf(LinkSession.PUBLIC), a.state.hiddenConnections)
        assertFalse(a.state.nodesShown)
        assertEquals(PartyLayers(members = false), a.session.parties.value.first().layers)
        assertEquals(PartyLayers(), a.session.parties.value.last().layers)
        assertEquals(listOf(hike.color, run.color), a.session.parties.value.map { it.color })
    }

    @Test
    fun theTrailsLayerHasTheLastHalfHour() = runTest {
        val net = Net(this)
        val a = net.device("A")
        val b = net.device("B")
        a.session.create("Hike", "Dana")
        b.joinOf(a, "Ido")
        net.step(2)
        // Dana walks north for 40 minutes, about 110 m a minute.
        var lat = 31.7
        repeat(40) {
            lat += 0.001
            a.fix(lat, 35.2)
            net.step(60)
        }
        val way = b.state.recentWays.getValue(a.state.me!!.id)
        assertTrue("${way.size}", way.size in 25..35)
        assertTrue(way.first().at >= net.nowMs - 30 * 60_000)
        assertEquals(lat, way.last().lat, 1e-4)
        assertTrue(way.zipWithNext().all { (x, y) -> x.at < y.at })
    }
}
