package app.maparound.party.link

import app.maparound.party.PartyKey
import app.maparound.party.TransportStatus
import app.maparound.sensors.GeoFix
import app.maparound.testing.assertEquals
import app.maparound.testing.assertFalse
import app.maparound.testing.assertNotNull
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.random.Random
import kotlin.test.Test

/**
 * The hub on a fake network (FakeNet.kt): MQTT brokers that speak enough MQTT 3.1.1 (retained messages,
 * wildcards, a publisher getting its own messages back), and a LoRa mesh where every other radio
 * hears what one sends. Callbacks come later, from one event loop, as on the platforms.
 */
class LinkHubTest {
    private val partyKey = PartyKey.create("Trip")
    private val t0 = 1_795_000_000_000L
    private val here = GridPoint.of(31.7681, 35.1584)
    private val club = "wss://club.example/mqtt"

    // The network (FakeNet.kt).

    private inner class World {
        var nowMs = t0
        val loop = FakeLoop()
        val internet = FakeInternet(loop)
        val phones = ArrayList<Phone>()
        val tickers = ArrayList<() -> Unit>()

        fun broker(url: String) = internet.broker(url)

        fun sockets(owner: String, online: () -> Boolean = { true }) = internet.sockets(owner, online)

        fun phone(name: String, id: Int, key: PartyKey = partyKey): Phone = Phone(name, this).also {
            it.party = it.party(key, id)
            phones += it
        }

        fun step(seconds: Int = 1, each: (Int) -> Unit = {}) = repeat(seconds) { s ->
            nowMs += 1000
            each(s)
            phones.forEach { it.hub.tick(batteryPct = 80) }
            tickers.forEach { it() }
            loop.drain()
        }
    }

    private inner class Phone(val name: String, val world: World) : LinkHub.Listener {
        var online = true
        val events = ArrayList<Pair<LinkParty, LinkEvent>>()
        val statuses = HashMap<ConnectionId, TransportStatus>()
        val hub = LinkHub(world.sockets(name) { online }, this) { world.nowMs }
        lateinit var party: LinkParty

        fun party(key: PartyKey, id: Int) = LinkParty(key, id, LinkProfile(name, 0x2E7D32), random = Random(id))

        override fun onEvent(party: LinkParty, event: LinkEvent) {
            events += party to event
        }

        override fun onStatus(connection: ConnectionId, status: TransportStatus) {
            statuses[connection] = status
        }

        /** Online again: the platform tells the hub. */
        fun back() {
            online = true
            hub.networkBack()
        }

        fun server(id: ConnectionId, vararg urls: String) = apply { hub.addServer(id, urls.toList()) }

        fun radio(id: ConnectionId, mesh: FakeMesh) = apply {
            hub.addRadio(id, mesh.radio(hub, id))
            hub.radioStatus(id, TransportStatus(TransportStatus.State.CONNECTED, id))
        }

        fun join(vararg on: ConnectionId, p: LinkParty = party) = apply {
            hub.addParty(p, on.toList())
            world.loop.drain()
        }

        fun act(p: LinkParty = party, f: LinkParty.(Long) -> LinkResult) {
            hub.send(p, p.f(world.nowMs))
            world.loop.drain()
        }

        fun eventsOf(p: LinkParty = party) = events.filter { it.first === p }.map { it.second }

        fun fix(lat: Double, lon: Double) =
            party.onFix(GeoFix(lat, lon, 700.0, 4f, 0f, null, null, world.nowMs, world.nowMs - t0, true))
    }

    private val tagHex = LinkKey.of(partyKey).tagHex
    private fun topic(rest: String) = "maparound/link1/$tagHex/$rest"

    // The topics.

