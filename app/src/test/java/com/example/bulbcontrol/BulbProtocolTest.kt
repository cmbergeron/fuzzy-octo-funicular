package com.example.bulbcontrol

import com.example.bulbcontrol.ble.BulbCommand
import com.example.bulbcontrol.ble.ElkBledomProtocol
import com.example.bulbcontrol.ble.TrionesProtocol
import com.example.bulbcontrol.ble.toHex
import com.example.bulbcontrol.ble.uuid16
import org.junit.Assert.assertEquals
import org.junit.Test

class BulbProtocolTest {

    @Test
    fun triones_power() {
        assertEquals("CC 23 33", TrionesProtocol.encode(BulbCommand.Power(true)).toHex())
        assertEquals("CC 24 33", TrionesProtocol.encode(BulbCommand.Power(false)).toHex())
    }

    @Test
    fun triones_color() {
        assertEquals("56 FF 80 00 00 F0 AA", TrionesProtocol.encode(BulbCommand.Rgb(255, 128, 0)).toHex())
    }

    @Test
    fun triones_white() {
        assertEquals("56 00 00 00 C8 0F AA", TrionesProtocol.encode(BulbCommand.White(200)).toHex())
    }

    @Test
    fun values_are_clamped_to_a_byte() {
        assertEquals("56 FF 00 00 00 F0 AA", TrionesProtocol.encode(BulbCommand.Rgb(999, -5, 0)).toHex())
    }

    @Test
    fun elk_power_and_color() {
        assertEquals("7E 00 04 F0 00 01 FF 00 EF", ElkBledomProtocol.encode(BulbCommand.Power(true)).toHex())
        assertEquals("7E 00 04 00 00 00 FF 00 EF", ElkBledomProtocol.encode(BulbCommand.Power(false)).toHex())
        assertEquals("7E 00 05 03 00 00 FF 00 EF", ElkBledomProtocol.encode(BulbCommand.Rgb(0, 0, 255)).toHex())
    }

    @Test
    fun short_uuid_expands_correctly() {
        assertEquals("0000ffe5-0000-1000-8000-00805f9b34fb", uuid16(0xFFE5).toString())
    }
}
