package com.dzid26.teslable.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaNamesTest {

    @Test
    fun `derives advertised name from VIN`() {
        assertEquals("S3acc31774a738ea0C", TeslaNames.bleName("5YJ3E1EA7KF000001"))
    }

    @Test
    fun `accepts valid Tesla BLE names`() {
        assertTrue(TeslaNames.isTeslaBleName("S1a87a5a75f3df858C"))
        assertTrue(TeslaNames.isTeslaBleName("S1A87A5A75F3DF858C"))
    }

    @Test
    fun `rejects names that do not match the pattern`() {
        assertFalse(TeslaNames.isTeslaBleName(null))
        assertFalse(TeslaNames.isTeslaBleName(""))
        assertFalse(TeslaNames.isTeslaBleName("Tesla"))
        assertFalse(TeslaNames.isTeslaBleName("S123C"))
        assertFalse(TeslaNames.isTeslaBleName("S1a87a5a75f3df858Z"))
        assertFalse(TeslaNames.isTeslaBleName("S1a87a5a75f3df858CX"))
    }

    @Test
    fun `rejects VINs of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            TeslaNames.bleName("TOO-SHORT")
        }
    }
}
