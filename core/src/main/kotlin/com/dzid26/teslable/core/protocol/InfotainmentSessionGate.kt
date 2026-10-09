// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

/**
 * Tracks whether an Infotainment read is waiting for its session handshake, and when the handshake
 * may be requested (ADR-0009).
 *
 * The Infotainment session is requested only on demand: when [InfotainmentPollPolicy] says a read is
 * due and VCSEC says the car is awake. A sleeping car cannot answer, and the request might wake it.
 * The wait ends in exactly one of two ways: the session is established ([onSessionEstablished], the
 * read then goes out), or it is abandoned ([onGaveUp], [onAsleep], [onLinkDropped],
 * [onNotStartable]). A wait that is never ended would make the caller think a handshake is still in
 * flight and never start another one; a real car logged VCSEC status for a whole drive without a
 * single read that way.
 *
 * Pure, so it ports to other platforms; one instance per vehicle link, not thread-safe.
 */
class InfotainmentSessionGate {
    /** What to do about a read that is due. */
    enum class Action {
        /** A session exists: send the read now. */
        SEND_READ,

        /** No session and none requested yet: request it; the read follows on success. */
        START_HANDSHAKE,

        /** A request is already in flight: do not send another; the read follows on success. */
        WAIT_FOR_HANDSHAKE,

        /** The car is not awake: do nothing. */
        SKIP,
    }

    /** A read is waiting for the handshake to finish; this also means a handshake is in flight. */
    var readWaiting = false
        private set

    /** Decides what to do with a read that is due. [awake] is the latest VCSEC verdict. */
    fun onReadDue(
        awake: Boolean,
        hasSession: Boolean,
    ): Action =
        when {
            !awake -> {
                readWaiting = false
                Action.SKIP
            }

            hasSession -> Action.SEND_READ

            readWaiting -> Action.WAIT_FOR_HANDSHAKE

            else -> {
                readWaiting = true
                Action.START_HANDSHAKE
            }
        }

    /** Whether the Infotainment session may be requested now (retries included). */
    fun mayRequestSession(awake: Boolean): Boolean = readWaiting && awake

    /** The Infotainment session is established. True when a read was waiting and should go out now. */
    fun onSessionEstablished(): Boolean {
        val waiting = readWaiting
        readWaiting = false
        return waiting
    }

    /** The handshake retries ran out. */
    fun onGaveUp() = clear()

    /** VCSEC reports the car asleep: no handshake can finish. */
    fun onAsleep() = clear()

    /** The BLE link dropped or was closed: the handshake went down with it. */
    fun onLinkDropped() = clear()

    /** The handshake could not even be sent (no key, no valid VIN). */
    fun onNotStartable() = clear()

    private fun clear() {
        readWaiting = false
    }
}
