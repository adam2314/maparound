package app.maparound.party

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import app.maparound.i18n.T
import java.util.UUID
import kotlin.random.Random

/**
 * Talks to a Meshtastic LoRa radio over Bluetooth LE (the official "phone API"):
 * write ToRadio, read FromRadio until empty, get notified on FromNum. MapAround's encrypted
 * party packets travel as PRIVATE_APP payloads on the radio's primary channel; positions that
 * Meshtastic radios broadcast themselves are reported too (e.g. a standalone tracker in a car).
 *
 * All GATT calls happen on the main thread, one at a time (Android allows one outstanding op).
 */
@SuppressLint("MissingPermission")
class MeshtasticTransport(
    private val context: Context,
    private val address: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: TransportStatus)
        fun onMyNode(num: Long)
        fun onPartyPacket(payload: ByteArray)
        fun onNode(num: Long, longName: String?, shortName: String?, position: Mesh.Position?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var toRadio: BluetoothGattCharacteristic? = null
    private var fromRadio: BluetoothGattCharacteristic? = null
    private var fromNum: BluetoothGattCharacteristic? = null

    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var draining = false
    private var configured = false
    private var stopped = true
    private var retries = 0
    private val outbox = ArrayDeque<ByteArray>()
    private var bondReceiver: BroadcastReceiver? = null

    val isReady: Boolean get() = configured

    fun start() {
        if (!stopped) return
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        bondReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        bondReceiver = null
        closeGatt()
        listener.onStatus(TransportStatus(TransportStatus.State.OFF))
    }

    /** Broadcasts a MapAround party packet over LoRa. Queued while (re)connecting. */
    fun send(payload: ByteArray) = main.post {
        val packet = Mesh.sendPacket(payload, Mesh.PORT_PRIVATE, Random.nextInt())
        if (!configured) {
            if (outbox.size >= 10) outbox.removeFirst()
            outbox.addLast(packet)
        } else {
            enqueue { write(toRadio!!, packet) }
        }
    }

    // ---- connection --------------------------------------------------------------------------

    private fun connect() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            status(TransportStatus.State.ERROR, T.party.bluetoothOff)
            retryLater()
            return
        }
        val device = a.getRemoteDevice(address)
        status(TransportStatus.State.CONNECTING, T.party.connectingToRadio(device.name ?: address))
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun closeGatt() {
        configured = false
        busy = false
        draining = false
        ops.clear()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun retryLater() {
        if (stopped) return
        retries++
        val delay = (5_000L * retries).coerceAtMost(60_000L)
        main.postDelayed({ if (!stopped) connect() }, delay)
    }

    /** Meshtastic radios require pairing (PIN on the radio's screen, or 123456 on boards without one). */
    private fun ensureBond(device: BluetoothDevice) {
        if (device.bondState != BluetoothDevice.BOND_NONE || bondReceiver != null) return
        status(TransportStatus.State.CONNECTING, T.party.pairWithRadioPin)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (d?.address != address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> {
                        runCatching { context.unregisterReceiver(this) }
                        bondReceiver = null
                        closeGatt()
                        main.postDelayed({ if (!stopped) connect() }, 500)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        runCatching { context.unregisterReceiver(this) }
                        bondReceiver = null
                        status(TransportStatus.State.ERROR, T.party.pairingFailed)
                    }
                }
            }
        }
        bondReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        device.createBond()
    }

    // ---- GATT operation queue ----------------------------------------------------------------

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!busy) next()
    }

    private fun next() {
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            if (op()) {
                busy = true
                return
            }
        }
        busy = false
    }

    private fun opDone() = main.post {
        busy = false
        next()
    }

    private fun write(c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = value
                g.writeCharacteristic(c)
            }
        }
    }

    private fun read(c: BluetoothGattCharacteristic): Boolean = gatt?.readCharacteristic(c) ?: false

    private fun enableNotify(c: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(CCCD) ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
    }

    /** Reads FromRadio until the radio returns an empty packet. */
    private fun drain() {
        if (draining) return
        val c = fromRadio ?: return
        draining = true
        enqueue { read(c) }
    }

    // ---- incoming ------------------------------------------------------------------------------

    private fun onFromRadio(bytes: ByteArray) {
        when (val f = runCatching { Mesh.parseFromRadio(bytes) }.getOrNull()) {
            is Mesh.FromRadio.MyInfo -> listener.onMyNode(f.myNodeNum)
            is Mesh.FromRadio.Node -> listener.onNode(f.info.num, f.info.user?.longName, f.info.user?.shortName, f.info.position)
            is Mesh.FromRadio.ConfigComplete -> if (!configured) {
                configured = true
                retries = 0
                fromNum?.let { c -> enqueue { enableNotify(c) } }
                status(TransportStatus.State.CONNECTED, gatt?.device?.name ?: address)
                while (outbox.isNotEmpty()) {
                    val p = outbox.removeFirst()
                    enqueue { write(toRadio!!, p) }
                }
            }
            is Mesh.FromRadio.PacketIn -> {
                val p = f.packet
                when (p.portnum) {
                    Mesh.PORT_PRIVATE -> listener.onPartyPacket(p.payload)
                    Mesh.PORT_POSITION -> listener.onNode(p.from, null, null, runCatching { Mesh.parsePosition(PbReader(p.payload)) }.getOrNull())
                    Mesh.PORT_NODEINFO -> runCatching { Mesh.parseUser(PbReader(p.payload)) }.getOrNull()
                        ?.let { u -> listener.onNode(p.from, u.longName, u.shortName, null) }
                }
            }
            else -> Unit
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = main.post {
            if (g != gatt) return@post
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(512)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasConfigured = configured
                    closeGatt()
                    if (!stopped) {
                        status(TransportStatus.State.CONNECTING, if (wasConfigured) T.party.radioOutOfRange else T.party.couldntConnectRetrying)
                        retryLater()
                    }
                }
            }
        }.let { }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = main.post {
            if (g == gatt) g.discoverServices()
        }.let { }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = main.post {
            if (g != gatt) return@post
            val service = g.getService(SERVICE)
            if (service == null) {
                status(TransportStatus.State.ERROR, T.party.notAMeshtasticRadio)
                stopped = true
                closeGatt()
                return@post
            }
            toRadio = service.getCharacteristic(TO_RADIO)
            fromRadio = service.getCharacteristic(FROM_RADIO)
            fromNum = service.getCharacteristic(FROM_NUM)
            ensureBond(g.device)
            // Start a session: the radio answers with my info, its node list and config.
            enqueue { write(toRadio!!, Mesh.wantConfig(Random.nextInt())) }
            drain()
        }.let { }

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            handleRead(g, c, value, status)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            handleRead(g, c, c.value ?: ByteArray(0), status)
        }

        private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    draining = false
                    if (status == 5 || status == 15) ensureBond(g.device) // insufficient authentication / encryption
                } else if (c.uuid == FROM_RADIO) {
                    if (value.isEmpty()) {
                        draining = false
                    } else {
                        onFromRadio(value)
                        ops.addFirst { read(c) } // keep reading until empty
                    }
                }
            }
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == 5 || status == 15) main.post { ensureBond(g.device) }
            opDone()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opDone()
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == FROM_NUM) main.post { if (g == gatt) drain() }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == FROM_NUM) main.post { if (g == gatt) drain() }
        }
    }

    private fun status(state: TransportStatus.State, detail: String) = listener.onStatus(TransportStatus(state, detail))

    companion object {
        val SERVICE: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
        val TO_RADIO: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
        val FROM_RADIO: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
        val FROM_NUM: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
