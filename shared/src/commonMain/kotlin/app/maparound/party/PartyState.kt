package app.maparound.party

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.maparound.sensors.GeoFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** How a transport (online MQTT, LoRa radio) is doing. */
data class TransportStatus(val state: State, val detail: String = "") {
    enum class State { OFF, CONNECTING, CONNECTED, ERROR }
}

/** Me in the current party: [color] ARGB, [icon] a [PartyIcon] code. */
data class PartyMe(val id: Int, val name: String, val color: Int, val icon: Int = 0)

/** Everything the Party screen and the map show about the party. */
data class PartyState(
    val party: PartyKey? = null,
    val me: PartyMe? = null,
    /**
     * My name, colour and icon as kept, in a party or not (what the next one starts with; Settings
     * changes them): null before they were ever set.
     */
    val look: PartyMe? = null,
    /** Running: sending and receiving. */
    val active: Boolean = false,
    /** Share my position (off = "ghost": see others, stay hidden). */
    val sharing: Boolean = true,
    val members: List<Member> = emptyList(),
    val meshNodes: List<MeshNode> = emptyList(),
    val meeting: Meeting? = null,
    val chat: List<ChatLine> = emptyList(),
    val mySos: Boolean = false,
    val online: TransportStatus = TransportStatus(TransportStatus.State.OFF),
    val onlineEnabled: Boolean = true,
    val radio: TransportStatus = TransportStatus(TransportStatus.State.OFF),
    val radioName: String? = null,
    val gateway: Boolean = true,
    val myPosition: GeoFix? = null,
    /** How many positions of each member (by id) are in their history ([PartyController.history]). */
    val historyCounts: Map<Int, Int> = emptyMap(),
    /** This party's colour on this device (ARGB): the ring round its markers while several parties are on the map. */
    val color: Int = 0,
    /** What of this party the map shows. */
    val layers: PartyLayers = PartyLayers(),
    /** The connections (servers, radios), shared by the parties on them, as the map's layers show them. */
    val connections: List<PartyConnection> = emptyList(),
    /** The connections hidden on the map (by [PartyConnection.id]): what came only through them isn't shown. */
    val hiddenConnections: Set<String> = emptySet(),
    /** The plain Meshtastic nodes the radio hears, on the map. */
    val nodesShown: Boolean = true,
    /** Each member's way (by id) over the last [RECENT_WAY_MS], oldest first: the trails layer. */
    val recentWays: Map<Int, List<WayPoint>> = emptyMap(),
    /** The party's marks (not the meeting point: [meeting]), as the map draws them. */
    val marks: List<PartyMark> = emptyList(),
) {
    companion object {
        /** How far back the trails layer goes. */
        const val RECENT_WAY_MS = 30 * 60_000L
    }
}

/** A point of a member's way, and when they were there (their clock, ms). */
data class WayPoint(val lat: Double, val lon: Double, val at: Long)

/** One of a party's marks (docs/LINK_PROTOCOL.md §5): its shape, look and text, and how it came. */
data class PartyMark(
    /** Its id in the party (creator and counter). */
    val id: String,
    val shape: MarkShape,
    val name: String = "",
    val note: String = "",
    /** A [app.maparound.party.link.MarkIcon] code (1–8: the saved places' categories); null: a plain pin. */
    val icon: Int? = null,
    /** 0xRRGGBB; null: the icon's own colour, else the party's. */
    val rgb: Int? = null,
    val width: app.maparound.party.link.Width = app.maparound.party.link.Width.NORMAL,
    val line: app.maparound.party.link.LineStyle = app.maparound.party.link.LineStyle.SOLID,
    val fill: app.maparound.party.link.Fill = app.maparound.party.link.Fill.NONE,
    /** The connections that brought any of it: shown while one of them is (or it's [mine]). */
    val via: Set<String> = emptySet(),
    /** Made or edited on this phone. */
    val mine: Boolean = false,
    /** Who changed it last (their member id), and when (ms). */
    val editor: Int? = null,
    val edited: Long? = null,
)

/** A mark's shape. */
sealed class MarkShape {
    data class Point(val at: app.maparound.routing.GeoPoint) : MarkShape()
    data class Circle(val center: app.maparound.routing.GeoPoint, val radiusM: Double) : MarkShape()
    data class Line(val points: List<app.maparound.routing.GeoPoint>) : MarkShape()

    /** A closed area, its last point not repeated. */
    data class Area(val points: List<app.maparound.routing.GeoPoint>) : MarkShape()
}

/** What of one party the map shows; an SOS shows whatever is off. */
data class PartyLayers(
    /** The members' positions. */
    val members: Boolean = true,
    /** Where each member went over the last half hour (with their marker). */
    val trails: Boolean = true,
    /** The meeting point, and the party's marks. */
    val marks: Boolean = true,
)

/**
 * One connection as the layers show it (docs/LINK_PROTOCOL.md §7): a server or a radio, shared by
 * the parties on it. Hiding it hides what came only through it; it still receives, sends and relays.
 */
