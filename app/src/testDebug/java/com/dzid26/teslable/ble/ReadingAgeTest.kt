// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAgeTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `a reading inside the fresh window reads just now`() {
        assertEquals("just now", readingAgeText(0))
        assertEquals("just now", readingAgeText(30_000))
        assertEquals("just now", readingAgeText(STALE_READING_MS - 1))
    }

    @Test
    fun `a clock that ran backwards reads just now`() {
        assertEquals("just now", readingAgeText(-90_000))
    }

    @Test
    fun `minutes read as whole minutes, the first label opening at the stale boundary`() {
        assertEquals("5m ago", readingAgeText(STALE_READING_MS))
        assertEquals("5m ago", readingAgeText(6 * minute - 1))
        assertEquals("6m ago", readingAgeText(6 * minute))
        assertEquals("7m ago", readingAgeText(7 * minute))
        assertEquals("7m ago", readingAgeText(8 * minute - 1))
        assertEquals("59m ago", readingAgeText(hour - 1))
    }

    @Test
    fun `hours round down`() {
        assertEquals("1h ago", readingAgeText(hour))
        assertEquals("1h ago", readingAgeText(2 * hour - 1))
        assertEquals("23h ago", readingAgeText(day - 1))
    }

    @Test
    fun `days round down`() {
        assertEquals("1d ago", readingAgeText(day))
        assertEquals("1d ago", readingAgeText(2 * day - 1))
        assertEquals("3d ago", readingAgeText(3 * day))
        assertEquals("40d ago", readingAgeText(40 * day))
    }

    @Test
    fun `a live reading is never just now once batteryPercent calls it stale`() {
        val readAt = 1_000L
        val connection =
            TeslaConnection(
                address = "18:04:ED:84:79:80",
                name = "Se1f0941734830fe7C",
                charge =
                    ChargeState(
                        battery_level = 62,
                        charge_limit_soc = 80,
                        charging_state = ChargeState.ChargingState(Disconnected = Void()),
                    ),
                chargeAtMillis = readAt,
            )
        val nowMillis = readAt + STALE_READING_MS + 1
        val reading = batteryPercent(connection, lastKnown = null, nowMillis = nowMillis)
        assertTrue(reading?.stale == true)
        assertNotEquals("just now", readingAgeText(nowMillis - readAt))
    }
}
