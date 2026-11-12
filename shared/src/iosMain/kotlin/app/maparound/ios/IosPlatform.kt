package app.maparound.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import app.maparound.party.PartyController
import app.maparound.party.PartyEvent
import app.maparound.party.PartyKey
import app.maparound.party.PartyPlatform
import app.maparound.party.PartyState
import app.maparound.places.Place
import app.maparound.places.Places
import app.maparound.raster.RasterLibrary
import app.maparound.raster.RasterMap
import app.maparound.share.RouteText
import app.maparound.share.SharedLinks
import app.maparound.shared.fileSystem
import app.maparound.shared.util.urlEncode
import app.maparound.tracks.Gpx
import app.maparound.tracks.Track
import app.maparound.ui.PlatformActions
import app.maparound.ui.RecordingControl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/** The share sheet and Apple Maps for the shared screens. */
object IosPlatformActions : PlatformActions {
    override fun shareText(text: String, title: String) = share(listOf(text))

    override fun sharePointText(lat: Double, lon: Double, label: String?) = share(listOf(SharedLinks.pointText(lat, lon, label)))

    override fun sharePointGpx(lat: Double, lon: Double, label: String?) {
        val file = writeShared(label ?: "location") { Gpx.writeWaypoint(label, lat, lon, it) }
        share(listOf(SharedLinks.pointText(lat, lon, label), file))
    }

    override fun openPointElsewhere(lat: Double, lon: Double, label: String?) {
        val q = urlEncode(label ?: "Location")
        val url = NSURL(string = "https://maps.apple.com/?ll=$lat,$lon&q=$q")
        UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
    }

    override fun sharePlaces(places: List<Place>, title: String) {
        val file = writeShared(title) { Places.writeGpx(places, it, title) }
        val text = if (places.size == 1) SharedLinks.pointText(places[0].lat, places[0].lon, places[0].name)
        else "$title: ${places.size} places (GPX attached)"
        share(listOf(text, file))
    }

    override fun shareRoute(track: Track, gpxPath: String?) {
        val file = gpxPath?.takeIf { fileSystem.exists(it.toPath()) }?.let { NSURL.fileURLWithPath(it) }
            ?: writeShared(track.name) { Gpx.write(track, it) }
        share(listOf(RouteText.of(track), file))
    }

    override fun shareRouteLink(track: Track) = share(listOf(RouteText.of(track)))

    override fun shareTextFile(fileName: String, mimeType: String, content: String, title: String) {
        val dir = NSTemporaryDirectory() + "shared"
        fileSystem.createDirectories(dir.toPath())
        val path = "$dir/${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        fileSystem.write(path.toPath()) { writeUtf8(content) }
        share(listOf(NSURL.fileURLWithPath(path)))
    }

    /** Writes a GPX file to share into the temporary folder. */
    private fun writeShared(name: String, write: (Appendable) -> Unit): NSURL {
        val dir = NSTemporaryDirectory() + "shared"
        fileSystem.createDirectories(dir.toPath())
        val path = "$dir/${slug(name)}.gpx"
        val text = StringBuilder().also(write).toString()
        fileSystem.write(path.toPath()) { writeUtf8(text) }
        return NSURL.fileURLWithPath(path)
    }

    private fun slug(name: String): String =
        name.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }.trim().replace(' ', '_').take(50).ifEmpty { "shared" }

    private fun share(items: List<Any>) {
        val top = topViewController() ?: return
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        sheet.popoverPresentationController?.sourceView = top.view // iPad
        top.presentViewController(sheet, animated = true, completion = null)
    }
}

/** The view controller on top, to present sheets from. */
fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

private const val LATER = "Coming to iPhone in a later version"

/** Track recording in the background comes in phase 4. */
object PendingRecording : RecordingControl {
    @Composable
    override fun rememberStarter(hasLocation: Boolean, requestLocation: () -> Unit): () -> Unit = remember { {} }
    override fun stop() = Unit
}

/** Raster maps (RMAP) come in phase 4. */
object PendingRasters : RasterLibrary {
    override val maps: StateFlow<List<RasterMap>> = MutableStateFlow(emptyList())
    override fun get(id: String): RasterMap? = null
    override fun remove(id: String) = Unit

    @Composable
    override fun rememberPicker(onResult: (Result<RasterMap>) -> Unit): () -> Unit {
        val done = rememberUpdatedState(onResult)
        return remember { { done.value(Result.failure(Exception("Raster maps: $LATER"))) } }
    }
}

/** The party comes in phase 5 (its tab is hidden until then); the map reads an empty party. */
object PendingParty : PartyController {
    override val state: StateFlow<PartyState> = MutableStateFlow(PartyState())
    override val events: SharedFlow<PartyEvent> = MutableSharedFlow()
    override val pendingInvite = MutableStateFlow<PartyKey?>(null)
    override fun create(partyName: String, myName: String) = Unit
    override fun join(key: PartyKey, myName: String) = Unit
    override fun leave() = Unit
    override fun setActive(on: Boolean) = Unit
    override fun setSharing(on: Boolean) = Unit
    override fun setProfile(name: String, color: Int, icon: Int) = Unit
    override fun setOnlineEnabled(on: Boolean) = Unit
    override fun setGateway(on: Boolean) = Unit
    override fun setRadio(address: String, name: String) = Unit
    override fun forgetRadio() = Unit
    override fun sos(active: Boolean, text: String) = Unit
    override fun setMeeting(lat: Double, lon: Double, label: String) = Unit
    override fun clearMeeting() = Unit
    override fun sendText(text: String) = Unit
}

object PendingPartyPlatform : PartyPlatform {
    @Composable
    override fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit = remember { {} }

    @Composable
    override fun QrImage(text: String, modifier: Modifier) {
        Box(modifier) { Text(text) }
    }

    @Composable
    override fun RadioPicker(onPick: (address: String, name: String) -> Unit, onDismiss: () -> Unit) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    @Composable
    override fun rememberNotificationPermission(): () -> Unit = remember { {} }
}
