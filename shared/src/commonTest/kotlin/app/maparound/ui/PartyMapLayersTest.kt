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

    private fun party(
        key: PartyKey, members: List<Member>, color: Int = 0xFF1E88E5.toInt(), layers: PartyLayers = PartyLayers(),
        hidden: Set<String> = emptySet(), meeting: Meeting? = null, nodes: List<MeshNode> = emptyList(), nodesShown: Boolean = true,
    ) = PartyState(
        party = key, members = members, color = color, layers = layers, hiddenConnections = hidden, meeting = meeting,
        meshNodes = nodes, nodesShown = nodesShown,
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
    fun theRadiosNodesFollowItsLayer() {
        val node = MeshNode(0x1234, "Car tracker", "CAR", Mesh.Position(31.3, 35.3, 700, null), now)
        fun nodes(p: PartyState) = partyMapMarkers(listOf(p), now).count { it.kind == PartyMarker.Kind.RADIO }
        assertEquals(1, nodes(party(hike, listOf(), nodes = listOf(node))))
        assertEquals(0, nodes(party(hike, listOf(), nodes = listOf(node), nodesShown = false)))
        assertEquals(0, nodes(party(hike, listOf(), nodes = listOf(node), hidden = setOf("radio:AA"))))
        // Two parties: once.
        assertEquals(1, partyMapMarkers(listOf(party(hike, listOf(), nodes = listOf(node)), party(club, listOf(), nodes = listOf(node))), now).count { it.kind == PartyMarker.Kind.RADIO })
    }
}
