package app.maparound.ui

import app.maparound.i18n.T
import app.maparound.map.PartyMarker
import app.maparound.map.PartyTrail
import app.maparound.map.MarkOnMap
import app.maparound.party.Meeting
import app.maparound.party.Member
import app.maparound.party.MeshNode
import app.maparound.party.PartyKey
import app.maparound.party.PartyMark
import app.maparound.party.MarkShape
import app.maparound.party.link.Fill
import app.maparound.party.link.MarkIcon
import app.maparound.party.link.Width
import app.maparound.places.PlaceCategory
import app.maparound.party.PartyState
import app.maparound.party.TimedPosition
import app.maparound.routing.Geo
import app.maparound.routing.GeoPoint

// The parties on the map, as layers (docs/LINK_PROTOCOL.md §7): each party on or off (its members,
// its meeting point and marks), each connection on or off, shared by the parties on it. A member
// sits at their newest position from a shown connection; what came only through hidden ones isn't
// shown; an SOS always is. With several parties on the map, each one's markers have its colour
// round them and its name after theirs. Unit-tested (PartyMapLayersTest).

/** A member's marker: their party (its tag) and their id there (ids are per party). */
internal fun memberKey(party: PartyKey, id: Int) = "m${party.tagHex}.$id"

internal fun meetKey(party: PartyKey) = "meet.${party.tagHex}"

internal fun radioKey(num: Long) = "r$num"

internal fun markKey(party: PartyKey, id: String) = "k${party.tagHex}.$id"

/** What a party marker's key points at. */
internal sealed class PartyTarget {
    data class MemberOf(val party: PartyState, val member: Member) : PartyTarget()
    data class MeetingOf(val party: PartyState, val meeting: Meeting) : PartyTarget()
    data class MarkOf(val party: PartyState, val mark: PartyMark) : PartyTarget()
    data class Radio(val node: MeshNode) : PartyTarget()
}

internal fun partyTarget(parties: List<PartyState>, key: String): PartyTarget? {
    for (p in parties) {
        val k = p.party ?: continue
        if (key == meetKey(k)) return p.meeting?.let { PartyTarget.MeetingOf(p, it) }
        val markPrefix = "k${k.tagHex}."
        if (key.startsWith(markPrefix)) {
            val id = key.removePrefix(markPrefix)
            return p.marks.firstOrNull { it.id == id }?.let { PartyTarget.MarkOf(p, it) }
        }
        val prefix = "m${k.tagHex}."
        if (key.startsWith(prefix)) {
            val id = key.removePrefix(prefix).toIntOrNull() ?: return null
            return p.members.firstOrNull { it.id == id }?.let { PartyTarget.MemberOf(p, it) }
        }
    }
    if (key.startsWith("r")) {
        val num = key.drop(1).toLongOrNull() ?: return null
        return parties.firstOrNull()?.meshNodes?.firstOrNull { it.num == num }?.let { PartyTarget.Radio(it) }
    }
    return null
}

/**
 * Where [m] shows on the map: their newest position a shown connection brought (one that came only
 * through [hidden] ones doesn't count); null when there's none.
 */
fun shownPosition(m: Member, hidden: Set<String>): TimedPosition? {
    // Without the connections it came through (the old engine): as it is.
    if (m.positionsOn.isEmpty()) return m.position?.let { TimedPosition(it, m.positionAt) }
    return m.positionsOn.filterKeys { it !in hidden }.values.maxByOrNull { it.at }
}

/**
 * The trails layer: each member's way over the last half hour ([PartyState.recentWays]) in their
 * colour, up to where their marker is (one held back by a hidden connection holds its trail back
 * too); for members shown, and anyone in an SOS. Not a [replay]ed member's: the replay draws theirs.
 */
fun partyTrails(parties: List<PartyState>, now: Long, replay: PartyReplay.State? = null, shown: Boolean = true): List<PartyTrail> = buildList {
    val inParties = parties.filter { it.party != null }
    val hidden = inParties.firstOrNull()?.hiddenConnections.orEmpty()
    val since = now - PartyState.RECENT_WAY_MS
    for (p in inParties) {
        if (!p.layers.trails) continue
        for (m in p.members) {
            if (m.id == replay?.memberId) continue
            val until = when {
                m.sos -> m.positionAt
                shown && p.layers.members -> shownPosition(m, hidden)?.at
                else -> null
            } ?: continue
            val points = p.recentWays[m.id].orEmpty().filter { it.at in since..until }.map { GeoPoint(it.lat, it.lon) }
            if (points.size >= 2) add(PartyTrail(points, m.color))
        }
    }
}

/**
 * The parties' marks (not their meeting points: [partyMapMarkers] has those), each party's while
 * its marks layer is on: a mark while a connection that brought it is shown, or it's mine. Its
 * colour: its own, else its icon's (a saved place's category), else its party's. With several
 * parties, a point's name says whose it is.
 */
