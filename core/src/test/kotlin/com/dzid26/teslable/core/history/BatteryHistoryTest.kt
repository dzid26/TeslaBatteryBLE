// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHistoryTest {
    private fun sample(
        minutes: Long,
        percent: Int,
        state: String? = "Disconnected",
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = percent,
        chargingState = state,
        chargeLimit = null,
    )

    @Test
    fun withinFiltersByRange() {
        val samples =
            listOf(
                sample(minutes = 0, percent = 80),
                sample(minutes = 60, percent = 79),
                sample(minutes = 600, percent = 70),
            )
        val now = 600 * 60_000L

        assertEquals(1, samples.within(HistoryRange.SIX_HOURS, now).size)
        assertEquals(3, samples.within(HistoryRange.DAY, now).size)
        assertEquals(3, samples.within(HistoryRange.ALL, now).size)
    }

    @Test
    fun chargeStatsNullWithoutCharging() {
        assertNull(chargeStats(listOf(sample(0, 80), sample(60, 79))))
        assertNull(chargeStats(emptyList()))
    }

    @Test
    fun chargeStatsNullWhileStillCharging() {
        val samples = listOf(sample(0, 80), sample(30, 81, "Charging"))
        assertNull(chargeStats(samples))
    }

    @Test
    fun chargeStatsSinceLastChargeEnd() {
        val samples =
            listOf(
                sample(0, 70),
                sample(30, 80, "Charging"),
                sample(60, 85, "Charging"),
                sample(90, 84),
                sample(120, 78),
            )

        val stats = chargeStats(samples)!!
        assertEquals(90 * 60_000L, stats.sinceMillis)
        assertEquals(84f, stats.startPercent)
        assertEquals(78f, stats.currentPercent)
        assertEquals(78f, stats.minPercent)
        assertEquals(84f, stats.maxPercent)
        assertEquals(6f, stats.usedPercent)
    }

    @Test
    fun chargeStatsUseLastCharge() {
        val samples =
            listOf(
                sample(0, 60, "Charging"),
                sample(30, 70),
                sample(60, 80, "Charging"),
                sample(90, 75),
            )

        val stats = chargeStats(samples)!!
        assertEquals(90 * 60_000L, stats.sinceMillis)
        assertEquals(75f, stats.startPercent)
        assertEquals(0f, stats.usedPercent)
    }

    @Test
    fun percentDerivesFromTheDisplayedLevel() {
        assertEquals(78, sample(0, 78).percent)
        assertEquals(77, sample(0, 78).copy(batteryLevel = 77).percent)
    }
}
