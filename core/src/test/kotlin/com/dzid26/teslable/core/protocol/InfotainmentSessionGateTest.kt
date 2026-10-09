// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.dzid26.teslable.core.protocol.InfotainmentSessionGate.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfotainmentSessionGateTest {
    private val gate = InfotainmentSessionGate()

    @Test
    fun `read with a session goes out without a handshake`() {
        assertEquals(Action.SEND_READ, gate.onReadDue(awake = true, hasSession = true))
        assertFalse(gate.readWaiting)
    }

    @Test
    fun `first due read starts the handshake and later ones wait for it`() {
        assertEquals(Action.START_HANDSHAKE, gate.onReadDue(awake = true, hasSession = false))
        assertTrue(gate.readWaiting)
        assertEquals(Action.WAIT_FOR_HANDSHAKE, gate.onReadDue(awake = true, hasSession = false))
        assertEquals(Action.WAIT_FOR_HANDSHAKE, gate.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `established session releases the waiting read once`() {
        gate.onReadDue(awake = true, hasSession = false)
        assertTrue(gate.onSessionEstablished())
        assertFalse(gate.readWaiting)
        assertFalse(gate.onSessionEstablished())
    }

    @Test
    fun `session is requested only while awake and a read waits`() {
        assertFalse(gate.mayRequestSession(awake = true))
        gate.onReadDue(awake = true, hasSession = false)
        assertTrue(gate.mayRequestSession(awake = true))
        assertFalse(gate.mayRequestSession(awake = false))
    }

    @Test
    fun `a sleeping car never starts a handshake`() {
        assertEquals(Action.SKIP, gate.onReadDue(awake = false, hasSession = false))
        assertFalse(gate.readWaiting)
        assertFalse(gate.mayRequestSession(awake = false))
    }

    @Test
    fun `give-up unblocks the next due read`() {
        gate.onReadDue(awake = true, hasSession = false)
        gate.onGaveUp()
        assertFalse(gate.readWaiting)
        assertEquals(Action.START_HANDSHAKE, gate.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `car falling asleep unblocks the next due read after the wake`() {
        gate.onReadDue(awake = true, hasSession = false)
        gate.onAsleep()
        assertFalse(gate.readWaiting)
        assertEquals(Action.START_HANDSHAKE, gate.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `dropped link unblocks the next due read after the reconnect`() {
        gate.onReadDue(awake = true, hasSession = false)
        gate.onLinkDropped()
        assertFalse(gate.readWaiting)
        assertEquals(Action.START_HANDSHAKE, gate.onReadDue(awake = true, hasSession = false))
    }

    @Test
    fun `unstartable handshake does not leave a read waiting`() {
        gate.onReadDue(awake = true, hasSession = false)
        gate.onNotStartable()
        assertFalse(gate.readWaiting)
    }

    @Test
    fun `waiting read is dropped when the car is found asleep at the next due read`() {
        gate.onReadDue(awake = true, hasSession = false)
        assertEquals(Action.SKIP, gate.onReadDue(awake = false, hasSession = false))
        assertFalse(gate.readWaiting)
    }
}