    @Test
    fun eachPacketHasItsTopic() {
        val key = LinkKey.of(partyKey)
        fun header(m: LinkMessage) = LinkEnvelope.header(LinkEnvelope.seal(key, 0x0A0B0C0D, t0 / 1000, 1, m))!!
        val pos = LinkMessage.Pos(here, accuracyM = 5)
        assertEquals(LinkTopic(topic("0a0b0c0d/pos"), true), LinkTopics.of(tagHex, header(pos)))
        assertEquals(LinkTopic(topic("0a0b0c0d/hello"), true), LinkTopics.of(tagHex, header(LinkMessage.Hello("Adam", 0, 0, ModeInfo(LinkMode.NORMAL, true)))))
        assertEquals(LinkTopic(topic("0a0b0c0d/sos"), true), LinkTopics.of(tagHex, header(LinkMessage.Sos(true, "Help"))))
        assertEquals(LinkTopic(topic("0a0b0c0d/text"), false), LinkTopics.of(tagHex, header(LinkMessage.Text("Hi"))))
        assertEquals(LinkTopic(topic("0a0b0c0d/leave"), false), LinkTopics.of(tagHex, header(LinkMessage.Leave)))
        assertEquals(LinkTopic(topic("live"), true), LinkTopics.of(tagHex, header(LinkMessage.Live(0))))
        val mark = LinkMessage.Mark(MarkId(0xF0000001.toInt(), 7), Reg(Geom.Point(here), t0 / 1000, 0))
        assertEquals(LinkTopic(topic("o/f0000001-7"), true), LinkTopics.of(tagHex, header(mark), snapshot = mark.id))
        assertEquals(LinkTopic(topic("0a0b0c0d/mark"), false), LinkTopics.of(tagHex, header(mark)))
        assertTrue(LinkTopics.isRelayPost(topic("0a0b0c0d/mark")))
        assertFalse(LinkTopics.isRelayPost(topic("o/f0000001-7")))
        assertNull(LinkTopics.of(tagHex, header(LinkMessage.Want(listOf(WantEntry.Fingerprint(1))))))
        assertEquals(listOf(topic("0a0b0c0d/pos"), topic("0a0b0c0d/hello"), topic("0a0b0c0d/sos")), LinkTopics.cleared(tagHex, 0x0A0B0C0D))
        assertEquals("maparound/link1/$tagHex/#", LinkTopics.all(tagHex))
    }

    @Test
    fun anInviteCarriesSeveralServers() {
        val key = partyKey.withServers(listOf(club, "wss://backup.example:8084/mqtt"))
        val back = PartyKey.parse(key.toLink())!!
        assertEquals(key.servers, back.servers)
        assertEquals(club, back.server)
        assertEquals(key, back)
        // Addresses that aren't secure WebSockets are dropped; with none left, the public servers.
        val code = key.toLink().substringAfter('#').substringBefore('?')
        assertEquals(listOf(club), PartyKey.parse("maparound://party/$code?n=X&s=http%3A%2F%2Fevil.example&s=${club.replace(":", "%3A").replace("/", "%2F")}")!!.servers)
        assertTrue(PartyKey.parse("maparound://party/$code?n=X&s=ftp%3A%2F%2Fx")!!.servers.isEmpty())
    }

    // One server connection.

    @Test
    fun aServerIsPingedOnlyWhenIdle() {
        val w = World()
        val b = w.broker(club)
        val mqtt = LinkMqtt(
            "club", listOf(club), w.sockets("solo"), { w.nowMs },
            object : LinkMqtt.Listener {
                override fun onSubscribed(filter: String) = Unit
                override fun onDown() = Unit
                override fun onMessage(topic: String, payload: ByteArray, retained: Boolean) = Unit
            },
        )
        w.tickers += mqtt::tick
        mqtt.start()
        w.loop.drain()
        // Positions every 10 s for 3 minutes keep it alive: no pings.
        w.step(180) { s -> if (s % 10 == 0) mqtt.publish("x/pos", byteArrayOf(1), retained = true) }
        assertEquals(0, b.pings)
        // Idle: one every 50 s, and the next is what it waits for.
        w.step(110)
        assertEquals(2, b.pings)
        assertEquals(31_000L, mqtt.nextDueMs()!! - w.nowMs)
    }

