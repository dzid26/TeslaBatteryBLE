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
        val result = HealthFusion.fuse(90f, null)!!
        assertEquals(90f, result.sohPercent, 0.001f)
        assertEquals(0f, result.spreadPoints, 0.001f)
        assertEquals(1, result.sources)
        assertFalse(result.mismatch)
    }

    @Test
    fun `averages two close sources`() {
        val result = HealthFusion.fuse(90f, 87.5f)!!
        assertEquals(88.75f, result.sohPercent, 0.001f)
        assertEquals(2.5f, result.spreadPoints, 0.001f)
        assertEquals(2, result.sources)
        assertFalse(result.mismatch)
    }

    @Test
    fun `spread of exactly five is not a mismatch`() {
        val result = HealthFusion.fuse(90f, 85f)!!
        assertEquals(5f, result.spreadPoints, 0.001f)
        assertFalse(result.mismatch)
    }

    @Test
    fun `spread above five is a mismatch`() {
        val result = HealthFusion.fuse(90f, 84.9f)!!
        assertEquals(5.1f, result.spreadPoints, 0.001f)
        assertTrue(result.mismatch)
    }

    @Test
    fun `null and NaN inputs yield null`() {
        assertNull(HealthFusion.fuse(null, null))
        assertNull(HealthFusion.fuse(Float.NaN, Float.NaN))
    }

    @Test
    fun `NaN is treated as missing`() {
        val result = HealthFusion.fuse(Float.NaN, 87.5f)!!
        assertEquals(87.5f, result.sohPercent, 0.001f)
        assertEquals(1, result.sources)
    }
}
