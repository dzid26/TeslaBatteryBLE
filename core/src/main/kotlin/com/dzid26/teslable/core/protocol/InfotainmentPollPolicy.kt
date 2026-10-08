// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses

/**
 * When to ask the car for its Infotainment state (charge + drive).
 *
 * Any Infotainment request restarts the car's own sleep countdown (about 10 minutes), so polling it
 * while the car is merely awake keeps it awake (ADR-0009, issue #112). VCSEC status does not, and
 * keeps the BLE link alive, so it stays on its own 10 s cadence outside this class. The rules:
 *
 * 1. Never while asleep, and never to wake the car: reads only when VCSEC says awake and a session
 *    exists.
 * 2. Active (charging state Charging or Starting, or shift state D, R or N): a read every
 *    [ACTIVE_INTERVAL_MS]. When activity ends the reads stop right away.
 * 3. Not active: single reads on events, each sent at once. Events: a (re)connect, VCSEC asleep to
 *    awake, locked to unlocked, any closure changing state (opening or closing), and an explicit
 *    user request. Each event also schedules one follow-up read [FOLLOW_UP_DELAY_MS] later, to catch
 *    a shift into D or charging starting just after it. A new event replaces a pending follow-up
 *    instead of stacking. Then nothing until the next event.
 * 4. User presence, and staying unlocked or locked without a closure change, are not events.
 *
 * Pure and clock-free (the caller passes `nowMillis`) so it ports to other platforms; one instance
 * per vehicle link, not thread-safe.
 */
class InfotainmentPollPolicy {
    private var lastReadAtMs: Long? = null
    private var followUpAtMs: Long? = null
    private var readPending = false
    private var charging = false
    private var driving = false
    private var previousAsleep: Boolean? = null
    private var previousLocked: Boolean? = null
    private var previousOpenClosures: Int? = null

    private val active: Boolean get() = charging || driving

    /** A (re)connect: forgets the previous link, reads at the first awake status, follows up once. */
    fun onConnect(nowMillis: Long) {
        lastReadAtMs = null
        charging = false
        driving = false
        previousAsleep = null
        previousLocked = null
        previousOpenClosures = null
        event(nowMillis)
    }

    /**
     * The user asked for a reading (refresh button, notification wake). The caller sends the read
     * immediately; this counts it as the latest read and schedules the follow-up.
     */
    fun onUserRequest(nowMillis: Long) {
        lastReadAtMs = nowMillis
        readPending = false
        followUpAtMs = nowMillis + FOLLOW_UP_DELAY_MS
    }

    /** The latest charge reading. Charging or Starting is activity. */
    fun onChargeReading(chargingStateName: String?) {
        charging = chargingStateName in CHARGING_STATES
    }

    /** The latest drive reading. Shift state D, R or N is activity. */
    fun onDriveReading(shiftStateName: String?) {
        driving = shiftStateName in DRIVING_SHIFT_STATES
    }

    /**
     * Feeds one VCSEC status and answers whether an Infotainment read is due now. Returns true at
     * most once per due read: it records the read, so the caller must then send it. With
     * [sessionReady] false (no Infotainment session or VIN yet) nothing is recorded and the answer
     * is false, so an event read waits for the session. Asleep drops everything pending.
     */
    fun onStatus(
        status: TeslaVcsec.Status,
        sessionReady: Boolean,
        nowMillis: Long,
    ): Boolean {
        observe(status, nowMillis)
        if (status.asleep) {
            readPending = false
            followUpAtMs = null
            charging = false
            driving = false
            return false
        }
        if (!sessionReady) return false
        val followUpDue = followUpAtMs?.let { nowMillis >= it - DUE_SLACK_MS } == true
        val last = lastReadAtMs
        val activeDue = active && (last == null || nowMillis - last >= ACTIVE_INTERVAL_MS - DUE_SLACK_MS)
        if (!readPending && !followUpDue && !activeDue) return false
        lastReadAtMs = nowMillis
        readPending = false
        if (followUpDue) followUpAtMs = null
        return true
    }

    private fun event(nowMillis: Long) {
        readPending = true
        followUpAtMs = nowMillis + FOLLOW_UP_DELAY_MS
    }

    private fun observe(
        status: TeslaVcsec.Status,
        nowMillis: Long,
    ) {
        val openClosures = openClosureMask(status.raw?.closureStatuses)
        val wokeUp = previousAsleep == true && !status.asleep
        val unlocked = previousLocked == true && !status.locked
        val closureChanged = previousOpenClosures?.let { it != openClosures } == true
        if (wokeUp || unlocked || closureChanged) event(nowMillis)
        previousAsleep = status.asleep
        previousLocked = status.locked
        previousOpenClosures = openClosures
    }

    private fun openClosureMask(closures: ClosureStatuses?): Int {
        if (closures == null) return 0
        val states =
            listOf(
                closures.frontDriverDoor,
                closures.frontPassengerDoor,
                closures.rearDriverDoor,
                closures.rearPassengerDoor,
                closures.rearTrunk,
                closures.frontTrunk,
                closures.chargePort,
                closures.tonneau,
            )
        return states.foldIndexed(0) { index, mask, state ->
            if (state == ClosureState_E.CLOSURESTATE_OPEN) mask or (1 shl index) else mask
        }
    }

    companion object {
        /** Reads while charging or driving, matching the VCSEC status cadence. */
        const val ACTIVE_INTERVAL_MS = 10_000L

        /** How long after an event the one follow-up read comes. */
        const val FOLLOW_UP_DELAY_MS = 60_000L

        /**
         * VCSEC statuses arrive about every 10 s give or take a few ms, so a read is due slightly
         * early rather than slipping a whole tick.
         */
        const val DUE_SLACK_MS = 1_000L

        private val CHARGING_STATES = setOf("Charging", "Starting")
        private val DRIVING_SHIFT_STATES = setOf("D", "R", "N")
    }
}
