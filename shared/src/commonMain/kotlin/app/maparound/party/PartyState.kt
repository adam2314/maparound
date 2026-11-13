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
)

/** The party session as the shared screens drive it (Android: PartySession with MQTT and BLE). */
interface PartyController {
    val state: StateFlow<PartyState>

    /** Joins, leaves and SOS alerts, for the UI to announce. */
    val events: SharedFlow<PartyEvent>

    /** An invite opened from a link or QR code, waiting for the user to confirm. */
    val pendingInvite: MutableStateFlow<PartyKey?>

    /**
     * Only watches the party (the web app): sees the members, the meeting point, SOS alerts and
     * messages, and sends nothing, so it shares no position and the others don't see it. Creating
     * a party, [join]'s name and the sending calls do nothing.
     */
    val watchOnly: Boolean get() = false

    fun create(partyName: String, myName: String)
    fun join(key: PartyKey, myName: String)
    fun leave()
    fun setActive(on: Boolean)
    fun setSharing(on: Boolean)
    fun setProfile(name: String, color: Int, icon: Int)
    fun setOnlineEnabled(on: Boolean)
    fun setGateway(on: Boolean)
    fun setRadio(address: String, name: String)
    fun forgetRadio()
    fun sos(active: Boolean, text: String = "")
    fun setMeeting(lat: Double, lon: Double, label: String)
    fun clearMeeting()
    fun sendText(text: String)
}

val LocalParty = staticCompositionLocalOf<PartyController> { error("No PartyController provided") }

/** The party features that need the platform's own UI. */
interface PartyPlatform {
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
}

val LocalPartyPlatform = staticCompositionLocalOf<PartyPlatform> { error("No PartyPlatform provided") }
