package app.maparound.ui

import app.maparound.map.PartyMarker
import app.maparound.party.Meeting
import app.maparound.party.Member
import app.maparound.party.Mesh
import app.maparound.party.MeshNode
import app.maparound.party.PartyConnection
import app.maparound.party.PartyKey
import app.maparound.party.PartyLayers
import app.maparound.party.PartyMessage
import app.maparound.party.PartyState
import app.maparound.party.TimedPosition
import app.maparound.party.TransportStatus
import app.maparound.party.PartyMark
import app.maparound.party.MarkShape
import app.maparound.party.link.Fill
import app.maparound.party.link.LineStyle
import app.maparound.party.link.MarkIcon
import app.maparound.party.link.Width
import app.maparound.places.PlaceCategory
import app.maparound.routing.GeoPoint
import app.maparound.party.WayPoint
import app.maparound.testing.assertEquals
import app.maparound.testing.assertNull
import app.maparound.testing.assertTrue
import kotlin.test.Test

/** The parties on the map as layers (PartyMapLayers.kt). */
class PartyMapLayersTest {
    private val now = 1_795_000_000_000L
    private val hike = PartyKey.create("Hike", null)
    private val club = PartyKey.create("Club run", null)
    private val connected = TransportStatus(TransportStatus.State.CONNECTED)

    private fun pos(lat: Double, sos: Boolean = false) = PartyMessage.Position(lat, 35.0, null, 10, null, null, 80, sos = sos)

    /** Dana, heard online at 31.1 a minute ago and on the radio at 31.2 just now. */
    private fun dana(sos: Boolean = false) = Member(
        id = 7, name = "Dana", position = pos(31.2, sos), positionAt = now, sos = sos,
        positionsOn = mapOf("public" to TimedPosition(pos(31.1), now - 60_000), "radio:AA" to TimedPosition(pos(31.2, sos), now)),
    )

    /** Dana's way: one point 40 minutes ago (too old), then 20 and 10 minutes ago, then the two positions above. */
    private val danasWay = listOf(
        WayPoint(30.6, 35.0, now - 40 * 60_000), WayPoint(30.8, 35.0, now - 20 * 60_000), WayPoint(30.9, 35.0, now - 10 * 60_000),
        WayPoint(31.1, 35.0, now - 60_000), WayPoint(31.2, 35.0, now),
    )

    private fun party(
        key: PartyKey, members: List<Member>, color: Int = 0xFF1E88E5.toInt(), layers: PartyLayers = PartyLayers(),
        hidden: Set<String> = emptySet(), meeting: Meeting? = null, nodes: List<MeshNode> = emptyList(), nodesShown: Boolean = true,
    ) = PartyState(
        party = key, members = members, color = color, layers = layers, hiddenConnections = hidden, meeting = meeting,
        meshNodes = nodes, nodesShown = nodesShown, recentWays = mapOf(7 to danasWay),
        connections = listOf(
            PartyConnection("public", false, "Public servers", connected, now, 1),
            PartyConnection("radio:AA", true, "T-Echo", connected, now, 1, nodes.size),
        ),
    )

    private fun members(markers: List<PartyMarker>) = markers.filter { it.kind == PartyMarker.Kind.MEMBER || it.kind == PartyMarker.Kind.SOS }

    @Test
    fun onePartyLooksAsBefore() {
        val m = partyMapMarkers(listOf(party(hike, listOf(dana()), meeting = Meeting(31.5, 35.5, "Car park", 7))), now)
        val d = members(m).single()
        assertEquals("Dana", d.label)
        assertNull(d.ring)
        assertEquals(memberKey(hike, 7), d.key)
        assertEquals("Car park", m.single { it.kind == PartyMarker.Kind.MEET }.label)
    }

    @Test
    fun severalPartiesHaveTheirColourAndName() {
        val m = partyMapMarkers(listOf(party(hike, listOf(dana())), party(club, listOf(dana()), color = 0xFFFFB300.toInt())), now)
        assertEquals(listOf("Dana · Hike", "Dana · Club run"), members(m).map { it.label })
        assertEquals(listOf(0xFF1E88E5.toInt(), 0xFFFFB300.toInt()), members(m).map { it.ring })
        // The same id in two parties is two members: two keys, each finding its own.
        val keys = members(m).map { it.key }
        assertEquals(2, keys.toSet().size)
        val target = partyTarget(listOf(party(hike, listOf(dana())), party(club, listOf(dana()))), keys[1]) as PartyTarget.MemberOf
        assertEquals("Club run", target.party.party!!.name)
    }

