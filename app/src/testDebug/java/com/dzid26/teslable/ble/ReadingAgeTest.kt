// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.TeslaCommands
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
    fun `minutes round down to ten-minute buckets, the first one opening at the stale boundary`() {
        // Five to nineteen minutes all read "10m": the label first appears when
        // the reading turns stale, and the bucket then holds for 10 more minutes.
        assertEquals("10m ago", readingAgeText(STALE_READING_MS))
        assertEquals("10m ago", readingAgeText(10 * minute))
        assertEquals("10m ago", readingAgeText(20 * minute - 1))
        assertEquals("20m ago", readingAgeText(20 * minute))
        assertEquals("50m ago", readingAgeText(hour - 1))
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
                charge = TeslaCommands.Charge(batteryLevel = 62, chargeLimit = 80, chargingState = "Disconnected"),
                chargeAtMillis = readAt,
            )
        val nowMillis = readAt + STALE_READING_MS + 1
        val reading = batteryPercent(connection, lastKnown = null, nowMillis = nowMillis)
        assertTrue(reading?.stale == true)
        assertNotEquals("just now", readingAgeText(nowMillis - readAt))
    }
}
