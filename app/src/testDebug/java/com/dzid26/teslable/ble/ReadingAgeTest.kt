// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.InfotainmentPollPolicy
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
        assertEquals("just now", readingAgeText(FRESH_READING_MS - 1))
    }

    @Test
    fun `a clock that ran backwards reads just now`() {
        assertEquals("just now", readingAgeText(-90_000))
    }

    @Test
    fun `the first label opens at the stale boundary and minutes read whole`() {
        assertEquals("<1m ago", readingAgeText(FRESH_READING_MS))
        assertEquals("<1m ago", readingAgeText(minute - 1))
        assertEquals("1m ago", readingAgeText(minute))
        assertEquals("5m ago", readingAgeText(6 * minute - 1))
        assertEquals("12m ago", readingAgeText(12 * minute))
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
        val nowMillis = readAt + FRESH_READING_MS
        val reading = batteryPercent(connection, lastKnown = null, nowMillis = nowMillis)
        assertTrue(reading?.stale == true)
        assertNotEquals("just now", readingAgeText(nowMillis - readAt))
    }

    private fun liveConnection(readAt: Long?) =
        TeslaConnection(
            address = "18:04:ED:84:79:80",
            name = "Se1f0941734830fe7C",
            charge = ChargeState(battery_level = 62, charge_limit_soc = 80),
            chargeAtMillis = readAt,
        )

    @Test
    fun `a reading is fresh only while it is younger than the fresh window`() {
        val readAt = 1_000_000L
        val connection = liveConnection(readAt)
        assertEquals(false, batteryPercent(connection, null, readAt + FRESH_READING_MS - 1)?.stale)
        assertEquals(true, batteryPercent(connection, null, readAt + FRESH_READING_MS)?.stale)
        assertEquals(true, batteryPercent(connection, null, readAt + 6 * minute)?.stale)
    }

    @Test
    fun `fresh window follows the poll policy's read interval`() {
        assertEquals(2 * InfotainmentPollPolicy.READ_INTERVAL_MS + FRESH_SLACK_MS, FRESH_READING_MS)
        // One missed read (twice the interval) must not flip a reading to stale.
        assertTrue(FRESH_READING_MS > 2 * InfotainmentPollPolicy.READ_INTERVAL_MS)
        // And it greys well before a minute of silence.
        assertTrue(FRESH_READING_MS < 60_000L)
    }

    @Test
    fun `a live charge with no read time is stale`() {
        assertEquals(true, batteryPercent(liveConnection(null), null, 5_000)?.stale)
    }

    @Test
    fun `the clock ticks at the moment a fresh reading turns stale, then every minute`() {
        val readAt = 1_000_000L
        assertEquals(FRESH_READING_MS + 1, stalenessTickDelayMs(readAt, readAt))
        assertEquals(10_001L, stalenessTickDelayMs(readAt, readAt + FRESH_READING_MS - 10_000))
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(readAt, readAt + FRESH_READING_MS))
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(readAt, readAt + hour))
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(null, readAt))
    }
}
