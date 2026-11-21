package com.example.bulbcontrol

import com.example.bulbcontrol.ble.BulbCommand
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the user wants the light to look like. */
data class LightState(
    val on: Boolean = true,
    /** 0..360 */
    val hue: Float = 35f,
    /** 0..1 */
    val saturation: Float = 1f,
    /** 0.05..1 */
    val brightness: Float = 1f,
    /** Use the bulb's white LEDs instead of RGB. */
    val whiteMode: Boolean = false,
) {
    /** The colour as 0..255 RGB, already scaled by brightness. */
    fun rgb(): Triple<Int, Int, Int> =
        if (whiteMode) {
            val w = (brightness * 255).roundToInt()
            Triple(w, w, w)
        } else {
            hsvToRgb(hue, saturation, brightness)
        }

    fun colorCommand(): BulbCommand =
        if (whiteMode) {
            BulbCommand.White((brightness * 255).roundToInt().coerceIn(0, 255))
        } else {
            val (r, g, b) = rgb()
            BulbCommand.Rgb(r, g, b)
        }
}

/** Pure-Kotlin HSV -> RGB so it can be unit tested without Android. */
fun hsvToRgb(hue: Float, saturation: Float, value: Float): Triple<Int, Int, Int> {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1 - abs((h / 60f) % 2 - 1))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun to255(f: Float) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
    return Triple(to255(r1), to255(g1), to255(b1))
}