    @Test
    fun aMemberSitsAtTheNewestPositionFromAShownConnection() {
        assertEquals(31.2, members(partyMapMarkers(listOf(party(hike, listOf(dana()))), now)).single().lat, 1e-9)
        // The radio hidden: where the servers had them.
        assertEquals(31.1, members(partyMapMarkers(listOf(party(hike, listOf(dana()), hidden = setOf("radio:AA"))), now)).single().lat, 1e-9)
        // Both hidden: not shown.
        assertTrue(members(partyMapMarkers(listOf(party(hike, listOf(dana()), hidden = setOf("radio:AA", "public"))), now)).isEmpty())
    }

    @Test
    fun anSosShowsWhateverIsOff() {
        val sos = dana(sos = true)
        for (p in listOf(
            party(hike, listOf(sos), hidden = setOf("radio:AA", "public")),
            party(hike, listOf(sos), layers = PartyLayers(members = false)),
        )) {
            val d = members(partyMapMarkers(listOf(p), now)).single()
            assertEquals(PartyMarker.Kind.SOS, d.kind)
            assertEquals(31.2, d.lat, 1e-9)
        }
        assertEquals(PartyMarker.Kind.SOS, members(partyMapMarkers(listOf(party(hike, listOf(sos))), now, shown = false)).single().kind)
    }

    @Test
    fun aPartysLayersGoOnAndOff() {
        val meet = Meeting(31.5, 35.5, "Car park", 7)
        val noMembers = partyMapMarkers(listOf(party(hike, listOf(dana()), layers = PartyLayers(members = false), meeting = meet)), now)
        assertTrue(members(noMembers).isEmpty())
        assertEquals(1, noMembers.count { it.kind == PartyMarker.Kind.MEET })
        val noMarks = partyMapMarkers(listOf(party(hike, listOf(dana()), layers = PartyLayers(marks = false), meeting = meet)), now)
        assertEquals(0, noMarks.count { it.kind == PartyMarker.Kind.MEET })
        assertEquals(1, members(noMarks).size)
    }

    @Test
    fun aTrailIsTheLastHalfHourUpToTheMarker() {
        val t = partyTrails(listOf(party(hike, listOf(dana()))), now).single()
        assertEquals(listOf(30.8, 30.9, 31.1, 31.2), t.points.map { it.lat })
        assertEquals(dana().color, t.color)
        // The radio hidden: the marker is where the servers had them, and the trail stops there.
        assertEquals(listOf(30.8, 30.9, 31.1), partyTrails(listOf(party(hike, listOf(dana()), hidden = setOf("radio:AA"))), now).single().points.map { it.lat })
    }

    @Test
    fun trailsFollowTheirPartysLayers() {
        assertTrue(partyTrails(listOf(party(hike, listOf(dana()), layers = PartyLayers(trails = false))), now).isEmpty())
        assertTrue(partyTrails(listOf(party(hike, listOf(dana()), layers = PartyLayers(members = false))), now).isEmpty())
        assertTrue(partyTrails(listOf(party(hike, listOf(dana()))), now, shown = false).isEmpty())
        // An SOS: theirs shows anyway (where they came from), unless trails are off.
        assertEquals(1, partyTrails(listOf(party(hike, listOf(dana(sos = true)), layers = PartyLayers(members = false))), now, shown = false).size)
    }

    @Test
    fun theRadiosNodesFollowItsLayer() {
        val node = MeshNode(0x1234, "Car tracker", "CAR", Mesh.Position(31.3, 35.3, 700, null), now)
        fun nodes(p: PartyState) = partyMapMarkers(listOf(p), now).count { it.kind == PartyMarker.Kind.RADIO }
        assertEquals(1, nodes(party(hike, listOf(), nodes = listOf(node))))
        assertEquals(0, nodes(party(hike, listOf(), nodes = listOf(node), nodesShown = false)))
        assertEquals(0, nodes(party(hike, listOf(), nodes = listOf(node), hidden = setOf("radio:AA"))))
        // Two parties: once.
        assertEquals(1, partyMapMarkers(listOf(party(hike, listOf(), nodes = listOf(node)), party(club, listOf(), nodes = listOf(node))), now).count { it.kind == PartyMarker.Kind.RADIO })
    }

    private fun withMarks(key: PartyKey, marks: List<PartyMark>, color: Int = 0xFF1E88E5.toInt(), layers: PartyLayers = PartyLayers(), hidden: Set<String> = emptySet()) =
        party(key, listOf(), color = color, layers = layers, hidden = hidden).copy(marks = marks)

