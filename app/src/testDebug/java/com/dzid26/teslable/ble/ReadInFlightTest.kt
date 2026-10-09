// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadInFlightTest {
    private val changes = mutableListOf<Boolean>()
    private val read = ReadInFlight { changes += it }

    @Test
    fun `it is off until a read request goes out`() {
        assertFalse(read.active)
        read.onSent(1_000)
        assertTrue(read.active)
    }

    @Test
    fun `a reply with a follow-up keeps the read in flight, the last reply ends it`() {
        read.onSent(0) // charge
        read.onReply()
        read.onSent(200) // drive follow-up
        assertTrue(read.active)
        read.onReply()
        read.onSent(400) // closures
        read.onReply()
        read.onSent(600) // climate
        assertTrue(read.active)
        read.onReply() // climate reply: nothing follows
        assertFalse(read.active)
    }

    @Test
    fun `a failure, a refusal or a reply without data ends it when no follow-up goes out`() {
        read.onSent(0)
        read.onReply()
        assertFalse(read.active)
    }

    @Test
    fun `the car asleep, so the gate skips the read, ends it`() {
        read.onSent(0)
        read.onSkipped()
        assertFalse(read.active)
    }

    @Test
    fun `the link dropping ends it`() {
        read.onSent(0)
        read.onLinkDropped()
        assertFalse(read.active)
    }

    @Test
    fun `an unanswered read ends after the command timeout and not before`() {
        read.onSent(1_000)
        assertFalse(read.expire(1_000 + ReadInFlight.TIMEOUT_MS - 1))
        assertTrue(read.active)
        assertTrue(read.expire(1_000 + ReadInFlight.TIMEOUT_MS))
        assertFalse(read.active)
        assertFalse(read.expire(1_000 + 10 * ReadInFlight.TIMEOUT_MS))
    }

    @Test
    fun `each request restarts the timeout`() {
        read.onSent(0)
        read.onReply()
        read.onSent(10_000)
        assertFalse(read.expire(10_000 + ReadInFlight.TIMEOUT_MS - 1))
    }

    @Test
    fun `the change callback fires once per change, never twice with the same value`() {
        read.onSent(0)
        read.onReply()
        read.onSent(100) // a follow-up straight after the reply
        read.onSent(200)
        read.onReply()
        read.onReply()
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test
    fun `the timeout matches the command log's`() {
        assertEquals(CommandRecorder.TIMEOUT_MS, ReadInFlight.TIMEOUT_MS)
    }

    @Test
    fun `a spinner stays up for its minimum, then releases at once`() {
        assertEquals(READ_SPINNER_MIN_VISIBLE_MS, spinnerHoldMs(shownAtMillis = 1_000, nowMillis = 1_000))
        assertEquals(400L, spinnerHoldMs(shownAtMillis = 1_000, nowMillis = 1_000 + READ_SPINNER_MIN_VISIBLE_MS - 400))
        assertEquals(0L, spinnerHoldMs(shownAtMillis = 1_000, nowMillis = 1_000 + READ_SPINNER_MIN_VISIBLE_MS))
        assertEquals(0L, spinnerHoldMs(shownAtMillis = 1_000, nowMillis = 99_000))
    }
}
