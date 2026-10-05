// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.reading

import com.dzid26.teslable.core.protocol.TeslaCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreciseReadingTest {
    private fun charge(
        batteryLevel: Int? = null,
        batteryRange: Float? = null,
        estBatteryRange: Float? = null,
        usableBatteryLevel: Int? = null,
    ) = TeslaCommands.Charge(
        batteryLevel = batteryLevel,
        chargeLimit = null,
        chargingState = null,
        range = batteryRange,
        estBatteryRange = estBatteryRange,
        usableBatteryLevel = usableBatteryLevel,
    )

    @Test
    fun prefersUsableLevelOverRawLevel() {
        val reading = PreciseReading.from(charge(batteryLevel = 79, usableBatteryLevel = 76))
        assertEquals(76f, reading.socPercent)
    }

    @Test
    fun fallsBackToRawLevel() {
        assertEquals(79f, PreciseReading.from(charge(batteryLevel = 79)).socPercent)
        assertEquals(79f, PreciseReading.from(charge(batteryLevel = 79, usableBatteryLevel = 0)).socPercent)
        assertEquals(79f, PreciseReading.from(charge(batteryLevel = 79, usableBatteryLevel = -1)).socPercent)
        assertEquals(79f, PreciseReading.from(charge(batteryLevel = 79, usableBatteryLevel = 101)).socPercent)
    }

    @Test
    fun usableLevelAloneIsEnough() {
        assertEquals(50f, PreciseReading.from(charge(usableBatteryLevel = 50)).socPercent)
    }

    @Test
    fun nullAndImplausibleLevelsYieldNullSoc() {
        assertNull(PreciseReading.from(charge()).socPercent)
        assertNull(PreciseReading.from(charge(batteryLevel = 0)).socPercent)
        assertNull(PreciseReading.from(charge(batteryLevel = -1)).socPercent)
        assertNull(PreciseReading.from(charge(batteryLevel = 101)).socPercent)
        assertNull(PreciseReading.from(charge(batteryLevel = 0, usableBatteryLevel = 0)).socPercent)
    }

    @Test
    fun prefersEstimatedRangeOverRatedRange() {
        val reading = PreciseReading.from(charge(batteryRange = 240.5f, estBatteryRange = 232.75f))
        assertEquals(232.75f, reading.rangeMiles)
    }

    @Test
    fun fallsBackToRatedRange() {
        assertEquals(240.5f, PreciseReading.from(charge(batteryRange = 240.5f)).rangeMiles)
        assertEquals(240.5f, PreciseReading.from(charge(batteryRange = 240.5f, estBatteryRange = 0f)).rangeMiles)
        assertEquals(240.5f, PreciseReading.from(charge(batteryRange = 240.5f, estBatteryRange = -1f)).rangeMiles)
        assertEquals(240.5f, PreciseReading.from(charge(batteryRange = 240.5f, estBatteryRange = Float.NaN)).rangeMiles)
        assertEquals(
            240.5f,
            PreciseReading.from(charge(batteryRange = 240.5f, estBatteryRange = Float.POSITIVE_INFINITY)).rangeMiles,
        )
    }

    @Test
    fun nullAndImplausibleRangesYieldNullRange() {
        assertNull(PreciseReading.from(charge()).rangeMiles)
        assertNull(PreciseReading.from(charge(batteryRange = 0f)).rangeMiles)
        assertNull(PreciseReading.from(charge(batteryRange = -5f)).rangeMiles)
        assertNull(PreciseReading.from(charge(batteryRange = Float.NaN)).rangeMiles)
        assertNull(PreciseReading.from(charge(batteryRange = Float.NEGATIVE_INFINITY)).rangeMiles)
    }

    @Test
    fun resolvesSocAndRangeTogether() {
        val reading =
            PreciseReading.from(
                charge(
                    batteryLevel = 79,
                    batteryRange = 240.5f,
                    estBatteryRange = 232.75f,
                    usableBatteryLevel = 76,
                ),
            )
        assertEquals(76f, reading.socPercent)
        assertEquals(232.75f, reading.rangeMiles)
    }
}
