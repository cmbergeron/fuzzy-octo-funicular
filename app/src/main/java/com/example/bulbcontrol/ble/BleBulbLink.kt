package com.example.bulbcontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.bulbcontrol.ble.mesh.MeshController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "BleBulbLink"
private const val SCAN_DURATION_MS = 10_000L
private const val OP_TIMEOUT_MS = 2_000L
private const val MAX_LOG_LINES = 60
private const val RECONNECT_ATTEMPTS = 6
private const val RECONNECT_DELAY_MS = 2_500L

private val CCCD: UUID = uuid16(0x2902)
private val MESH_PROVISIONING_SERVICE: UUID = uuid16(0x1827)
private val MESH_PROV_DATA_IN: UUID = uuid16(0x2ADB)
private val MESH_PROV_DATA_OUT: UUID = uuid16(0x2ADC)
private val MESH_PROXY_SERVICE: UUID = uuid16(0x1828)
private val MESH_PROXY_DATA_IN: UUID = uuid16(0x2ADD)
private val MESH_PROXY_DATA_OUT: UUID = uuid16(0x2ADE)

/**
 * Real Bluetooth LE implementation.
 *
 * Talks to two kinds of bulbs:
 *  - "Simple" bulbs (Triones, ELK-BLEDOM, ...) that take raw byte commands, see [BulbProtocol].
 *  - Standard Bluetooth Mesh bulbs (Sylvania/LEDVANCE and others), via [MeshController].
 *
 * Permissions are requested by MainActivity before any method here is called,
 * hence @SuppressLint("MissingPermission"). SecurityExceptions are still caught.
 *
 * All state is touched only on the main thread (GATT callbacks are posted there),
 * and GATT operations are queued because Android allows only one at a time.
 */
@SuppressLint("MissingPermission")
class BleBulbLink(context: Context) : BulbLink {

    companion object {
        fun isSupported(context: Context): Boolean =
            context.getSystemService(BluetoothManager::class.java)?.adapter != null
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LinkState())
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val found = LinkedHashMap<String, FoundDevice>()
    /** Mesh provisioning service data seen while scanning, by address (holds the device UUID). */
    private val meshServiceData = HashMap<String, ByteArray>()

    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var mtu = 23

    /** What we're talking to on the current connection. */
    private sealed interface Driver {
        data class Simple(val protocol: BulbProtocol, val characteristic: BluetoothGattCharacteristic) : Driver
        data class Mesh(val dataIn: BluetoothGattCharacteristic, val dataOut: BluetoothGattCharacteristic) : Driver
    }
    private var driver: Driver? = null
    private var meshReady = false
    private var reconnectAttemptsLeft = 0
    private var refreshCacheOnConnect = false

    private val meshLazy = lazy { MeshController(appContext, meshHost) }
    private val mesh: MeshController by meshLazy
    private val reconnectToken = Any()

    // ---------- GATT operation queue ----------

    private sealed interface GattOp {
        class Write(
            val characteristic: BluetoothGattCharacteristic,
            val data: ByteArray,
            val isMesh: Boolean,
        ) : GattOp
        class EnableNotifications(val characteristic: BluetoothGattCharacteristic) : GattOp
    }

    private val ops = ArrayDeque<GattOp>()
    private val pendingCommands = ArrayDeque<BulbCommand>()
    private var currentOp: GattOp? = null