    @Test
    fun aServerConnectionFailsOverAndComesBack() {
        val w = World()
        val b = w.broker("wss://b.example/mqtt")
        b.retained["x/kept"] = byteArrayOf(1)
        val log = ArrayList<String>()
        val mqtt = LinkMqtt(
            "public", listOf("wss://a.example/mqtt", "wss://b.example/mqtt"), w.sockets("solo"), { w.nowMs },
            object : LinkMqtt.Listener {
                override fun onSubscribed(filter: String) {
                    log += "sub $filter"
                }

                override fun onDown() {
                    log += "down"
                }

                override fun onMessage(topic: String, payload: ByteArray, retained: Boolean) {
                    log += "msg $topic $retained"
                }
            },
        )
        w.tickers += mqtt::tick
        mqtt.subscribe("x/#")
        mqtt.start()
        w.loop.drain()
        // a.example can't be reached: b, a moment later.
        assertEquals(TransportStatus.State.ERROR, mqtt.status.state)
        w.step(3)
        assertEquals(TransportStatus.State.CONNECTED, mqtt.status.state)
        assertEquals("wss://b.example/mqtt", mqtt.url)
        assertEquals(listOf("sub x/#", "msg x/kept true"), log)
        assertTrue(mqtt.publish("x/new", byteArrayOf(2), retained = false))
        w.loop.drain()
        assertEquals("msg x/new false", log.last())
        // Kept alive while idle.
        w.step(60)
        assertTrue(b.pings >= 1)
        // b goes quiet: given up on, then tried again (and again) until it answers.
        b.mute = true
        w.step(200)
        assertTrue("down" in log)
        assertFalse(mqtt.isConnected)
        assertFalse(mqtt.publish("x/lost", byteArrayOf(3), retained = false))
        b.mute = false
        w.step(60)
        assertEquals(TransportStatus.State.CONNECTED, mqtt.status.state)
        assertEquals(2, log.count { it == "sub x/#" })
        // Off: it says goodbye and stays off.
        mqtt.stop()
        w.step(60)
        assertEquals(TransportStatus.State.OFF, mqtt.status.state)
        assertTrue(b.clients.isEmpty())
    }

    // Parties on a server.

