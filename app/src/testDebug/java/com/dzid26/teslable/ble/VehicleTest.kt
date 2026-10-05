// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTest {
    // Verified on a real car: this VIN advertises as this BLE name.
    private val vin = "5YJ3E7EBXKF523421"
    private val bleName = "Se1f0941734830fe7C"

    private fun vehicle(vin: String? = null) =
        Vehicle(
            bleName = bleName,
            address = "18:04:ED:84:79:80",
            gattName = "\uD83D\uDD11 Teslak",
            vin = vin,
        )

    @Test
    fun acceptsTheVinThatMatchesTheAdvertisedName() {
        assertTrue(vehicle().acceptsVin(vin))
    }

    @Test
    fun acceptsNormalisedInput() {
        assertTrue(vehicle().acceptsVin("  ${vin.lowercase()}  "))
        assertEquals(vin, Vehicle.normalizeVin("  ${vin.lowercase()}  "))
    }

    @Test
    fun rejectsAnotherVin() {
        assertFalse(vehicle().acceptsVin("5YJ3E7EBXKF000000"))
    }

    @Test
    fun rejectsShortInput() {
        assertFalse(vehicle().acceptsVin("5YJ3E7"))
    }

    @Test
    fun titlePrefersUserThenCarName() {
        assertEquals("\uD83D\uDD11 Teslak", vehicle().title)
        assertEquals("Daily driver", vehicle().copy(displayName = "Daily driver").title)
    }

    @Test
    fun pairedOnlyWhenAKeySlotIsKnown() {
        assertFalse(vehicle().isPaired)
        assertTrue(vehicle().copy(keySlot = 3).isPaired)
    }
}
