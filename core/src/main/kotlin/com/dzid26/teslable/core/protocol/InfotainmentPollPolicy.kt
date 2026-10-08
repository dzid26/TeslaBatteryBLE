// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses

/**
 * When to ask the car for its Infotainment state (charge + drive).
 *
 * Infotainment reads keep a car awake, so polling them every 10 s whenever VCSEC says "awake" stops
 * it from ever falling asleep (ADR-0009, issue #112). VCSEC status is not affected and keeps the
 * link alive, so it stays on its own 10 s cadence outside this class. The rules:
 *
 * 1. Asleep: no reads. Never wake the car.
 * 2. Active (charging or driving): a read every [ACTIVE_INTERVAL_MS]; each active reading restarts
 *    the idle timer.
 * 3. Window: for [IDLE_WINDOW_MS] after the last activity or trigger, a read every
 *    [WINDOW_INTERVAL_MS]. Triggers: (re)connect, locked to unlocked, any closure going to open,
 *    and an explicit user request. User presence, staying unlocked, locking and closing do not.
 * 4. Idle (window expired): a read every [IDLE_INTERVAL_MS].
 * 5. Waking up (asleep to awake): one read right away so every wake gets a reading. It does not
 *    restart the window, because a car that blips awake by itself must be allowed to go back to sleep.
 *
 * Modelled on yoziru/esphome-tesla-ble PRs #198 and #213. Pure and clock-free (the caller passes
 * `nowMillis`) so it ports to other platforms; one instance per vehicle link, not thread-safe.
 */
class InfotainmentPollPolicy {
    private var lastReadAtMs: Long? = null
    private var lastActivityAtMs: Long = 0
    private var charging = false
    private var driving = false
    private var wakeReadPending = false
    private var previousAsleep: Boolean? = null
    private var previousLocked: Boolean? = null
    private var previousOpenClosures = 0

    private val active: Boolean get() = charging || driving

    /** A (re)connect: forgets everything about the previous link and starts the idle window. */
    fun onConnect(nowMillis: Long) {
        lastReadAtMs = null
        lastActivityAtMs = nowMillis
        charging = false
        driving = false
        wakeReadPending = false
        previousAsleep = null
        previousLocked = null
        previousOpenClosures = 0
    }

    /**
     * The user asked for a reading (refresh button, notification wake). The caller sends the read
     * immediately; this counts it as the latest read and restarts the window.
     */
    fun onUserRequest(nowMillis: Long) {
        lastActivityAtMs = nowMillis
        lastReadAtMs = nowMillis
        wakeReadPending = false
    }

    /** The latest charge reading. Charging or Starting is activity. */
    fun onChargeReading(
        chargingStateName: String?,
        nowMillis: Long,
    ) {
        charging = chargingStateName in CHARGING_STATES
        if (charging) lastActivityAtMs = nowMillis
    }

    /** The latest drive reading. Shift state D, R or N is activity. */
    fun onDriveReading(
        shiftStateName: String?,
        nowMillis: Long,
    ) {
        driving = shiftStateName in DRIVING_SHIFT_STATES
        if (driving) lastActivityAtMs = nowMillis
    }

    /**
     * Feeds one VCSEC status and answers whether an Infotainment read is due now. Returns true at
     * most once per due read: it records the read, so the caller must then send it. With
     * [sessionReady] false (no Infotainment session or VIN yet) nothing is recorded and the answer
     * is false, so a pending wake read waits for the session.
     */
    fun onStatus(
        status: TeslaVcsec.Status,
        sessionReady: Boolean,
        nowMillis: Long,
    ): Boolean {
        observe(status, nowMillis)
        if (status.asleep || !sessionReady || !isDue(nowMillis)) return false
        lastReadAtMs = nowMillis
        wakeReadPending = false
        return true
    }

    private fun observe(
        status: TeslaVcsec.Status,
        nowMillis: Long,
    ) {
        val openClosures = openClosureMask(status.raw?.closureStatuses)
        val unlocked = previousLocked == true && !status.locked
        val closureOpened = openClosures and previousOpenClosures.inv() != 0
        if (unlocked || closureOpened) lastActivityAtMs = nowMillis
        if (previousAsleep == true && !status.asleep) wakeReadPending = true
        if (status.asleep) {
            // Readings from before sleep say nothing about now.
            charging = false
            driving = false
        }
        previousAsleep = status.asleep
        previousLocked = status.locked
        previousOpenClosures = openClosures
    }

    private fun isDue(nowMillis: Long): Boolean {
        val last = lastReadAtMs
        if (last == null || wakeReadPending) return true
        val interval =
            when {
                active -> ACTIVE_INTERVAL_MS
                nowMillis - lastActivityAtMs < IDLE_WINDOW_MS -> WINDOW_INTERVAL_MS
                else -> IDLE_INTERVAL_MS
            }
        return nowMillis - last >= interval - DUE_SLACK_MS
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

        /** Reads while awake and idle, within [IDLE_WINDOW_MS] of the last activity or trigger. */
        const val WINDOW_INTERVAL_MS = 30_000L

        /** How long after the last activity or trigger the [WINDOW_INTERVAL_MS] cadence lasts. */
        const val IDLE_WINDOW_MS = 660_000L

        /** Reads once the window has expired; the car is free to fall asleep in between. */
        const val IDLE_INTERVAL_MS = 660_000L

        /**
         * VCSEC statuses arrive about every 10 s give or take a few ms, so a read is due slightly
         * early rather than slipping a whole tick.
         */
        const val DUE_SLACK_MS = 1_000L

        private val CHARGING_STATES = setOf("Charging", "Starting")
        private val DRIVING_SHIFT_STATES = setOf("D", "R", "N")
    }
}