data class PartyConnection(
    /** Link v1's connection id: kept across restarts, so what's hidden stays hidden. */
    val id: String,
    val radio: Boolean,
    /** The server's host ("Public servers" for those), or the radio's name. */
    val label: String,
    val status: TransportStatus,
    /** When it last brought something from a member (our clock, ms; 0: never). */
    val lastHeard: Long,
    /** Members it brought something from, in all the parties. */
    val members: Int,
    /** Plain Meshtastic nodes it hears (a radio). */
    val nodes: Int = 0,
)

/**
 * The party session as the shared screens drive it (Android: PartySession; the web: LinkSession).
 * Up to [MAX_PARTIES] parties at once: the screens show one ([state], picked with [select]); the
 * map all ([parties]). The actions are the shown party's, except the shared settings (my look,
 * the online switch, the radio, the gateway) and an SOS, which goes to every party that's on.
 */
interface PartyController {
    /** The party the screens show (no party: [PartyState.party] null), with what all parties share. */
    val state: StateFlow<PartyState>

    /** Every party I'm in, in the order joined (empty: none). Implementations with one party may leave it empty. */
    val parties: StateFlow<List<PartyState>> get() = NO_PARTIES

    /** Shows [party] (one of [parties]) on the screens. */
    fun select(party: PartyKey) = Unit

    /** What of [party] the map shows. */
    fun setLayers(party: PartyKey, layers: PartyLayers) = Unit

    /** A connection's layer ([PartyConnection.id]) on the map or hidden; it keeps running either way. */
    fun setConnectionShown(id: String, shown: Boolean) = Unit

    /** The plain Meshtastic nodes the radio hears, on the map or not. */
    fun setNodesShown(shown: Boolean) = Unit

    /** [party]'s colour on this device (one of [PARTY_COLORS]): its markers' ring with several parties. */
    fun setPartyColor(party: PartyKey, color: Int) = Unit

    /** Joins, leaves and SOS alerts, for the UI to announce. */
    val events: SharedFlow<PartyEvent>

    /** An invite opened from a link or QR code, waiting for the user to confirm. */
    val pendingInvite: MutableStateFlow<PartyKey?>

    /**
     * Only watches the party: sees the members, the meeting point, SOS alerts and messages, and
     * sends nothing, so it shares no position and the others don't see it. Creating a party,
     * [join]'s name and the sending calls do nothing.
     */
    val watchOnly: Boolean get() = false

    /** Has the LoRa radio (and so the online switch and gateway); online only otherwise (the web app). */
    val hasRadio: Boolean get() = true

    fun create(partyName: String, myName: String)
    fun join(key: PartyKey, myName: String)

    /**
     * The party's own MQTT server ([PartyRelays.server]), or null for the public ones: the party
     * moves there (its invite carries it, so it has to be shared again), and new parties start there.
     */
    fun setServer(server: String?) = Unit

    /** The server new parties start on (the last one chosen), or null for the public ones. */
    val defaultServer: String? get() = null
    fun leave()
    fun setActive(on: Boolean)
    fun setSharing(on: Boolean)
    /** My name, colour and icon: in the party now (the others are told), and for the next ones. */
    fun setProfile(name: String, color: Int, icon: Int)
    fun setOnlineEnabled(on: Boolean)
    fun setGateway(on: Boolean)
    fun setRadio(address: String, name: String)
    fun forgetRadio()
    fun sos(active: Boolean, text: String = "")
    fun setMeeting(lat: Double, lon: Double, label: String)
    fun clearMeeting()
    fun sendText(text: String)

    /** [member]'s positions this device received in the party, oldest first (for replaying their way). */
    fun history(member: Int): List<HistoryPoint> = emptyList()

    companion object {
        /** Parties at once: more crowds the screens, and the radio's airtime is shared by all. */
        const val MAX_PARTIES = 4

        /** The parties' colours on this device: strong, apart from each other and from an SOS's red. */
        val PARTY_COLORS = listOf(0xFF1E88E5, 0xFFFFB300, 0xFF8E24AA, 0xFF00897B, 0xFFD81B60, 0xFF7CB342).map { it.toInt() }
    }
}

private val NO_PARTIES: StateFlow<List<PartyState>> = MutableStateFlow(emptyList())

val LocalParty = staticCompositionLocalOf<PartyController> { error("No PartyController provided") }

/** The party features that need the platform's own UI. */
interface PartyPlatform {
    /** Whether [rememberQrScanner] can scan (not in every browser). */
    val canScanQr: Boolean get() = true

    /** Opens the camera to scan an invite QR code; [onResult] gets its text. */
    @Composable
    fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit

    @Composable
    fun QrImage(text: String, modifier: Modifier)

    /** Finds Meshtastic radios over Bluetooth LE and lets the user pick one. */
    @Composable
    fun RadioPicker(onPick: (address: String, name: String) -> Unit, onDismiss: () -> Unit)

    /** An action that asks for permission to show alerts (SOS), where the platform needs one. */
    @Composable
    fun rememberNotificationPermission(): () -> Unit

    /** A line under the party's connections about the platform (the web: keep the page open), or null. */
    fun connectionsNote(active: Boolean): String? = null

    /** The party keeps going with the screen off (the apps); a web page only while it's open. */
    val runsInBackground: Boolean get() = true
}

val LocalPartyPlatform = staticCompositionLocalOf<PartyPlatform> { error("No PartyPlatform provided") }
