// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleStatus

/**
 * When to ask the car for its Infotainment state (charge + drive).
 *
 * Any Infotainment request restarts the car's own sleep countdown (about 10 minutes), so polling it
 * while the car is merely awake keeps it awake (ADR-0009, issue #112). VCSEC status does not, and
 * keeps the BLE link alive, so it stays on its own 10 s cadence outside this class. The rules:
 *
 * 1. Never while asleep (asleep also drops the hold), and never to wake the car: reads only when
 *    VCSEC says awake and a session exists.
 * 2. A read every [READ_INTERVAL_MS] while either
 *    - the car is active (see [isActive]: charging, driving), or
 *    - the hold is running: [HOLD_PRESENT_MS] after the last status change if the latest status shows
 *      user presence, otherwise [HOLD_ABSENT_MS]. Presence is re-evaluated on every status, so a
 *      person leaving cuts the remaining hold to the short one.
 * 3. Status changes start or restart the hold: asleep to awake, any lock-state change (locking too,
 *    which catches drive-away locking), any closure changing between open and not open (a closure
 *    that is neither CLOSED nor UNKNOWN counts as open, so ajar is open), a fresh start (see
 *    [onFreshStart]) and a user request. A change in user presence alone is not a change: it flips
 *    with phone-key range and would keep resetting the car's sleep countdown.
 *
 * Reconnects: the transition memory (previous asleep, locked and closure state) survives a dropped
 * link, so a wake, lock change or door change that happened while the link was down still starts the
 * hold on the first status after it. The caller therefore does nothing at a plain reconnect.
 *
 * Pure and clock-free (the caller passes `nowMillis`) so it ports to other platforms; one instance
 * per vehicle link, not thread-safe.
 */
class InfotainmentPollPolicy {
    private var lastReadAtMs: Long? = null
    private var lastChangeAtMs: Long? = null
    private var userPresent = false
    private var started = false
    private var charging = false
    private var driving = false
    private var previousAsleep: Boolean? = null
    private var previousLocked: Boolean? = null
    private var previousOpenClosures: Int? = null

    /**
     * Whether the car is doing something that warrants a read every [READ_INTERVAL_MS] on its own.
     * Extension point: sentry mode and climate join charging and driving here later.
     */
    private fun isActive(): Boolean = charging || driving

    /**
     * A fresh start: the first READY link since the app process or tracking started (app start,
     * auto-start after boot or update, tracking switched on), or the first READY link right after
     * pairing or key enrollment. Forgets everything, including the transition memory, and starts the
     * hold. Not for a reconnect after a dropped link.
     */
    fun onFreshStart(nowMillis: Long) {
        started = true
        lastReadAtMs = null
        charging = false
        driving = false
        previousAsleep = null
        previousLocked = null
        previousOpenClosures = null
        lastChangeAtMs = nowMillis
    }

    /**
     * A link became READY. The first one since this policy was created (app or tracking start, as
     * the controller makes one policy per link) is a [fresh start][onFreshStart]; later ones are
     * plain reconnects and change nothing.
     */
    fun onLinkReady(nowMillis: Long) {
        if (!started) onFreshStart(nowMillis)
    }

    /**
     * The user asked for a reading (refresh button). The caller sends the read immediately; this
     * counts it as the latest read and starts the hold.
     */
    fun onUserRequest(nowMillis: Long) {
        lastReadAtMs = nowMillis
        lastChangeAtMs = nowMillis
    }

    /** The latest charge reading. Charging or Starting is activity. */
    fun onChargeReading(chargingState: ChargingStateKind?) {
        charging = chargingState == ChargingStateKind.Charging || chargingState == ChargingStateKind.Starting
    }

    /** The latest drive reading. Shift state D, R or N is activity. */
    fun onDriveReading(shiftState: ShiftStateKind?) {
        driving = shiftState == ShiftStateKind.D || shiftState == ShiftStateKind.R || shiftState == ShiftStateKind.N
    }

    /**
     * Feeds one VCSEC status and answers whether an Infotainment read is due now. Returns true at
     * most once per due read: it records the read, so the caller must then send it. With
     * [sessionReady] false (no Infotainment session or VIN yet) nothing is recorded and the answer
     * is false; the hold keeps running, so a read still follows once the session exists.
     */
    fun onStatus(
        status: VehicleStatus,
        sessionReady: Boolean,
        nowMillis: Long,
    ): Boolean {
        observe(status, nowMillis)
        if (status.asleep) {
            lastChangeAtMs = null
            charging = false
            driving = false
            return false
        }
        if (!sessionReady || !(isActive() || holding(nowMillis))) return false
        val last = lastReadAtMs
        if (last != null && nowMillis - last < READ_INTERVAL_MS - DUE_SLACK_MS) return false
        lastReadAtMs = nowMillis
        return true
    }

    private fun holding(nowMillis: Long): Boolean {
        val changedAt = lastChangeAtMs ?: return false
        return nowMillis - changedAt < if (userPresent) HOLD_PRESENT_MS else HOLD_ABSENT_MS
    }

    private fun observe(
        status: VehicleStatus,
        nowMillis: Long,
    ) {
        val openClosures = openClosureMask(status.closureStatuses)
        val wokeUp = previousAsleep == true && !status.asleep
        val lockChanged = previousLocked?.let { it != status.locked } == true
        val closureChanged = previousOpenClosures?.let { it != openClosures } == true
        if (wokeUp || lockChanged || closureChanged) lastChangeAtMs = nowMillis
        userPresent = status.userPresent
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
            if (state.isOpen()) mask or (1 shl index) else mask
        }
    }

    private fun ClosureState_E.isOpen() = this != ClosureState_E.CLOSURESTATE_CLOSED && this != ClosureState_E.CLOSURESTATE_UNKNOWN

    companion object {
        /** Reads while active or holding, matching the VCSEC status cadence. */
        const val READ_INTERVAL_MS = 10_000L

        /** How long after a status change reads continue while the person is in or near the car. */
        const val HOLD_PRESENT_MS = 600_000L

        /** How long after a status change reads continue when nobody is present. */
        const val HOLD_ABSENT_MS = 60_000L

        /**
         * VCSEC statuses arrive about every 10 s give or take a few ms, so a read is due slightly
         * early rather than slipping a whole tick.
         */
        const val DUE_SLACK_MS = 1_000L
    }
}
