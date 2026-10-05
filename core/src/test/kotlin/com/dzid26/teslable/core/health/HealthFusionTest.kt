// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthFusionTest {
    @Test
    fun `single source passes through`() {
        val result = HealthFusion.fuse(90.0, null)!!
        assertEquals(90.0, result.sohPercent, 1e-9)
        assertEquals(0.0, result.spreadPoints, 1e-9)
        assertEquals(1, result.sources)
        assertFalse(result.mismatch)
    }

    @Test
    fun `averages two close sources`() {
        val result = HealthFusion.fuse(90.0, 87.5)!!
        assertEquals(88.75, result.sohPercent, 1e-9)
        assertEquals(2.5, result.spreadPoints, 1e-9)
        assertEquals(2, result.sources)
        assertFalse(result.mismatch)
    }

    @Test
    fun `spread of exactly five is not a mismatch`() {
        val result = HealthFusion.fuse(90.0, 85.0)!!
        assertEquals(5.0, result.spreadPoints, 1e-9)
        assertFalse(result.mismatch)
    }

    @Test
    fun `spread above five is a mismatch`() {
        val result = HealthFusion.fuse(90.0, 84.9)!!
        assertEquals(5.1, result.spreadPoints, 1e-9)
        assertTrue(result.mismatch)
    }

    @Test
    fun `null and NaN inputs yield null`() {
        assertNull(HealthFusion.fuse(null, null))
        assertNull(HealthFusion.fuse(Double.NaN, Double.NaN))
    }

    @Test
    fun `NaN is treated as missing`() {
        val result = HealthFusion.fuse(Double.NaN, 87.5)!!
        assertEquals(87.5, result.sohPercent, 1e-9)
        assertEquals(1, result.sources)
    }
}
