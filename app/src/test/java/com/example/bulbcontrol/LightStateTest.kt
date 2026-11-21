package com.example.bulbcontrol

import com.example.bulbcontrol.ble.BulbCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class LightStateTest {

    @Test
    fun primary_hues() {
        assertEquals(Triple(255, 0, 0), hsvToRgb(0f, 1f, 1f))
        assertEquals(Triple(0, 255, 0), hsvToRgb(120f, 1f, 1f))
        assertEquals(Triple(0, 0, 255), hsvToRgb(240f, 1f, 1f))
        assertEquals(Triple(255, 0, 0), hsvToRgb(360f, 1f, 1f))
    }

    @Test
    fun zero_saturation_is_grey() {
        assertEquals(Triple(128, 128, 128), hsvToRgb(200f, 0f, 0.5f))
    }

    @Test
    fun brightness_scales_rgb_command() {
        val state = LightState(hue = 0f, saturation = 1f, brightness = 0.5f)
        assertEquals(BulbCommand.Rgb(128, 0, 0), state.colorCommand())
    }

    @Test
    fun white_mode_uses_white_command() {
        val state = LightState(whiteMode = true, brightness = 1f)
        assertEquals(BulbCommand.White(255), state.colorCommand())
    }
}

class RgbToHsvTest {
    @Test
    fun round_trips_with_hsvToRgb() {
        val (h, s, v) = com.example.bulbcontrol.ble.mesh.rgbToHsv(255, 128, 0)
        assertEquals(Triple(255, 128, 0), hsvToRgb(h, s, v))
    }

    @Test
    fun pure_blue() {
        val (h, s, v) = com.example.bulbcontrol.ble.mesh.rgbToHsv(0, 0, 255)
        assertEquals(240f, h, 0.01f)
        assertEquals(1f, s, 0.001f)
        assertEquals(1f, v, 0.001f)
    }
}