    private val spring = PartyMark("0a0a0a0a.1", MarkShape.Point(GeoPoint(31.5, 35.1)), name = "Spring", icon = MarkIcon.WATER, via = setOf("public"))

    @Test
    fun marksHaveTheirLook() {
        val road = PartyMark("0a0a0a0a.2", MarkShape.Line(listOf(GeoPoint(31.0, 35.0), GeoPoint(31.0, 35.1))), rgb = 0x00FF00, line = LineStyle.DASHED, width = Width.THICK, via = setOf("public"))
        val camp = PartyMark("0a0a0a0a.3", MarkShape.Circle(GeoPoint(31.2, 35.2), 300.0), fill = Fill.MEDIUM, via = setOf("public"))
        val note = PartyMark("0a0a0a0a.4", MarkShape.Point(GeoPoint(31.3, 35.3)), name = "Cliff edge", icon = MarkIcon.LABEL, via = setOf("public"))
        val zone = PartyMark("0a0a0a0a.5", MarkShape.Area(listOf(GeoPoint(31.0, 35.0), GeoPoint(31.1, 35.0), GeoPoint(31.1, 35.1))), via = setOf("public"))
        val m = partyMarks(listOf(withMarks(hike, listOf(spring, road, camp, note, zone))))
        // A point: its icon (a saved place's category) and its colour.
        assertEquals("💧", m[0].emoji)
        assertEquals(PlaceCategory.WATER.argb.toInt(), m[0].color)
        assertEquals("Spring", m[0].label)
        // A line in its own colour, dashed and thick.
        assertEquals(0xFF00FF00.toInt(), m[1].color)
        assertEquals(1, m[1].dash)
        assertEquals(6.0, m[1].width, 0.0)
        // A circle round its centre, filled; no colour of its own: the party's.
        assertEquals(300.0, m[2].circleM!!, 0.0)
        assertEquals(0xFF1E88E5.toInt(), m[2].color)
        assertEquals(0.3, m[2].fill, 1e-9)
        // The name alone; an area.
        assertNull(m[3].emoji)
        assertEquals("Cliff edge", m[3].label)
        assertTrue(m[4].area)
        assertEquals(markKey(hike, "0a0a0a0a.1"), m[0].key)
        assertEquals("Spring", (partyTarget(listOf(withMarks(hike, listOf(spring))), m[0].key) as PartyTarget.MarkOf).mark.name)
    }

    @Test
    fun marksFollowTheirLayerAndConnections() {
        assertEquals(1, partyMarks(listOf(withMarks(hike, listOf(spring)))).size)
        assertTrue(partyMarks(listOf(withMarks(hike, listOf(spring), layers = PartyLayers(marks = false)))).isEmpty())
        assertTrue(partyMarks(listOf(withMarks(hike, listOf(spring))), shown = false).isEmpty())
        // Only through a hidden connection: hidden, unless made here.
        assertTrue(partyMarks(listOf(withMarks(hike, listOf(spring), hidden = setOf("public")))).isEmpty())
        assertEquals(1, partyMarks(listOf(withMarks(hike, listOf(spring.copy(mine = true)), hidden = setOf("public")))).size)
        // Several parties: whose it is.
        val two = partyMarks(listOf(withMarks(hike, listOf(spring)), withMarks(club, listOf(spring), color = 0xFFFFB300.toInt())))
        assertEquals(listOf("Spring · Hike", "Spring · Club run"), two.map { it.label })
        assertEquals(2, two.map { it.key }.toSet().size)
    }

    @Test
    fun aRouteToAMarkAndItsLength() {
        val line = MarkShape.Line(listOf(GeoPoint(31.0, 35.0), GeoPoint(31.0, 35.01), GeoPoint(31.01, 35.01)))
        assertEquals(GeoPoint(31.01, 35.01), markAnchor(line, GeoPoint(31.02, 35.02)))
        assertEquals(GeoPoint(31.0, 35.0), markAnchor(line, null))
        assertEquals(GeoPoint(31.2, 35.2), markAnchor(MarkShape.Circle(GeoPoint(31.2, 35.2), 50.0), GeoPoint(0.0, 0.0)))
        assertEquals(954.0 + 1112.0, markLength(line)!!, 10.0)
        assertNull(markLength(MarkShape.Point(GeoPoint(31.0, 35.0))))
    }
}
