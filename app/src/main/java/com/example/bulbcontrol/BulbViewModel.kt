package com.example.bulbcontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bulbcontrol.ble.BleBulbLink
import com.example.bulbcontrol.ble.BulbCommand
import com.example.bulbcontrol.ble.BulbLink
import com.example.bulbcontrol.ble.DemoBulbLink
import com.example.bulbcontrol.ble.LinkState
import com.example.bulbcontrol.ble.LinkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BulbViewModel(app: Application) : AndroidViewModel(app) {

    private val bleLink = BleBulbLink(app)
    private val demoLink = DemoBulbLink()

    /** Demo mode starts on automatically when the device has no Bluetooth at all. */
    private val _demoMode = MutableStateFlow(!BleBulbLink.isSupported(app))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val link: BulbLink get() = if (_demoMode.value) demoLink else bleLink

    val linkState: StateFlow<LinkState> = _demoMode
        .flatMapLatest { demo -> if (demo) demoLink.state else bleLink.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LinkState())

    private val _light = MutableStateFlow(LightState())
    val light: StateFlow<LightState> = _light.asStateFlow()

    init {
        // When a bulb finishes connecting, make it match what the screen shows.
        viewModelScope.launch {
            linkState.map { it.status }.distinctUntilChanged().collect { status ->
                if (status == LinkStatus.Connected) pushFullState()
            }
        }
    }

    fun setDemoMode(enabled: Boolean) {
        if (enabled == _demoMode.value) return
        link.stopScan()
        link.disconnect()
        _demoMode.value = enabled
    }

    fun startScan() = link.startScan()
    fun stopScan() = link.stopScan()
    fun connect(address: String) = link.connect(address)
    fun disconnect() = link.disconnect()

    fun setPower(on: Boolean) {
        _light.update { it.copy(on = on) }
        link.send(BulbCommand.Power(on))
        if (on) link.send(_light.value.colorCommand())
    }

    fun setHue(hue: Float) = updateColor { it.copy(hue = hue, whiteMode = false) }
    fun setSaturation(saturation: Float) = updateColor { it.copy(saturation = saturation, whiteMode = false) }
    fun setBrightness(brightness: Float) = updateColor { it.copy(brightness = brightness.coerceIn(0.05f, 1f)) }

    fun applyPreset(preset: Preset) = updateColor {
        if (preset.white) it.copy(whiteMode = true)
        else it.copy(hue = preset.hue, saturation = preset.saturation, whiteMode = false)
    }

    private fun updateColor(change: (LightState) -> LightState) {
        _light.update(change)
        val state = _light.value
        if (state.on) link.send(state.colorCommand())
    }

    private fun pushFullState() {
        val state = _light.value
        link.send(BulbCommand.Power(state.on))
        if (state.on) link.send(state.colorCommand())
    }

    override fun onCleared() {
        bleLink.stopScan()
        bleLink.disconnect()
        demoLink.disconnect()
    }
}

data class Preset(val label: String, val hue: Float = 0f, val saturation: Float = 1f, val white: Boolean = false)

val PRESETS = listOf(
    Preset("White", white = true),
    Preset("Warm", hue = 30f, saturation = 0.7f),
    Preset("Red", hue = 0f),
    Preset("Green", hue = 120f),
    Preset("Blue", hue = 240f),
    Preset("Purple", hue = 285f),
)
