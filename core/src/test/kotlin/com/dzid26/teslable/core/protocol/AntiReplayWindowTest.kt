// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiReplayWindowTest {
    @Test
    fun `accepts a higher counter and shifts the window`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 101)
        assertEquals(101, counter)
        assertEquals(1L or (1L shl 1) or (1L shl 6), window)
        assertTrue(ok)
    }

    @Test
    fun `accepts a skipped counter`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 103)
        assertEquals(103, counter)
        assertEquals((1L shl 2) or (1L shl 3) or (1L shl 8), window)
        assertTrue(ok)
    }

    @Test
    fun `clears the window when the jump exceeds its size`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 500)
        assertEquals(500, counter)
        assertEquals(0L, window)
        assertTrue(ok)
    }

    @Test
    fun `accepts an unseen counter inside the window`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 98)
        assertEquals(100, counter)
        assertEquals(1L or (1L shl 1) or (1L shl 5), window)
        assertTrue(ok)
    }

    @Test
    fun `rejects a seen counter inside the window`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 99)
        assertEquals(100, counter)
        assertEquals(1L or (1L shl 5), window)
        assertFalse(ok)
    }

    @Test
    fun `rejects a counter too far behind the window`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 3)
        assertEquals(100, counter)
        assertEquals(1L or (1L shl 5), window)
        assertFalse(ok)
    }

    @Test
    fun `rejects a duplicate counter`() {
        val (counter, window, ok) = AntiReplayWindow.updateSlidingWindow(100, 1L or (1L shl 5), 100)
        assertEquals(100, counter)
        assertEquals(1L or (1L shl 5), window)
        assertFalse(ok)
    }

    @Test
    fun `stateful window accepts the first value then rejects duplicates`() {
        val window = AntiReplayWindow()
        assertTrue(window.update(7))
        assertFalse(window.update(7))
        assertTrue(window.update(8))
        assertFalse(window.update(7))
    }
}
