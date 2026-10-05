// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyDeltaEstimatorTest {
    @Test
    fun `computes capacity and soh from a charge session`() {
        val result = EnergyDeltaEstimator.estimate(26.25f, 20f, 60f, 75f)!!
        assertEquals(65.625f, result.usableCapacityKwh, 0.001f)
        assertEquals(87.5f, result.sohPercent!!, 0.001f)
        assertEquals(40f, result.socDeltaPercent, 0.001f)
    }

    @Test
    fun `soh is null without a when-new baseline`() {
        val result = EnergyDeltaEstimator.estimate(26.25f, 20f, 60f)!!
        assertEquals(65.625f, result.usableCapacityKwh, 0.001f)
        assertNull(result.sohPercent)
    }

    @Test
    fun `accepts a delta of exactly five percent`() {
        val result = EnergyDeltaEstimator.estimate(3f, 20f, 25f, 75f)!!
        assertEquals(60f, result.usableCapacityKwh, 0.001f)
    }

    @Test
    fun `rejects invalid sessions`() {
        assertNull(EnergyDeltaEstimator.estimate(0f, 20f, 60f, 75f))
        assertNull(EnergyDeltaEstimator.estimate(26.25f, 20f, 23f, 75f))
        assertNull(EnergyDeltaEstimator.estimate(26.25f, 60f, 20f, 75f))
        assertNull(EnergyDeltaEstimator.estimate(26.25f, -1f, 60f, 75f))
        assertNull(EnergyDeltaEstimator.estimate(26.25f, 20f, 101f, 75f))
        assertNull(EnergyDeltaEstimator.estimate(26.25f, 20f, 60f, 0f))
        assertNull(EnergyDeltaEstimator.estimate(Float.NaN, 20f, 60f, 75f))
    }
}
