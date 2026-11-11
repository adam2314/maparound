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
import app.maparound.data.LocalPrefs
import app.maparound.sensors.AndroidSensors
import app.maparound.sensors.LocalDeviceSensors
import app.maparound.ui.MapAroundRoot
import app.maparound.ui.theme.MapAroundTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sensors = AndroidSensors(this)
        setContent {
            // What the shared screens get from the platform.
            CompositionLocalProvider(LocalPrefs provides AppGraph.prefs, LocalDeviceSensors provides sensors) {
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
