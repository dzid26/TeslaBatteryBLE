// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ChargingStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

class FullRangeScaleTest {
    private fun sample(
        minutes: Long,
        level: Int,
        miles: Float?,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = level,
        chargingState = ChargingStateKind.Charging,
        chargeLimit = null,
        ratedRangeMiles = miles,
    )

    /** A charge climbing in 0.25% steps of true SOC; the level is [round] of it. */
    private fun climb(
        scale: Float,
        offset: Float,
        from: Float,
        to: Float,
        round: (Float) -> Int,
    ): List<BatterySample> {
        val out = mutableListOf<BatterySample>()
        var soc = from
        var minute = 0L
        while (soc <= to) {
            out += sample(minute++, round(soc), offset + scale * soc / 100f)
            soc += 0.25f
        }
        return out
    }

    @Test
    fun `recovers the scale whichever way the level rounds`() {
        for (round in listOf<(Float) -> Int>(
            { floor(it).toInt() },
            { it.roundToInt() },
            { ceil(it).toInt() },
        )) {
            val scale = FullRangeScaleEstimator.estimate(climb(270f, -2f, 40.1f, 70f, round))!!
            assertTrue(scale.pinned)
            assertEquals(270f, scale.fullRangeMiles, 1.5f)
        }
    }

    @Test
    fun `fine soc comes from the miles not the integer level`() {
        val scale = FullRangeScaleEstimator.estimate(climb(270f, 0f, 40.1f, 70f) { it.roundToInt() })!!
        // 0.6 mi above the 50% boundary: ~50.2% on the displayed scale.
        assertEquals(50.22f, scale.socPercent(0f + 270f * 0.5022f)!!, 0.15f)
    }

    @Test
    fun `stays unpinned with one level span`() {
        val samples =
            listOf(sample(0, 79, 213.0f), sample(1, 80, 215.7f), sample(2, 81, 218.4f))
        val scale = FullRangeScaleEstimator.estimate(samples)
        assertTrue(scale == null || !scale.pinned)
        assertNull(scale?.socPercent(240f))
    }

    @Test
    fun `a drive between two reads does not move the scale`() {
        val charge = climb(270f, 0f, 40.1f, 70f) { it.roundToInt() }
        // A sample after a drive: level 1 lower but miles far lower than the step implies.
        val bumped = charge.take(50) + sample(999, charge[49].batteryLevel + 1, 120f) + charge.drop(50)
        val scale = FullRangeScaleEstimator.estimate(bumped)!!
        assertEquals(270f, scale.fullRangeMiles, 2f)
    }

    @Test
    fun `needs several boundaries`() {
        assertNull(FullRangeScaleEstimator.estimate(emptyList()))
        assertNull(FullRangeScaleEstimator.estimate(listOf(sample(0, 80, null))))
        assertNotNull(FullRangeScaleEstimator.estimate(climb(270f, 0f, 40.1f, 70f) { it.roundToInt() }))
        assertFalse(
            FullRangeScaleEstimator
                .estimate(listOf(sample(0, 80, 216f), sample(1, 80, 216.1f)))
                ?.pinned == true,
        )
    }

    @Test
    fun `tenths split a soc cleanly`() {
        assertEquals(0, socTenths(0f))
        assertEquals(51, socTenths(5.05f))
        assertEquals(620, socTenths(62f))
        assertEquals(624, socTenths(62.4f))
        assertEquals(1000, socTenths(99.96f))
        assertEquals(1000, socTenths(100f))
    }
}