    @Test
    fun membersMeetOnAServer() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        val b = w.phone("Dana", 0x0B0B0B0B).server("club", club).join("club")
        w.step(2)
        assertEquals(TransportStatus(TransportStatus.State.CONNECTED, "club.example"), a.statuses["club"])
        assertEquals("Dana", a.party.members[0x0B0B0B0B]?.name)
        assertEquals("Adam", b.party.members[0x0A0A0A0A]?.name)
        assertEquals(setOf("club"), b.party.members.getValue(0x0A0A0A0A).heard.keys)
        a.fix(31.7681, 35.1584)
        w.step(2)
        assertEquals(here, b.party.members.getValue(0x0A0A0A0A).position?.at)
        // Kept on the server under the spec's topics.
        assertTrue(topic("0a0a0a0a/hello") in broker.retained)
        assertTrue(topic("0a0a0a0a/pos") in broker.retained)
        assertTrue(topic("0b0b0b0b/hello") in broker.retained)
        a.act { text("Lunch at the spring", it) }
        assertEquals("Lunch at the spring", b.party.chat.single().text)
        assertEquals(topic("0a0a0a0a/text"), broker.published.last().first)
    }

    @Test
    fun aLatecomerGetsWhatTheServerKeeps() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        w.step(2)
        a.fix(31.7681, 35.1584)
        a.act { createMark(it, Geom.Point(here), Style(icon = MarkIcon.WATER), MarkText("Spring")) }
        a.act { setMeeting(GridPoint.of(31.77, 35.16), "Car park", it) }
        a.act { startLive(3600, it) }
        w.step(3)
        // Adam's phone goes away; Dana joins later and gets it all from the server.
        a.online = false
        broker.kick("Adam")
        w.step(5)
        val b = w.phone("Dana", 0x0B0B0B0B).server("club", club).join("club")
        w.step(1)
        val adam = b.party.members[0x0A0A0A0A]
        assertEquals("Adam", adam?.name)
        assertEquals(here, adam?.position?.at)
        assertEquals("Spring", b.party.marks[MarkId(0x0A0A0A0A, 1)]?.text?.value?.name)
        assertEquals("Car park", b.party.marks[MarkId.MEETING]?.text?.value?.name)
        assertTrue(b.party.isLive(w.nowMs / 1000))
        // Kept state announces no one.
        assertTrue(b.eventsOf().none { it is LinkEvent.Joined })
        // Nothing to republish: the server held it all.
        val before = broker.published.size
        w.step(20)
        assertTrue(broker.published.drop(before).none { it.first.contains("/o/") || it.first.endsWith("/live") })
    }

    @Test
    fun marksMadeWhileTheServerWasDownReachItAfter() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        val b = w.phone("Dana", 0x0B0B0B0B).server("club", club).join("club")
        w.step(2)
        a.act { createMark(it, Geom.Point(here), text = MarkText("Before")) }
        broker.crash()
        w.step(1)
        assertEquals(TransportStatus.State.CONNECTING, a.statuses["club"]?.state)
        assertFalse(a.party.connections.single().up)
        a.act { createMark(it, Geom.Point(GridPoint.of(31.78, 35.17)), text = MarkText("While down")) }
        val ids = listOf(MarkId(0x0A0A0A0A, 1), MarkId(0x0A0A0A0A, 2))
        assertNull(b.party.marks[ids[1]])
        broker.up = true
        w.step(20)
        assertTrue(a.party.connections.single().up)
        assertEquals("While down", b.party.marks[ids[1]]?.text?.value?.name)
        assertTrue(LinkTopics.mark(tagHex, ids[1]) in broker.retained)
        // Once each: what the server still held isn't sent again.
        assertEquals(2, broker.published.count { it.first.contains("/o/") })

        // The server comes back empty: the members put the marks back, once each.
        broker.crash(wipe = true)
        broker.up = true
        w.step(25)
        ids.forEach { assertTrue(LinkTopics.mark(tagHex, it) in broker.retained) }
        assertEquals(4, broker.published.count { it.first.contains("/o/") })
        // And their hellos and positions.
        assertTrue(topic("0a0a0a0a/hello") in broker.retained)
    }

    @Test
    fun anEditMadeAwayReplacesTheKeptState() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        val b = w.phone("Dana", 0x0B0B0B0B).server("club", club).join("club")
        w.step(2)
        a.act { createMark(it, Geom.Point(here), text = MarkText("Spring")) }
        val id = MarkId(0x0A0A0A0A, 1)
        // Dana's phone loses the internet, and she renames the mark meanwhile.
        b.online = false
        broker.kick("Dana")
        w.step(5)
        b.act { editMark(id, it, text = MarkText("Spring (dry)")) }
        w.step(5)
        assertEquals("Spring", a.party.marks[id]?.text?.value?.name)
        b.back()
        w.step(10)
        assertEquals("Spring (dry)", a.party.marks[id]?.text?.value?.name)
        assertEquals(0x0B0B0B0B, a.party.marks[id]?.lastEditor)
        val kept = LinkEnvelope.open(LinkKey.of(partyKey), broker.retained.getValue(LinkTopics.mark(tagHex, id)))!!.message as LinkMessage.Mark
        assertEquals("Spring (dry)", kept.text?.value?.name)
    }

    @Test
    fun aRelayPostIsPublishedAsTheMarksStateOnce() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        val b = w.phone("Dana", 0x0B0B0B0B).server("club", club).join("club")
        w.step(2)
        // A blind relay heard a new mark on the radio and posted it as it was.
        val id = MarkId(0x0C0C0C0C, 1)
        val nowS = w.nowMs / 1000
        val mark = LinkMessage.Mark(id, Reg(Geom.Point(here), nowS, 0x0C0C0C0C), text = Reg(MarkText("Cave"), nowS, 0x0C0C0C0C))
        broker.inject(topic("0c0c0c0c/mark"), LinkEnvelope.relayed(LinkEnvelope.seal(LinkKey.of(partyKey), 0x0C0C0C0C, nowS, 1, mark))!!)
        w.loop.drain()
        assertEquals("Cave", a.party.marks[id]?.text?.value?.name)
        assertEquals("Cave", b.party.marks[id]?.text?.value?.name)
        w.step(8)
        assertTrue(LinkTopics.mark(tagHex, id) in broker.retained)
        assertEquals(1, broker.published.count { it.first == LinkTopics.mark(tagHex, id) })
    }

    @Test
    fun leavingClearsWhatTheServerKeeps() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        val b = w.phone("Dana", 0x0B0B0B0B).server("club", club).join("club")
        a.fix(31.7681, 35.1584)
        w.step(2)
        a.act { sos(true, "Stuck", it) }
        assertTrue(topic("0a0a0a0a/sos") in broker.retained)
        a.act { sos(false, "", it) }
        a.act { leave(it) }
        a.hub.removeParty(a.party)
        w.step(1)
        assertTrue(b.eventsOf().any { it is LinkEvent.Left && it.member.id == 0x0A0A0A0A })
        assertTrue(broker.retained.keys.none { it.contains("0a0a0a0a") })
        // A latecomer doesn't see Adam at all.
        val c = w.phone("Carmel", 0x0C0C0C0C).server("club", club).join("club")
        w.step(1)
        assertNull(c.party.members[0x0A0A0A0A])
        assertEquals("Dana", c.party.members[0x0B0B0B0B]?.name)
    }

    @Test
    fun anSosKeptFromBeforeIsPutRight() {
        val w = World()
        val broker = w.broker(club)
        val a = w.phone("Adam", 0x0A0A0A0A).server("club", club).join("club")
        w.step(2)
        a.act { sos(true, "Stuck", it) }
        // Away from the server, Adam cancels it; back, the server still says SOS: his phone puts it right.
        a.online = false
        broker.kick("Adam")
        w.step(3)
        a.act { sos(false, "", it) }
        a.back()
        w.step(5)
        val kept = LinkEnvelope.open(LinkKey.of(partyKey), broker.retained.getValue(topic("0a0a0a0a/sos")))!!.message as LinkMessage.Sos
        assertFalse(kept.active)
    }

    // Radios.

    @Test
    fun aRadioSendsWithinItsBudget() {
        val w = World()
        val mesh = FakeMesh(w.loop)
        val a = w.phone("Adam", 0x0A0A0A0A).radio("radio", mesh).join("radio")
        val b = w.phone("Dana", 0x0B0B0B0B).radio("radio", mesh).join("radio")
        w.step(60) // the hellos' airtime is back
        val start = mesh.aired.size
        // Five messages at once: three go (the burst), the rest one per 20 s.
        repeat(5) { n -> a.act { text("Message ${n + 1}", it) } }
        val first = mesh.aired.drop(start).count { LinkEnvelope.header(it)!!.type == LinkType.TEXT }
        assertEquals(3, first)
        assertEquals(2, a.hub.connection("radio")!!.waiting)
        // An SOS doesn't wait.
        a.act { sos(true, "Fell", it) }
        assertEquals(LinkType.SOS, LinkEnvelope.header(mesh.aired.last())!!.type)
        w.step(20)
        assertEquals(4, b.party.chat.size)
        w.step(20)
        assertEquals(5, b.party.chat.size)
        assertEquals((1..5).map { "Message $it" }, b.party.chat.map { it.text })
    }

    @Test
    fun aGatewayJoinsARadioAndAServer() {
        val w = World()
        val mesh = FakeMesh(w.loop)
        w.broker(club)
        val r = w.phone("Rotem", 0x01010101).radio("radio", mesh).join("radio")
        val g = w.phone("Gil", 0x06060606).radio("radio", mesh).server("club", club).join("radio", "club")
        val o = w.phone("Omer", 0x0F0F0F0F).server("club", club).join("club")
        w.step(12) // the gateway introduces Rotem to the server once it has settled
        assertEquals("Rotem", o.party.members[0x01010101]?.name)
        assertEquals("Omer", r.party.members[0x0F0F0F0F]?.name)
        // A mark made on the radio reaches the server as its full state, and back the other way.
        r.act { createMark(it, Geom.Point(here), text = MarkText("Ford")) }
        w.step(6)
        val id = MarkId(0x01010101, 1)
        assertEquals("Ford", o.party.marks[id]?.text?.value?.name)
        o.act { editMark(id, it, text = MarkText("Ford, deep after rain")) }
        w.step(25) // onto the radio within the gateway's airtime
        assertEquals("Ford, deep after rain", r.party.marks[id]?.text?.value?.name)
        o.act { text("Coming down", it) }
        w.step(25)
        assertEquals("Coming down", r.party.chat.last().text)
    }

    // Several parties and servers.

    @Test
    fun twoPartiesShareAServerAndARadio() {
        val w = World()
        val mesh = FakeMesh(w.loop)
        val broker = w.broker(club)
        val other = PartyKey.create("Club run")
        val h = w.phone("Hila", 0x0A0A0A0A).server("club", club).radio("radio", mesh)
        val second = h.party(other, 0x0E0E0E0E)
        h.join("club").join("club", "radio", p = second)
        val x = w.phone("Ido", 0x0B0B0B0B).server("club", club).join("club")
        val y = w.phone("Yael", 0x0C0C0C0C, other).radio("radio", mesh).join("radio")
        w.step(3)
        assertEquals(setOf(0x0A0A0A0A), x.party.members.keys)
        assertEquals(setOf(0x0E0E0E0E), y.party.members.keys)
        assertEquals(setOf(0x0B0B0B0B), h.party.members.keys)
        assertEquals(setOf(0x0C0C0C0C), second.members.keys)
        // One connection to the server, a subscription for each party.
        assertEquals(1, broker.clients.count { it.owner == "Hila" })
        assertEquals(2, broker.clients.single { it.owner == "Hila" }.subs.size)
        // Yael's message reaches her party only.
        y.act { text("Second group here", it) }
        w.step(4)
        assertEquals("Second group here", second.chat.single().text)
        assertTrue(h.party.chat.isEmpty())
        assertTrue(x.party.chat.isEmpty())
        // Hila leaves the club run: its subscription goes, the trip's stays.
        h.hub.removeParty(second)
        w.loop.drain()
        assertEquals(listOf(LinkTopics.all(tagHex)), broker.clients.single { it.owner == "Hila" }.subs.toList())
    }

    @Test
    fun aPartyOnTwoServersMeetsThroughAPhoneOnBoth() {
        val w = World()
        val s1 = w.broker(club)
        val s2 = w.broker("wss://backup.example/mqtt")
        val a = w.phone("Adam", 0x0A0A0A0A).server("s1", club).join("s1")
        val b = w.phone("Dana", 0x0B0B0B0B).server("s2", "wss://backup.example/mqtt").join("s2")
        val g = w.phone("Gil", 0x06060606).server("s1", club).server("s2", "wss://backup.example/mqtt").join("s1", "s2")
        w.step(3)
        assertEquals("Dana", a.party.members[0x0B0B0B0B]?.name)
        assertEquals("Adam", b.party.members[0x0A0A0A0A]?.name)
        assertEquals(setOf("s1", "s2"), g.party.members.keys.let { g.party.members.getValue(0x0A0A0A0A).heard.keys + g.party.members.getValue(0x0B0B0B0B).heard.keys })
        a.act { createMark(it, Geom.Point(here), text = MarkText("Hut")) }
        w.step(6)
        assertEquals("Hut", b.party.marks[MarkId(0x0A0A0A0A, 1)]?.text?.value?.name)
        assertTrue(LinkTopics.mark(tagHex, MarkId(0x0A0A0A0A, 1)) in s2.retained)
        assertNotNull(s1.retained[topic("0b0b0b0b/hello")])
    }
}
