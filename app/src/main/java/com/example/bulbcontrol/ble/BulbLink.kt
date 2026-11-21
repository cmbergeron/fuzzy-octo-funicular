package com.example.bulbcontrol.ble

import kotlinx.coroutines.flow.StateFlow

enum class LinkStatus { Disconnected, Connecting, Discovering, Connected }

data class FoundDevice(val address: String, val name: String, val rssi: Int)

data class LinkState(
    val scanning: Boolean = false,
    val devices: List<FoundDevice> = emptyList(),
    val status: LinkStatus = LinkStatus.Disconnected,
    val deviceName: String? = null,
    val protocolName: String? = null,
    /** Hex of the last bytes written to the bulb, handy for debugging. */
    val lastSent: String? = null,
    val error: String? = null,
    /** Extra progress text, e.g. during first-time mesh setup. */
    val detail: String? = null,
    /** Recent diagnostic lines, newest last. */
    val log: List<String> = emptyList(),
)

/** Something that can find and drive a bulb: real Bluetooth, or the demo fake. */
interface BulbLink {
    val state: StateFlow<LinkState>
    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun send(command: BulbCommand)
}
