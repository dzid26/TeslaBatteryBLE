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

    @Test
    fun `accepts the first counter once even when it is zero`() {
        val window = AntiReplayWindow()
        assertTrue(window.update(0))
        assertFalse(window.update(0))
    }

    @Test
    fun `accepts out of order counters inside the window`() {
        val window = AntiReplayWindow()
        assertTrue(window.update(100))
        assertTrue(window.update(98))
        assertTrue(window.update(99))
        assertFalse(window.update(98))
        assertFalse(window.update(99))
    }

    @Test
    fun `accepts the oldest counter in the window and rejects one further back`() {
        val window = AntiReplayWindow()
        assertTrue(window.update(100))
        assertTrue(window.update(100 - AntiReplayWindow.WINDOW_SIZE))
        assertFalse(window.update(100 - AntiReplayWindow.WINDOW_SIZE - 1))
        assertFalse(window.update(100 - AntiReplayWindow.WINDOW_SIZE))
    }

    @Test
    fun `advances the window and forgets counters that fall out`() {
        val window = AntiReplayWindow()
        assertTrue(window.update(100))
        assertTrue(window.update(133))
        assertFalse(window.update(133))
        assertTrue(window.update(101))
        assertFalse(window.update(100))
    }

    @Test
    fun `rejects counters after the uint32 wrap`() {
        val window = AntiReplayWindow()
        assertTrue(window.update(-1))
        assertFalse(window.update(0))
        assertFalse(window.update(1))
    }

    @Test
    fun `treats counters as unsigned 32 bit values`() {
        val (counter, history, ok) = AntiReplayWindow.updateSlidingWindow(0, 0L, -1)
        assertTrue(ok)
        assertEquals(-1, counter)
        assertEquals(0L, history)

        val (wrapped, wrappedHistory, wrappedOk) = AntiReplayWindow.updateSlidingWindow(-1, 0L, 0)
        assertFalse(wrappedOk)
        assertEquals(-1, wrapped)
        assertEquals(0L, wrappedHistory)
    }
}
