package com.example.bulbcontrol.ble

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A pretend bulb so the whole app can be tried on the Android Studio emulator,
 * which can't reach a real Bluetooth bulb. It encodes commands with the real
 * protocol code, so "Last command" shows the exact bytes a real bulb would get.
 */
class DemoBulbLink : BulbLink {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(LinkState())
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val demoDevices = listOf(
        FoundDevice("00:11:22:33:44:01", "Demo Bulb (Triones)", -48),
        FoundDevice("00:11:22:33:44:02", "Demo Bulb (ELK-BLEDOM)", -63),
    )
    private var protocol: BulbProtocol? = null

    override fun startScan() {
        _state.update { it.copy(scanning = true, devices = emptyList(), error = null) }
        main.postDelayed({ _state.update { it.copy(devices = demoDevices.take(1)) } }, 500)
        main.postDelayed({ _state.update { it.copy(devices = demoDevices) } }, 1_000)
        main.postDelayed({ _state.update { it.copy(scanning = false) } }, 2_000)
    }

    override fun stopScan() {
        main.removeCallbacksAndMessages(null)
        _state.update { it.copy(scanning = false) }
    }

    override fun connect(address: String) {
        stopScan()
        val device = demoDevices.firstOrNull { it.address == address } ?: return
        val p = if (device.name.contains("ELK")) ElkBledomProtocol else TrionesProtocol
        _state.update { it.copy(status = LinkStatus.Connecting, deviceName = device.name, error = null) }
        main.postDelayed({ _state.update { it.copy(status = LinkStatus.Discovering) } }, 400)
        main.postDelayed({
            protocol = p
            _state.update { it.copy(status = LinkStatus.Connected, protocolName = p.name) }
        }, 800)
    }

    override fun disconnect() {
        main.removeCallbacksAndMessages(null)
        protocol = null
        _state.update { it.copy(status = LinkStatus.Disconnected, protocolName = null, scanning = false) }
    }

    override fun send(command: BulbCommand) {
        val p = protocol ?: return
        _state.update { it.copy(lastSent = p.encode(command).toHex()) }
    }
}
