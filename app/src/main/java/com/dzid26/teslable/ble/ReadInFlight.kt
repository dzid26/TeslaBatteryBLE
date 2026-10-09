// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

/**
 * Whether an Infotainment read (charge, drive, closures and climate, in sequence) is in
 * flight for one car, for the spinner on its card. The VCSEC status poll does not count.
 *
 * It turns on when a read request goes out ([onSent]) and off at every exit: a reply that sends
 * no follow-up (the climate reply, a failure, a refusal, a reply without data, or a follow-up
 * that could not be sent) via [onReply] with no [onSent] after it, the gate skipping the read
 * because the car is asleep ([onSkipped]), the link dropping ([onLinkDropped]), or no activity
 * for [TIMEOUT_MS] ([expire]), so it can never stay on.
 * One instance per vehicle link, used on the main thread.
 *
 * @param onChange called with the new value whenever [active] changes, never with the same value twice.
 */
internal class ReadInFlight(
    private val onChange: (Boolean) -> Unit = {},
) {
    private var lastActivityMs: Long? = null

    /** True while a read is in flight. */
    val active: Boolean get() = lastActivityMs != null

    /** A read request (the first or a follow-up) went out at [nowMs]. */
    fun onSent(nowMs: Long) {
        val was = active
        lastActivityMs = nowMs
        if (!was) onChange(true)
    }

    /** A read reply arrived. A follow-up sent after it (via [onSent]) keeps the read in flight. */
    fun onReply() = clear()

    /** The read was skipped because the car is asleep. */
    fun onSkipped() = clear()

    /** The link dropped or was closed. */
    fun onLinkDropped() = clear()

    /** Ends the read when nothing happened for [TIMEOUT_MS]; returns whether it did. */
    fun expire(nowMs: Long): Boolean {
        val last = lastActivityMs ?: return false
        if (nowMs - last < TIMEOUT_MS) return false
        clear()
        return true
    }

    private fun clear() {
        if (!active) return
        lastActivityMs = null
        onChange(false)
    }

    companion object {
        /** The same wait the command log gives a request before it counts as unanswered. */
        const val TIMEOUT_MS = CommandRecorder.TIMEOUT_MS
    }
}
