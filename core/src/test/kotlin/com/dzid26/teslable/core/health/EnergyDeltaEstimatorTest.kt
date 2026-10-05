// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyDeltaEstimatorTest {
    @Test
    fun `computes capacity and soh from a charge session`() {
        val result = EnergyDeltaEstimator.estimate(26.25, 20.0, 60.0, 75.0)!!
        assertEquals(65.625, result.usableCapacityKwh, 1e-9)
        assertEquals(87.5, result.sohPercent!!, 1e-9)
        assertEquals(40.0, result.socDeltaPercent, 1e-9)
    }

    @Test
    fun `soh is null without a when-new baseline`() {
        val result = EnergyDeltaEstimator.estimate(26.25, 20.0, 60.0)!!
        assertEquals(65.625, result.usableCapacityKwh, 1e-9)
        assertNull(result.sohPercent)
    }

    @Test
    fun `accepts a delta of exactly five percent`() {
        val result = EnergyDeltaEstimator.estimate(3.0, 20.0, 25.0, 75.0)!!
        assertEquals(60.0, result.usableCapacityKwh, 1e-9)
    }

    @Test
    fun `rejects invalid sessions`() {
        assertNull(EnergyDeltaEstimator.estimate(0.0, 20.0, 60.0, 75.0))
        assertNull(EnergyDeltaEstimator.estimate(26.25, 20.0, 23.0, 75.0))
        assertNull(EnergyDeltaEstimator.estimate(26.25, 60.0, 20.0, 75.0))
        assertNull(EnergyDeltaEstimator.estimate(26.25, -1.0, 60.0, 75.0))
        assertNull(EnergyDeltaEstimator.estimate(26.25, 20.0, 101.0, 75.0))
        assertNull(EnergyDeltaEstimator.estimate(26.25, 20.0, 60.0, 0.0))
        assertNull(EnergyDeltaEstimator.estimate(Double.NaN, 20.0, 60.0, 75.0))
    }
}
