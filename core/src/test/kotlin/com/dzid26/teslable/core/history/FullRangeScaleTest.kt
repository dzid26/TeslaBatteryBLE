// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ChargingStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullRangeScaleTest {
    private fun sample(
        minutes: Long,
        level: Int,
        miles: Float?,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = level,
        chargingState = ChargingStateKind.Disconnected,
        chargeLimit = null,
        ratedRangeMiles = miles,
    )

    @Test
    fun pinsTheScaleAcrossTheRoundingBracket() {
        // k = 300: true SOC 79.6, 79.9, 80.4 all display as 80%.
        val scale =
            FullRangeScaleEstimator.estimate(
                listOf(
                    sample(0, 80, 300f * 0.796f),
                    sample(10, 80, 300f * 0.799f),
                    sample(20, 80, 300f * 0.804f),
                ),
            )!!

        assertTrue(scale.pinned)
        assertEquals(300f, scale.fullRangeMiles, 1f)
        assertEquals(50f, scale.socPercent(150f)!!, 0.5f)
    }

    @Test
    fun staysUnpinnedWhileTheBracketIsWide() {
        // All true SOCs sit just above 79.5, so the bracket stays wide.
        val scale =
            FullRangeScaleEstimator.estimate(
                listOf(
                    sample(0, 80, 300f * 0.796f),
                    sample(10, 80, 300f * 0.797f),
                ),
            )!!

        assertFalse(scale.pinned)
        assertNull(scale.socPercent(240f))
    }

    @Test
    fun rejectsContradictorySamples() {
        assertNull(
            FullRangeScaleEstimator.estimate(
                listOf(
                    sample(0, 80, 300f),
                    sample(10, 50, 100f),
                ),
            ),
        )
    }

    @Test
    fun ignoresRowsWithoutARange() {
        assertNull(FullRangeScaleEstimator.estimate(emptyList()))
        assertNull(FullRangeScaleEstimator.estimate(listOf(sample(0, 80, null))))
    }
}
