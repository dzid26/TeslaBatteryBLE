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
    fun gapFlagsEmptyWithoutPairs() {
        assertEquals(emptyList<Boolean>(), emptyList<BatterySample>().gapFlags())
        assertEquals(emptyList<Boolean>(), listOf(sample(0, 80)).gapFlags())
    }

    @Test
    fun regularCadenceHasNoGaps() {
        val samples = (0..5).map { sample(minutes = it * 5L, percent = 80 - it) }
        assertEquals(listOf(false, false, false, false, false), samples.gapFlags())
    }

    @Test
    fun longIntervalBecomesGap() {
        // Median 5 min -> threshold max(15 min, 20 min) = 20 min.
        val samples =
            listOf(
                sample(0, 80),
                sample(5, 79),
                sample(10, 78),
                sample(15, 77),
                sample(75, 70),
            )
        assertEquals(listOf(false, false, false, true), samples.gapFlags())
    }

    @Test
    fun floorProtectsAgainstBursts() {
        // Median 1 min -> 3x is 3 min, but the 20-minute floor wins.
        val samples = listOf(sample(0, 80), sample(1, 80), sample(2, 80), sample(17, 79))
        assertEquals(listOf(false, false, false), samples.gapFlags())
    }

    @Test
    fun twoSamplesHoursApartUseTheFloor() {
        val samples = listOf(sample(0, 80), sample(180, 70))
        assertEquals(listOf(true), samples.gapFlags())
    }

    @Test
    fun medianScalesWithSparseCadence() {
        // Every 2 h -> threshold max(6 h, 20 min) = 6 h, so a 5 h gap is normal.
        val normal =
            listOf(
                sample(0, 80),
                sample(120, 79),
                sample(240, 78),
                sample(360, 77),
                sample(660, 70),
            )
        assertEquals(listOf(false, false, false, false), normal.gapFlags())

        // A 6 h 40 min interval does exceed the threshold.
        val gapped = normal.dropLast(1) + sample(760, 70)
        assertEquals(listOf(false, false, false, true), gapped.gapFlags())
    }

    @Test
    fun gapIsStrictlyGreaterThanThreshold() {
        // Exactly the 20-minute floor: not a gap.
        val samples = listOf(sample(0, 80), sample(20, 79))
        assertEquals(listOf(false), samples.gapFlags())
    }

    @Test
    fun duplicateTimestampsAreNotGaps() {
        val samples = listOf(sample(0, 80), sample(0, 80), sample(5, 79))
        assertEquals(listOf(false, false), samples.gapFlags())
    }

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
