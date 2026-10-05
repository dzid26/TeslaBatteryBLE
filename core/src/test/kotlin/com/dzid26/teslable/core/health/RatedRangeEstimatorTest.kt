// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatedRangeEstimatorTest {
    @Test
    fun `scales partial SOC to full range`() {
        val result = RatedRangeEstimator.estimate(216.0, 80.0, 300.0)!!
        assertEquals(270.0, result.fullRangeMiles, 1e-9)
        assertEquals(90.0, result.sohPercent, 1e-9)
    }

    @Test
    fun `full charge divides by EPA directly`() {
        val result = RatedRangeEstimator.estimate(270.0, 100.0, 300.0)!!
        assertEquals(270.0, result.fullRangeMiles, 1e-9)
        assertEquals(90.0, result.sohPercent, 1e-9)
    }

    @Test
    fun `rejects invalid inputs`() {
        assertNull(RatedRangeEstimator.estimate(216.0, 0.0, 300.0))
        assertNull(RatedRangeEstimator.estimate(216.0, 101.0, 300.0))
        assertNull(RatedRangeEstimator.estimate(216.0, 80.0, 0.0))
        assertNull(RatedRangeEstimator.estimate(0.0, 80.0, 300.0))
        assertNull(RatedRangeEstimator.estimate(Double.NaN, 80.0, 300.0))
        assertNull(RatedRangeEstimator.estimate(216.0, Double.NaN, 300.0))
        assertNull(RatedRangeEstimator.estimate(216.0, 80.0, Double.NaN))
        assertNull(RatedRangeEstimator.estimate(Double.POSITIVE_INFINITY, 80.0, 300.0))
    }
}
