package com.example.bulbcontrol.ble

import java.util.Locale
import java.util.UUID

/** A high-level thing we want the bulb to do. Each protocol turns it into bytes. */
sealed interface BulbCommand {
    data class Power(val on: Boolean) : BulbCommand
    /** Each channel 0..255. */
    data class Rgb(val r: Int, val g: Int, val b: Int) : BulbCommand
    /** Warm/white LEDs, level 0..255. */
    data class White(val level: Int) : BulbCommand
}

/**
 * Describes how to talk to one family of Bluetooth LE bulbs.
 *
 * To support a bulb that isn't listed here:
 *  1. Install "nRF Connect" on your phone, connect to the bulb and note the
 *     service UUID and the characteristic that has the WRITE property.
 *  2. Find the byte commands for your bulb (search the bulb's app name +
 *     "BLE protocol"), then add a new object below and register it in [BulbProtocols.all].
 */
interface BulbProtocol {
    val name: String
    /** (service UUID, writable characteristic UUID) pairs used to detect this protocol. */
    val writeTargets: List<Pair<UUID, UUID>>
    fun encode(command: BulbCommand): ByteArray
}

/** Bulbs sold as Triones, Magic Blue, Happy Lighting, LEDBLE, iLight etc. */
object TrionesProtocol : BulbProtocol {
    override val name = "Triones / Magic Blue"
    override val writeTargets = listOf(
        uuid16(0xFFD5) to uuid16(0xFFD9),
        uuid16(0xFFE5) to uuid16(0xFFE9),
    )

    override fun encode(command: BulbCommand): ByteArray = when (command) {
        is BulbCommand.Power -> if (command.on) bytes(0xCC, 0x23, 0x33) else bytes(0xCC, 0x24, 0x33)
        is BulbCommand.Rgb -> bytes(0x56, command.r.ch(), command.g.ch(), command.b.ch(), 0x00, 0xF0, 0xAA)
        is BulbCommand.White -> bytes(0x56, 0x00, 0x00, 0x00, command.level.ch(), 0x0F, 0xAA)
    }
}

/** Bulbs/strips sold as ELK-BLEDOM, "Lotus Lamp", "duoCo Strip" etc. */
object ElkBledomProtocol : BulbProtocol {
    override val name = "ELK-BLEDOM / Lotus Lamp"
    override val writeTargets = listOf(
        uuid16(0xFFF0) to uuid16(0xFFF3),
    )

    override fun encode(command: BulbCommand): ByteArray = when (command) {
        is BulbCommand.Power ->
            if (command.on) bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
            else bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
        is BulbCommand.Rgb ->
            bytes(0x7E, 0x00, 0x05, 0x03, command.r.ch(), command.g.ch(), command.b.ch(), 0x00, 0xEF)
        is BulbCommand.White -> {
            val w = command.level.ch()
            bytes(0x7E, 0x00, 0x05, 0x03, w, w, w, 0x00, 0xEF)
        }
    }
}

object BulbProtocols {
    val all: List<BulbProtocol> = listOf(TrionesProtocol, ElkBledomProtocol)
}

/** Expands a 16-bit Bluetooth SIG short UUID (e.g. 0xFFE5) to a full UUID. */
fun uuid16(short: Int): UUID =
    UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", short))

fun ByteArray.toHex(): String = joinToString(" ") { String.format(Locale.ROOT, "%02X", it) }

private fun Int.ch(): Int = coerceIn(0, 255)
private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