fun partyMarks(parties: List<PartyState>, shown: Boolean = true): List<MarkOnMap> = buildList {
    if (!shown) return@buildList
    val inParties = parties.filter { it.party != null }
    val several = inParties.size > 1
    val hidden = inParties.firstOrNull()?.hiddenConnections.orEmpty()
    for (p in inParties) {
        if (!p.layers.marks) continue
        val key = p.party!!
        for (m in p.marks) {
            if (!m.mine && m.via.isNotEmpty() && m.via.all { it in hidden }) continue
            val category = m.icon?.takeIf { it in 1..8 }?.let { PlaceCategory.entries[it - 1] }
            val color = m.rgb?.let { (0xFF shl 24) or it } ?: category?.argb?.toInt() ?: p.color.takeIf { it != 0 } ?: DEFAULT_MARK_COLOR
            val style = MarkOnMap(
                key = markKey(key, m.id), points = emptyList(), color = color,
                width = when (m.width) { Width.THIN -> 2.0; Width.NORMAL -> 3.5; Width.THICK -> 6.0; Width.EXTRA_THICK -> 9.0 },
                dash = m.line.ordinal,
                fill = when (m.fill) { Fill.NONE -> 0.0; Fill.LIGHT -> 0.15; Fill.MEDIUM -> 0.3; Fill.STRONG -> 0.5 },
            )
            add(when (val s = m.shape) {
                is MarkShape.Point -> style.copy(
                    points = listOf(s.at),
                    // The name alone, without a badge; else the icon's (a pin by default).
                    emoji = when (m.icon) { MarkIcon.LABEL -> null; MarkIcon.FLAG -> "🚩"; else -> category?.emoji ?: PlaceCategory.OTHER.emoji },
                    label = m.name + if (several && m.name.isNotEmpty()) " · ${key.name}" else "",
                )
                is MarkShape.Circle -> style.copy(points = listOf(s.center), circleM = s.radiusM)
                is MarkShape.Line -> style.copy(points = s.points)
                is MarkShape.Area -> style.copy(points = s.points, area = true)
            })
        }
    }
}

/** Where a route to a mark goes: a point, a circle's centre; else its point nearest [from] (the first, not knowing). */
internal fun markAnchor(shape: MarkShape, from: GeoPoint?): GeoPoint? = when (shape) {
    is MarkShape.Point -> shape.at
    is MarkShape.Circle -> shape.center
    is MarkShape.Line -> shape.points.nearestTo(from)
    is MarkShape.Area -> shape.points.nearestTo(from)
}

private fun List<GeoPoint>.nearestTo(from: GeoPoint?) = if (from == null) firstOrNull() else minByOrNull { Geo.distance(from, it) }

/** A line mark's length (m); null for the other shapes. */
internal fun markLength(shape: MarkShape): Double? = (shape as? MarkShape.Line)?.points?.zipWithNext { a, b -> Geo.distance(a, b) }?.sum()

/** A mark with no colour of its own, no icon's and no party's. */
private val DEFAULT_MARK_COLOR = 0xFF6A1B9A.toInt()

/** A member more than this long since their position: drawn faded. */
private const val STALE_MS = 10 * 60_000L

/** A radio node not heard for this long: drawn faded. */
private const val NODE_STALE_MS = 30 * 60_000L

/**
 * The parties' markers: each party's members (a [replay]ed one where the replay is) and meeting
 * point, as its layers say, and the radio's nodes once. [shown]: the party layer on (off: only
 * SOS alerts).
 */
fun partyMapMarkers(parties: List<PartyState>, now: Long, replay: PartyReplay.State? = null, shown: Boolean = true): List<PartyMarker> = buildList {
    val inParties = parties.filter { it.party != null }
    val several = inParties.size > 1
    val common = inParties.firstOrNull()
    val hidden = common?.hiddenConnections.orEmpty()
    replay?.let { r ->
        // Where they were at the moment shown, as they were (an SOS then: the SOS marker).
        val owner = inParties.firstOrNull { p -> p.members.any { it.id == r.memberId } }
        val at = r.where
        val sos = r.points[r.index].position.sos
        add(PartyMarker(
            owner?.party?.let { memberKey(it, r.memberId) } ?: "m.${r.memberId}", at.lat, at.lon, r.name, r.color,
            if (sos) PartyMarker.Kind.SOS else PartyMarker.Kind.MEMBER, false, r.icon, ring = owner?.color?.takeIf { several && it != 0 },
        ))
    }
    for (p in inParties) {
        val key = p.party!!
        val ring = p.color.takeIf { several && it != 0 }
        val suffix = if (several) " · ${key.name}" else ""
        val membersOn = shown && p.layers.members
        for (m in p.members) {
            if (m.id == replay?.memberId) continue
            // An SOS shows whatever is off, at their newest position.
            val where = when {
                m.sos -> m.position?.let { TimedPosition(it, m.positionAt) }
                membersOn -> shownPosition(m, hidden)
                else -> null
            } ?: continue
            add(PartyMarker(
                memberKey(key, m.id), where.position.lat, where.position.lon, m.displayName + suffix, m.color,
                if (m.sos) PartyMarker.Kind.SOS else PartyMarker.Kind.MEMBER,
                stale = now - where.at > STALE_MS,
                icon = m.icon,
                ring = ring,
            ))
        }
        if (shown && p.layers.marks) p.meeting?.let {
            add(PartyMarker(meetKey(key), it.lat, it.lon, it.label.ifBlank { T.map.meetMarker } + suffix, 0, PartyMarker.Kind.MEET, false, ring = ring))
        }
    }
    // The radio's nodes, once: while its layer is shown (or before it ran at all).
    val radios = common?.connections.orEmpty().filter { it.radio }
    val radioShown = radios.isEmpty() || radios.any { it.id !in hidden }
    if (shown && common != null && common.nodesShown && radioShown) common.meshNodes.forEach { n ->
        n.position?.let {
            add(PartyMarker(
                radioKey(n.num), it.lat, it.lon, n.shortName.ifBlank { n.name }, 0, PartyMarker.Kind.RADIO,
                stale = now - n.heardAt > NODE_STALE_MS,
            ))
        }
    }
}
