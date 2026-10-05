// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryChartTest {
    private fun sample(
        minutes: Long,
        percent: Int,
        state: String? = "Disconnected",
        limit: Int? = null,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        percent = percent,
        chargingState = state,
        chargeLimit = limit,
    )

    @Test
    fun projectionNullWhenLastSampleIsNotCharging() {
        val samples =
            listOf(
                sample(0, 50, "Charging", limit = 80),
                sample(30, 60),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionNullWithSingleChargingSample() {
        assertNull(chargeProjection(listOf(sample(0, 50, "Charging", limit = 80))))
    }

    @Test
    fun projectionNullWithoutChargeLimit() {
        val samples =
            listOf(
                sample(0, 50, "Charging"),
                sample(30, 60, "Charging"),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionNullWhenLimitAlreadyReached() {
        val atLimit =
            listOf(
                sample(0, 50, "Charging", limit = 80),
                sample(30, 80, "Charging", limit = 80),
            )
        assertNull(chargeProjection(atLimit))

        val aboveLimit =
            listOf(
                sample(0, 50, "Charging", limit = 70),
                sample(30, 80, "Charging", limit = 70),
            )
        assertNull(chargeProjection(aboveLimit))
    }

    @Test
    fun projectionNullWhenPercentStalls() {
        val samples =
            listOf(
                sample(0, 50, "Charging", limit = 80),
                sample(30, 50, "Charging", limit = 80),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionNullWhenRateSpanIsTooShort() {
        val samples =
            listOf(
                sample(0, 50, "Charging", limit = 80),
                sample(1, 55, "Charging", limit = 80),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionComputesCompletionFromTheCurrentRun() {
        val samples =
            listOf(
                sample(0, 50, "Charging", limit = 90),
                sample(30, 60, "Charging", limit = 90),
                sample(60, 70, "Charging", limit = 90),
            )

        val projection = chargeProjection(samples)!!
        assertEquals(samples.last(), projection.from)
        assertEquals(90, projection.targetPercent)
        // 20% gained over 60 min -> the last 20% takes another 60 min.
        assertEquals(120 * 60_000L, projection.completionMillis)
    }

    @Test
    fun projectionUsesOnlyTheTrailingChargingRun() {
        val samples =
            listOf(
                sample(0, 30, "Charging", limit = 80),
                sample(30, 40, "Charging", limit = 80),
                sample(60, 39),
                sample(90, 50, "Charging", limit = 80),
                sample(120, 60, "Charging", limit = 80),
            )

        val projection = chargeProjection(samples)!!
        // 10% gained over 30 min -> the last 20% takes another 60 min.
        assertEquals(180 * 60_000L, projection.completionMillis)
    }
}
