// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ChargingStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryChartTest {
    private fun sample(
        minutes: Long,
        percent: Int,
        state: ChargingStateKind? = ChargingStateKind.Disconnected,
        limit: Int? = null,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = percent,
        chargingState = state,
        chargeLimit = limit,
    )

    @Test
    fun dischargeProjectionNullWhileCharging() {
        val samples = listOf(sample(0, 80, ChargingStateKind.Charging, limit = 90), sample(30, 70, ChargingStateKind.Charging, limit = 90))
        assertNull(dischargeProjection(samples, windowMillis = 6 * 60 * 60_000L, nowMillis = 31 * 60_000L))
    }

    @Test
    fun dischargeProjectionNullWhenTheLastSampleIsStale() {
        val samples = listOf(sample(0, 80), sample(30, 70))
        // The last sample is five hours old, past the window's freshness bound.
        assertNull(dischargeProjection(samples, windowMillis = 6 * 60 * 60_000L, nowMillis = 330 * 60_000L))
    }

    @Test
    fun dischargeProjectionNullWhenThePercentRose() {
        val samples = listOf(sample(0, 70), sample(30, 75))
        assertNull(dischargeProjection(samples, windowMillis = 6 * 60 * 60_000L, nowMillis = 31 * 60_000L))
    }

    @Test
    fun dischargeProjectionCarriesTheWindowedRateForward() {
        // 10 points over two hours -> 60 points lower over the 12 h horizon.
        val samples = listOf(sample(0, 80), sample(60, 75), sample(120, 70))
        val projection =
            dischargeProjection(samples, windowMillis = 6 * 60 * 60_000L, nowMillis = 121 * 60_000L)!!
        assertEquals(10, projection.projectedPercent)
        assertEquals(120 * 60_000L + 12 * 60 * 60_000L, projection.projectedAtMillis)
    }

    @Test
    fun projectionWindowScalesWithTheRange() {
        assertEquals(2 * 60 * 60_000L, HistoryRange.SIX_HOURS.projectionWindowMillis())
        assertEquals(6 * 60 * 60_000L, HistoryRange.DAY.projectionWindowMillis())
        assertEquals(24 * 60 * 60_000L, HistoryRange.WEEK.projectionWindowMillis())
        assertEquals(7 * 24 * 60 * 60_000L, HistoryRange.ALL.projectionWindowMillis())
    }

    @Test
    fun projectionNullWhenLastSampleIsNotCharging() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80),
                sample(30, 60),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionNullWithSingleChargingSample() {
        assertNull(chargeProjection(listOf(sample(0, 50, ChargingStateKind.Charging, limit = 80))))
    }

    @Test
    fun projectionNullWithoutChargeLimit() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging),
                sample(30, 60, ChargingStateKind.Charging),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionNullWhenLimitAlreadyReached() {
        val atLimit =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80),
                sample(30, 80, ChargingStateKind.Charging, limit = 80),
            )
        assertNull(chargeProjection(atLimit))

        val aboveLimit =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 70),
                sample(30, 80, ChargingStateKind.Charging, limit = 70),
            )
        assertNull(chargeProjection(aboveLimit))
    }

    @Test
    fun projectionNullWhenPercentStalls() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80),
                sample(30, 50, ChargingStateKind.Charging, limit = 80),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionNullWhenRateSpanIsTooShort() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80),
                sample(1, 55, ChargingStateKind.Charging, limit = 80),
            )
        assertNull(chargeProjection(samples))
    }

    @Test
    fun projectionComputesCompletionFromTheCurrentRun() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 90),
                sample(30, 60, ChargingStateKind.Charging, limit = 90),
                sample(60, 70, ChargingStateKind.Charging, limit = 90),
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
                sample(0, 30, ChargingStateKind.Charging, limit = 80),
                sample(30, 40, ChargingStateKind.Charging, limit = 80),
                sample(60, 39),
                sample(90, 50, ChargingStateKind.Charging, limit = 80),
                sample(120, 60, ChargingStateKind.Charging, limit = 80),
            )

        val projection = chargeProjection(samples)!!
        // 10% gained over 30 min -> the last 20% takes another 60 min.
        assertEquals(180 * 60_000L, projection.completionMillis)
    }

    @Test
    fun projectionUsesTheLiveRateAndTheRunsMiles() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80).copy(ratedRangeMiles = 150f),
                sample(10, 60, ChargingStateKind.Charging, limit = 80).copy(ratedRangeMiles = 180f),
            )

        // 20% left at 3 mi/% = 60 mi; at the live 90 mph that is 40 min,
        // not the 20 min the integer-percent average would give.
        val projection = chargeProjection(samples, chargeRateMph = 90f)!!
        assertEquals(10 * 60_000L + 40 * 60_000L, projection.completionMillis)
    }

    @Test
    fun projectionUsesTheLearnedScaleWithTheLiveRate() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80),
                sample(10, 60, ChargingStateKind.Charging, limit = 80),
            )

        // 20% x 2.4 mi/% = 48 mi; at 90 mph that is 32 min.
        val projection = chargeProjection(samples, chargeRateMph = 90f, fullRangeMiles = 240f)!!
        assertEquals(10 * 60_000L + 32 * 60_000L, projection.completionMillis)
    }

    @Test
    fun projectionKeepsThePercentFallbackWithoutARate() {
        val samples =
            listOf(
                sample(0, 50, ChargingStateKind.Charging, limit = 80).copy(ratedRangeMiles = 150f),
                sample(10, 60, ChargingStateKind.Charging, limit = 80).copy(ratedRangeMiles = 180f),
            )

        // No live rate: the run average still gives 20 min for the last 20%.
        val projection = chargeProjection(samples, fullRangeMiles = 240f)!!
        assertEquals(10 * 60_000L + 20 * 60_000L, projection.completionMillis)
    }
}
