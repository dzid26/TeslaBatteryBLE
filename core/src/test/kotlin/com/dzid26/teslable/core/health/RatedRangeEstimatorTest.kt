// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatedRangeEstimatorTest {
    @Test
    fun `scales partial SOC to full range`() {
        val result = RatedRangeEstimator.estimate(216f, 80f, 300f)!!
        assertEquals(270f, result.fullRangeMiles, 0.001f)
        assertEquals(90f, result.sohPercent, 0.001f)
    }

    @Test
    fun `full charge divides by EPA directly`() {
        val result = RatedRangeEstimator.estimate(270f, 100f, 300f)!!
        assertEquals(270f, result.fullRangeMiles, 0.001f)
        assertEquals(90f, result.sohPercent, 0.001f)
    }

    @Test
    fun `rejects invalid inputs`() {
        assertNull(RatedRangeEstimator.estimate(216f, 0f, 300f))
        assertNull(RatedRangeEstimator.estimate(216f, 101f, 300f))
        assertNull(RatedRangeEstimator.estimate(216f, 80f, 0f))
        assertNull(RatedRangeEstimator.estimate(0f, 80f, 300f))
        assertNull(RatedRangeEstimator.estimate(Float.NaN, 80f, 300f))
        assertNull(RatedRangeEstimator.estimate(216f, Float.NaN, 300f))
        assertNull(RatedRangeEstimator.estimate(216f, 80f, Float.NaN))
        assertNull(RatedRangeEstimator.estimate(Float.POSITIVE_INFINITY, 80f, 300f))
    }
}
