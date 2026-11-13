package app.maparound.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import app.maparound.places.Place
import app.maparound.share.Sharing
import app.maparound.tracks.Recorder
import app.maparound.tracks.Track
import java.io.File

/** The shared screens' [PlatformActions] on Android (share sheet and chooser through [Sharing]). */
class AndroidPlatform(private val context: Context) : PlatformActions {
    override fun shareText(text: String, title: String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
    }

    override fun sharePointText(lat: Double, lon: Double, label: String?) = Sharing.sharePointText(context, lat, lon, label)
    override fun sharePointGpx(lat: Double, lon: Double, label: String?) = Sharing.sharePointGpx(context, lat, lon, label)
    override fun openPointElsewhere(lat: Double, lon: Double, label: String?) = Sharing.openPointElsewhere(context, lat, lon, label)

    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
    override fun sharePlaces(places: List<Place>, title: String) = Sharing.sharePlaces(context, places, title)
    override fun shareRoute(track: Track, gpxPath: String?) = Sharing.shareRoute(context, track, gpxPath?.let(::File))
    override fun shareRouteLink(track: Track) = Sharing.shareRouteLink(context, track)
    override fun shareTextFile(fileName: String, mimeType: String, content: String, title: String) =
        Sharing.shareTextFile(context, fileName, mimeType, content, title)
}

/** GPS recording on Android: the foreground [Recorder] service. */
class AndroidRecordingControl(private val context: Context) : RecordingControl {
    @Composable
    override fun rememberStarter(hasLocation: Boolean, requestLocation: () -> Unit): () -> Unit {
        // The recording notification needs this on Android 13+; recording works either way.
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            Recorder.start(context)
        }
        return {
            when {
                !hasLocation -> requestLocation()
                Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> Recorder.start(context)
            }
        }
    }

    override fun stop() = Recorder.stop(context)
}
