package com.example.bulbcontrol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "BleBulbLink"
private const val SCAN_DURATION_MS = 10_000L
private const val WRITE_TIMEOUT_MS = 1_000L

/**
 * Real Bluetooth LE implementation.
 *
 * Permissions are requested by MainActivity before any method here is called,
 * hence @SuppressLint("MissingPermission"). SecurityExceptions are still caught
 * and shown as errors in case a permission was revoked.
 *
 * All state is touched only on the main thread (GATT callbacks are posted there),
 * and writes are queued because Android allows only one GATT operation at a time.
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
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var protocol: BulbProtocol? = null
    private val queue = ArrayDeque<BulbCommand>()
    private var writing = false

    // ---------- Scanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: safe { device.name } ?: return
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
        stopScan()
        disconnect()
        val adapter = adapter ?: return fail("This device has no Bluetooth")
        val device = adapter.getRemoteDevice(address)
        val name = found[address]?.name ?: address
        _state.update {
            it.copy(status = LinkStatus.Connecting, deviceName = name, protocolName = null, error = null)
        }
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
        val g = gatt ?: return
        safe { g.disconnect() }
        safe { g.close() }
        resetConnection()
        _state.update { it.copy(status = LinkStatus.Disconnected, protocolName = null) }
    }

    private fun resetConnection() {
        gatt = null
        writeChar = null
        protocol = null
        queue.clear()
        writing = false
        main.removeCallbacks(writeTimeout)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) {
                    safe { g.close() }
                    return@post
                }
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.update { it.copy(status = LinkStatus.Discovering) }
                    safe { g.discoverServices() }
                } else {
                    safe { g.close() }
                    resetConnection()
                    val error = if (status != BluetoothGatt.GATT_SUCCESS) {
                        "Connection lost (GATT status $status). Move closer and try again."
                    } else null
                    _state.update { it.copy(status = LinkStatus.Disconnected, protocolName = null, error = error) }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val match = findWriteTarget(g)
                if (match == null) {
                    val services = g.services.joinToString { it.uuid.toString().substring(4, 8) }
                    Log.w(TAG, "No supported protocol. Services: ${g.services.map { it.uuid }}")
                    disconnect()
                    _state.update {
                        it.copy(error = "This bulb's protocol isn't supported yet. Services found: $services")
                    }
                    return@post
                }
                protocol = match.first
                writeChar = match.second
                _state.update { it.copy(status = LinkStatus.Connected, protocolName = match.first.name) }
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Write failed, status $status")
                writing = false
                main.removeCallbacks(writeTimeout)
                pump()
            }
        }
    }

    private fun findWriteTarget(g: BluetoothGatt): Pair<BulbProtocol, BluetoothGattCharacteristic>? {
        for (p in BulbProtocols.all) {
            for ((serviceId, charId) in p.writeTargets) {
                val ch = g.getService(serviceId)?.getCharacteristic(charId)
                if (ch != null) return p to ch
            }
        }
        return null
    }

    // ---------- Writing ----------

    override fun send(command: BulbCommand) {
        main.post {
            if (writeChar == null) return@post
            // Slider drags produce many commands; keep only the newest of each kind.
            queue.removeAll { it::class == command::class }
            queue.addLast(command)
            pump()
        }
    }

    private val writeTimeout = Runnable {
        Log.w(TAG, "Write timed out, continuing")
        writing = false
        pump()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (writing) return
        val g = gatt ?: return
        val ch = writeChar ?: return
        val p = protocol ?: return
        val command = queue.removeFirstOrNull() ?: return
        val bytes = p.encode(command)
        val type =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
        val ok = safe {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, bytes, type) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = type
                ch.value = bytes
                g.writeCharacteristic(ch)
            }
        } ?: false

        if (ok) {
            writing = true
            main.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
            _state.update { it.copy(lastSent = bytes.toHex()) }
        } else {
            Log.w(TAG, "Write rejected for $command, dropping it")
            main.post { pump() }
        }
    }

    // ---------- Helpers ----------

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