    // ---------- Scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val record = result.scanRecord
            record?.getServiceData(ParcelUuid(MESH_PROVISIONING_SERVICE))?.let {
                meshServiceData[device.address] = it
            }
            val name = record?.deviceName ?: safe { device.name } ?: return
            found[device.address] = FoundDevice(device.address, name, result.rssi)
            _state.update { s -> s.copy(devices = found.values.sortedByDescending { it.rssi }) }
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(scanning = false, error = "Scan failed (code $errorCode)") }
        }
    }

    private val stopScanRunnable = Runnable { stopScan() }

    override fun startScan() {
        val adapter = adapter ?: return fail("This device has no Bluetooth")
        if (!adapter.isEnabled) return fail("Bluetooth is turned off")
        val scanner = adapter.bluetoothLeScanner ?: return fail("Bluetooth LE scanner unavailable")
        if (_state.value.scanning) return

        found.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: SecurityException) {
            return fail("Missing Bluetooth permission")
        }
        _state.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        main.postDelayed(stopScanRunnable, SCAN_DURATION_MS)
    }

    override fun stopScan() {
        main.removeCallbacks(stopScanRunnable)
        if (!_state.value.scanning) return
        safe { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        _state.update { it.copy(scanning = false) }
    }

    // ---------- Connecting ----------

    override fun connect(address: String) {
        reconnectAttemptsLeft = 0
        refreshCacheOnConnect = false
        _state.update { it.copy(log = emptyList()) }
        openConnection(address)
    }

    private fun openConnection(address: String) {
        stopScan()
        closeGatt()
        val adapter = adapter ?: return fail("This device has no Bluetooth")
        val device = adapter.getRemoteDevice(address)
        val name = found[address]?.name ?: _state.value.deviceName ?: address
        this.address = address
        _state.update {
            it.copy(status = LinkStatus.Connecting, deviceName = name, protocolName = null, error = null)
        }
        log("Connecting to $name ($address)")
        gatt = try {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            null
        }
        if (gatt == null) {
            _state.update { it.copy(status = LinkStatus.Disconnected, error = "Could not connect to $name") }
        }
    }

    override fun disconnect() {
        reconnectAttemptsLeft = 0
        main.removeCallbacksAndMessages(reconnectToken)
        closeGatt()
        _state.update { it.copy(status = LinkStatus.Disconnected, protocolName = null, detail = null) }
    }

    private fun closeGatt() {
        val g = gatt ?: return
        safe { g.disconnect() }
        safe { g.close() }
        gatt = null
        driver = null
        meshReady = false
        ops.clear()
        pendingCommands.clear()
        currentOp = null
        main.removeCallbacks(opTimeout)
        if (meshLazy.isInitialized()) mesh.stop()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) {
                    safe { g.close() }
                    return@post
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    log("Connected, requesting MTU")
                    _state.update { it.copy(status = LinkStatus.Discovering) }
                    if (refreshCacheOnConnect) refreshGattCache(g)
                    val requested = safe { g.requestMtu(517) } ?: false
                    if (!requested) safe { g.discoverServices() }
                } else {
                    log("Disconnected (status $status)")
                    closeGatt()
                    if (reconnectAttemptsLeft > 0) {
                        scheduleReconnect()
                        return@post
                    }
                    val error = if (status != BluetoothGatt.GATT_SUCCESS) {
                        "Connection lost (GATT status $status). Move closer and try again."
                    } else null
                    _state.update {
                        it.copy(status = LinkStatus.Disconnected, protocolName = null, detail = null, error = error)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                log("MTU $mtu")
                safe { g.discoverServices() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                reconnectAttemptsLeft = 0
                log("Services: " + g.services.joinToString { shortUuid(it.uuid) })
                chooseDriver(g)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) log("Write failed, status $status")
                opFinished()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) log("Enable notifications failed, status $status")
                opFinished()
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            main.post { onNotification(g, characteristic, copy) }
        }

        @Deprecated("Used on Android 12 and lower")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val copy = characteristic.value?.copyOf() ?: return
            main.post { onNotification(g, characteristic, copy) }
        }
    }

    private fun onNotification(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (g != gatt) return
        val d = driver as? Driver.Mesh ?: return
        if (characteristic.uuid == d.dataOut.uuid) mesh.handleNotification(value)
    }

    private fun chooseDriver(g: BluetoothGatt) {
        // 1. Simple byte-command bulbs
        for (p in BulbProtocols.all) {
            for ((serviceId, charId) in p.writeTargets) {
                val ch = g.getService(serviceId)?.getCharacteristic(charId) ?: continue
                driver = Driver.Simple(p, ch)
                _state.update { it.copy(status = LinkStatus.Connected, protocolName = p.name, detail = null) }
                log("Using ${p.name}")
                return
            }
        }

        // 2. Standard Bluetooth Mesh bulbs
        val address = address ?: return
        val prov = g.getService(MESH_PROVISIONING_SERVICE)
        val proxy = g.getService(MESH_PROXY_SERVICE)
        val provIn = prov?.getCharacteristic(MESH_PROV_DATA_IN)
        val provOut = prov?.getCharacteristic(MESH_PROV_DATA_OUT)
        val proxyIn = proxy?.getCharacteristic(MESH_PROXY_DATA_IN)
        val proxyOut = proxy?.getCharacteristic(MESH_PROXY_DATA_OUT)

        if (proxyIn != null && proxyOut != null && (mesh.isOurs(address) || provIn == null)) {
            log("Bluetooth Mesh bulb (proxy)")
            driver = Driver.Mesh(proxyIn, proxyOut)
            _state.update { it.copy(protocolName = "Bluetooth Mesh", detail = "Connecting to mesh...") }
            ops.addLast(GattOp.EnableNotifications(proxyOut))
            pump()
            mesh.startProxy(address)
            return
        }
        if (provIn != null && provOut != null) {
            if (mesh.isOurs(address)) {
                // We provisioned it before but it's advertising as new again: it was reset.
                log("Bulb was reset since last setup; setting it up again")
            }
            log("Bluetooth Mesh bulb (not set up yet)")
            driver = Driver.Mesh(provIn, provOut)
            _state.update { it.copy(protocolName = "Bluetooth Mesh") }
            ops.addLast(GattOp.EnableNotifications(provOut))
            pump()
            val uuid = meshServiceData[address]
                ?.takeIf { it.size >= 16 }
                ?.let { data -> runCatching { uuidFromBytes(data) }.getOrNull() }
                ?: UUID.randomUUID()
            mesh.startProvisioning(address, uuid)
            return
        }

        // 3. Unknown
        val services = g.services.joinToString { shortUuid(it.uuid) }
        Log.w(TAG, "No supported protocol. Services: ${g.services.map { it.uuid }}")
        disconnect()
        _state.update { it.copy(error = "This bulb's protocol isn't supported yet. Services found: $services") }
    }

    private val meshHost = object : MeshController.Host {
        override val meshMtu: Int get() = mtu - 3

        override fun sendMeshPdu(pdu: ByteArray) {
            val d = driver as? Driver.Mesh ?: return
            // The library already added GATT segmentation headers; split into MTU-sized writes.
            val chunk = meshMtu
            var offset = 0
            while (offset < pdu.size) {
                val end = minOf(offset + chunk, pdu.size)
                ops.addLast(GattOp.Write(d.dataIn, pdu.copyOfRange(offset, end), isMesh = true))
                offset = end
            }
            pump()
        }

        override fun onMeshProgress(message: String) {
            _state.update { it.copy(detail = message) }
        }

        override fun onMeshReady() {
            meshReady = true
            _state.update { it.copy(status = LinkStatus.Connected, detail = null) }
            pump()
        }

        override fun onMeshFailed(message: String) {
            log("Mesh error: $message")
            disconnect()
            _state.update { it.copy(error = message) }
        }

        override fun onMeshProvisioned() {
            // The bulb restarts as a mesh node and advertises the proxy service instead.
            val address = address ?: return
            closeGatt()
            reconnectAttemptsLeft = RECONNECT_ATTEMPTS
            refreshCacheOnConnect = true
            this@BleBulbLink.address = address
            _state.update { it.copy(status = LinkStatus.Connecting) }
            scheduleReconnect()
        }

        override fun log(message: String) = this@BleBulbLink.log(message)
    }

    private fun scheduleReconnect() {
        val address = address ?: return
        reconnectAttemptsLeft--
        log("Reconnecting in ${RECONNECT_DELAY_MS / 1000}s (${reconnectAttemptsLeft} tries left)")
        main.postAtTime({ openConnection(address) }, reconnectToken,
            android.os.SystemClock.uptimeMillis() + RECONNECT_DELAY_MS)
    }

    /** Android caches a device's services; after mesh setup they change, so clear the cache. */
    private fun refreshGattCache(g: BluetoothGatt) {
        runCatching {
            val method = g.javaClass.getMethod("refresh")
            method.invoke(g)
            log("Cleared GATT cache")
        }
    }

    // ---------- Writing ----------

    override fun send(command: BulbCommand) {
        main.post {
            if (driver == null) return@post
            // Slider drags produce many commands; keep only the newest of each kind.
            pendingCommands.removeAll { it::class == command::class }
            pendingCommands.addLast(command)
            pump()
        }
    }

    private val opTimeout = Runnable {
        log("GATT operation timed out, continuing")
        opFinished()
    }

    private fun opFinished() {
        val op = currentOp
        currentOp = null
        main.removeCallbacks(opTimeout)
        if (op is GattOp.Write && op.isMesh) mesh.handleWriteComplete(op.data)
        pump()
    }

    private fun pump() {
        if (currentOp != null) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: nextCommandOp() ?: return
        val started = when (op) {
            is GattOp.Write -> write(g, op)
            is GattOp.EnableNotifications -> enableNotifications(g, op.characteristic)
        }
        if (started) {
            currentOp = op
            main.postDelayed(opTimeout, OP_TIMEOUT_MS)
        } else {
            log("GATT operation rejected, skipping")
            main.post { pump() }
        }
    }

    /** Turns the next queued light command into a GATT write. */
    private fun nextCommandOp(): GattOp? {
        return when (val d = driver) {
            is Driver.Simple -> {
                val command = pendingCommands.removeFirstOrNull() ?: return null
                val bytes = d.protocol.encode(command)
                _state.update { it.copy(lastSent = bytes.toHex()) }
                GattOp.Write(d.characteristic, bytes, isMesh = false)
            }
            is Driver.Mesh -> {
                if (!meshReady) return null
                val command = pendingCommands.removeFirstOrNull() ?: return null
                _state.update { it.copy(lastSent = "mesh: $command") }
                mesh.send(command) // enqueues PDUs via meshHost.sendMeshPdu
                ops.removeFirstOrNull()
            }
            null -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun write(g: BluetoothGatt, op: GattOp.Write): Boolean {
        val ch = op.characteristic
        val type =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        return safe {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, op.data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = type
                ch.value = op.data
                g.writeCharacteristic(ch)
            }
        } ?: false
    }

    @Suppress("DEPRECATION")
    private fun enableNotifications(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
        if (safe { g.setCharacteristicNotification(ch, true) } != true) return false
        val descriptor = ch.getDescriptor(CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return safe {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = value
                g.writeDescriptor(descriptor)
            }
        } ?: false
    }

    // ---------- Helpers ----------

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    private fun log(message: String) {
        Log.i(TAG, message)
        val line = "${timeFormat.format(Date())} $message"
        _state.update { it.copy(log = (it.log + line).takeLast(MAX_LOG_LINES)) }
    }

    private fun fail(message: String) {
        _state.update { it.copy(scanning = false, error = message) }
    }

    private inline fun <T> safe(block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission missing", e)
            null
        }
}

/** Short form for standard 16-bit UUIDs, full form for custom 128-bit ones. */
internal fun shortUuid(uuid: UUID): String {
    val s = uuid.toString()
    return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s
}

private fun uuidFromBytes(data: ByteArray): UUID {
    val bb = java.nio.ByteBuffer.wrap(data, 0, 16)
    return UUID(bb.long, bb.long)
}
