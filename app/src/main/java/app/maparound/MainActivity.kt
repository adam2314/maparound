package app.maparound

import android.content.Intent
import android.os.Bundle
import app.maparound.party.PartyKey
import app.maparound.party.PartySession
import app.maparound.share.IncomingShare
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import app.maparound.data.LocalOfflineData
import app.maparound.data.LocalPrefs
import app.maparound.data.OfflineData
import app.maparound.party.LocalParty
import app.maparound.party.LocalPartyPlatform
import app.maparound.raster.LocalRasters
import app.maparound.ui.AndroidPartyPlatform
import app.maparound.sensors.AndroidSensors
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.places.LocalPlaces
import app.maparound.search.SearchService
import app.maparound.tracks.LocalTracks
import app.maparound.ui.AndroidPlatform
import app.maparound.ui.AndroidRecordingControl
import app.maparound.ui.LocalRecordingControl
import app.maparound.ui.LocalPlatform
import app.maparound.ui.LocalSearcher
import app.maparound.ui.MapAroundRoot
import app.maparound.ui.theme.MapAroundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sensors = AndroidSensors(this)
        val platform = AndroidPlatform(this)
        val recording = AndroidRecordingControl(this)
        setContent {
            // What the shared screens get from the platform.
            CompositionLocalProvider(
                LocalPrefs provides AppGraph.prefs,
                LocalDeviceSensors provides sensors,
                LocalPlaces provides AppGraph.places,
                LocalPlatform provides platform,
                LocalSearcher provides SearchService,
                LocalTracks provides AppGraph.tracks,
                LocalRecordingControl provides recording,
                LocalRasters provides AppGraph.rasters,
                LocalParty provides PartySession,
                LocalPartyPlatform provides AndroidPartyPlatform,
                LocalOfflineData provides OfflineData(
                    maps = AppGraph.mapFiles,
                    downloads = AppGraph.downloader,
                    catalog = AppGraph.catalog,
                    terrain = AppGraph.terrain,
                    trails = AppGraph.trails,
                    routing = AppGraph.routing,
                ),
            ) {
                MapAroundTheme {
                    MapAroundRoot()
                }
            }
        }
        // Only for a fresh start: a recreated activity would otherwise import the same file again.
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Party invites (maparound://party/…), locations (geo:, map links, shared text) and files
     * (GPX, mapsforge .map) from other apps.
     */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && data?.scheme == "maparound" && data.host == "party") {
            PartyKey.parse(data.toString())?.let { PartySession.pendingInvite.value = it }
            return
        }
        IncomingShare.handle(this, intent)
    }
}
