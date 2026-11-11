package app.maparound.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.maparound.party.MeshtasticTransport
import app.maparound.party.PartyPlatform
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/** The party's platform UI on Android: zxing for QR codes, Bluetooth LE for Meshtastic radios. */
object AndroidPartyPlatform : PartyPlatform {
    @Composable
    override fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
        val callback = rememberUpdatedState(onResult)
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { callback.value(it) }
        }
        return {
            scanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the party QR code")
                    .setBeepEnabled(false).setOrientationLocked(false),
            )
        }
    }

    @Composable
    override fun QrImage(text: String, modifier: Modifier) {
        val qr = remember(text) { runCatching { BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 720, 720) }.getOrNull() }
        qr?.let { Image(it.asImageBitmap(), contentDescription = "Party QR code", modifier = modifier) }
    }

    @Composable
    override fun RadioPicker(onPick: (address: String, name: String) -> Unit, onDismiss: () -> Unit) = BleRadioPicker(onPick, onDismiss)

    @Composable
    override fun rememberNotificationPermission(): () -> Unit {
        val context = LocalContext.current
        // Notifications show SOS alerts; sharing works without them.
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        return {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/** Scans for Meshtastic radios over Bluetooth LE and lets the user pick one. */
@SuppressLint("MissingPermission")
@Composable
private fun BleRadioPicker(onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val needed = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    fun granted() = needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var permitted by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permitted = granted() }
    val found = remember { mutableStateListOf<Pair<String, String>>() } // address, name
    var error by remember { mutableStateOf<String?>(null) }

    if (!permitted) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Bluetooth permission") },
            text = { Text("MapAround needs Bluetooth access to find and talk to your Meshtastic radio.") },
            confirmButton = { TextButton(onClick = { launcher.launch(needed) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    DisposableEffect(Unit) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                if (found.none { it.first == addr }) found += addr to (result.scanRecord?.deviceName ?: result.device.name ?: addr)
            }

            override fun onScanFailed(errorCode: Int) {
                error = "Scan failed ($errorCode)"
            }
        }
        if (scanner == null) {
            error = "Turn on Bluetooth first"
        } else {
            // Already-paired radios may not advertise while connected elsewhere; list them too.
            adapter.bondedDevices.orEmpty()
                .filter { d -> d.uuids?.any { it.uuid == MeshtasticTransport.SERVICE } == true || (d.name ?: "").contains("Meshtastic", true) }
                .forEach { d -> found += d.address to (d.name ?: d.address) }
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(MeshtasticTransport.SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback,
            )
        }
        onDispose { runCatching { scanner?.stopScan(callback) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair a Meshtastic radio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Switch the radio on. All radios in the party must use the same Meshtastic region and channel (the default one works; " +
                        "party messages are encrypted separately). Close the official Meshtastic app if it is connected to the radio.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (found.isEmpty() && error == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Searching…")
                    }
                }
                found.forEach { (addr, name) ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(addr, name) },
                        leadingContent = { Icon(Icons.Filled.Router, contentDescription = null) },
                        headlineContent = { Text(name) },
                        supportingContent = { Text(addr) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

