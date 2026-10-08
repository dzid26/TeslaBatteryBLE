// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfotainmentPollPolicyTest {
    private val policy = InfotainmentPollPolicy()
    private var now = 1_000_000L

    private fun status(
        locked: Boolean = true,
        asleep: Boolean = false,
        userPresent: Boolean = false,
        closures: ClosureStatuses? = null,
    ) = TeslaVcsec.Status(locked, asleep, userPresent, closures?.let { VehicleStatus(closureStatuses = it) })

    private fun open(
        closures: ClosureStatuses,
        locked: Boolean = true,
    ) = status(locked = locked, closures = closures)

    private val doorOpen = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN)

    /** Advances the clock by one 10 s VCSEC tick and feeds the status; returns whether a read is due. */
    private fun tick(
        status: TeslaVcsec.Status = status(),
        sessionReady: Boolean = true,
    ): Boolean {
        now += TICK
        return policy.onStatus(status, sessionReady, now)
    }

    /** Ticks for [ms] and returns the times (ms since the start) at which a read was due. */
    private fun readsOver(
        ms: Long,
        status: TeslaVcsec.Status = status(),
    ): List<Long> {
        val start = now
        val reads = mutableListOf<Long>()
        while (now - start < ms) if (tick(status)) reads += now - start
        return reads
    }

    private fun connectAndFirstRead() {
        policy.onConnect(now)
        assertTrue(policy.onStatus(status(), true, now), "first status after connect reads")
    }

    /** Lets the window lapse with nothing happening. */
    private fun expireWindow(status: TeslaVcsec.Status = status()) {
        readsOver(InfotainmentPollPolicy.IDLE_WINDOW_MS + 60_000, status)
    }

    /** Within the window: reads are exactly 30 s apart. */
    private fun assertWindowCadence(status: TeslaVcsec.Status = status()) {
        val gaps = readsOver(300_000L, status).zipWithNext { a, b -> b - a }
        assertTrue(gaps.size >= 8 && gaps.all { it == 30_000L }, "gaps $gaps")
    }

    /** Window expired and nothing triggered: reads are exactly 660 s apart. */
    private fun assertIdleCadence(status: TeslaVcsec.Status = status()) {
        val gaps = readsOver(4 * 660_000L, status).zipWithNext { a, b -> b - a }
        assertTrue(gaps.size >= 2 && gaps.all { it == 660_000L }, "gaps $gaps")
    }

    @Test
    fun chargingReadsEveryTenSeconds() {
        connectAndFirstRead()
        repeat(6) {
            policy.onChargeReading("Charging", now)
            assertTrue(tick(), "tick $it")
        }
    }

    @Test
    fun startingCountsAsActive() {
        connectAndFirstRead()
        policy.onChargeReading("Starting", now)
        assertTrue(tick())
        policy.onChargeReading("Starting", now)
        assertTrue(tick())
    }

    @Test
    fun drivingInDriveReverseOrNeutralReadsEveryTenSeconds() {
        for (shift in listOf("D", "R", "N")) {
            connectAndFirstRead()
            repeat(3) {
                policy.onDriveReading(shift, now)
                assertTrue(tick(), "$shift $it")
            }
        }
    }

    @Test
    fun parkedAndNonChargingStatesAreNotActive() {
        for (name in listOf("Complete", "Stopped", "Disconnected", "NoPower", "Calibrating", "Unknown", null)) {
            connectAndFirstRead()
            policy.onChargeReading(name, now)
            policy.onDriveReading("P", now)
            assertFalse(tick(), "$name")
        }
        connectAndFirstRead()
        policy.onDriveReading("Invalid", now)
        assertFalse(tick())
    }

    @Test
    fun activityEndsWhenTheReadingsStopShowingIt() {
        connectAndFirstRead()
        policy.onChargeReading("Charging", now)
        assertTrue(tick())
        policy.onChargeReading("Complete", now)
        assertFalse(tick())
    }

    @Test
    fun windowReadsEveryThirtySecondsThenStopsAtExpiry() {
        connectAndFirstRead()
        val reads = readsOver(InfotainmentPollPolicy.IDLE_WINDOW_MS + 1_320_000L)
        val window = reads.takeWhile { it < InfotainmentPollPolicy.IDLE_WINDOW_MS }
        assertEquals((1..21).map { it * 30_000L }, window)
        // Then one read per 660 s, counted from the last window read.
        assertEquals(listOf(630_000L + 660_000L, 630_000L + 1_320_000L), reads.drop(window.size))
    }

    @Test
    fun idleReadsEverySixHundredSixtySeconds() {
        connectAndFirstRead()
        expireWindow()
        assertIdleCadence()
    }

    @Test
    fun activeReadingsKeepRestartingTheWindow() {
        connectAndFirstRead()
        repeat(100) {
            policy.onChargeReading("Charging", now)
            assertTrue(tick())
        }
        policy.onChargeReading("Charging", now)
        policy.onChargeReading("Complete", now)
        val reads = readsOver(InfotainmentPollPolicy.IDLE_WINDOW_MS)
        assertEquals((1..21).map { it * 30_000L }, reads)
    }

    @Test
    fun noReadsWhileAsleep() {
        connectAndFirstRead()
        assertTrue(readsOver(2 * 660_000L, status(asleep = true)).isEmpty())
    }

    @Test
    fun noReadsWithoutASession() {
        policy.onConnect(now)
        assertFalse(tick(sessionReady = false))
        assertFalse(tick(sessionReady = false))
        assertTrue(tick(sessionReady = true), "reads as soon as the session exists")
    }

    @Test
    fun wakeFromSleepReadsOnceAndDoesNotRestartTheWindow() {
        connectAndFirstRead()
        expireWindow()
        readsOver(5 * TICK, status(asleep = true))
        assertTrue(tick(), "one read right away on wake")
        policy.onChargeReading("Disconnected", now)
        policy.onDriveReading("P", now)
        assertIdleCadence()
    }

    @Test
    fun wakeReadShowingActivityTakesOverAtTenSeconds() {
        connectAndFirstRead()
        expireWindow()
        readsOver(3 * TICK, status(asleep = true))
        assertTrue(tick())
        policy.onChargeReading("Charging", now)
        assertTrue(tick())
        policy.onChargeReading("Charging", now)
        assertTrue(tick())
    }

    @Test
    fun wakeReadWaitsForTheSession() {
        connectAndFirstRead()
        readsOver(3 * TICK, status(asleep = true))
        assertFalse(tick(sessionReady = false))
        assertTrue(tick(sessionReady = true))
        assertFalse(tick(), "the wake read is not repeated")
    }

    @Test
    fun readingsFromBeforeSleepDoNotCountAsActiveAfterWake() {
        connectAndFirstRead()
        policy.onChargeReading("Charging", now)
        readsOver(3 * TICK, status(asleep = true))
        assertTrue(tick(), "wake read")
        assertFalse(tick(), "stale charging reading must not keep the 10 s cadence")
    }

    @Test
    fun reconnectRestartsTheWindow() {
        connectAndFirstRead()
        expireWindow()
        connectAndFirstRead()
        assertWindowCadence()
    }

    @Test
    fun lockedToUnlockedRestartsTheWindow() {
        connectAndFirstRead()
        expireWindow()
        tick(status(locked = false))
        assertWindowCadence(status(locked = false))
    }

    @Test
    fun everyClosureOpeningRestartsTheWindow() {
        val openings =
            listOf(
                ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(frontPassengerDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(rearDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(rearPassengerDoor = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(rearTrunk = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(frontTrunk = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(chargePort = ClosureState_E.CLOSURESTATE_OPEN),
                ClosureStatuses(tonneau = ClosureState_E.CLOSURESTATE_OPEN),
            )
        for (closures in openings) {
            connectAndFirstRead()
            expireWindow()
            tick(open(closures))
            assertWindowCadence(open(closures))
        }
    }

    @Test
    fun aSecondClosureOpeningWhileOneIsAlreadyOpenRestartsTheWindow() {
        connectAndFirstRead()
        tick(open(doorOpen))
        expireWindow()
        val both = doorOpen.copy(chargePort = ClosureState_E.CLOSURESTATE_OPEN)
        tick(open(both))
        assertWindowCadence(open(both))
    }

    @Test
    fun userRequestRestartsTheWindowAndCountsAsTheRead() {
        connectAndFirstRead()
        expireWindow()
        policy.onUserRequest(now)
        assertFalse(tick(), "the request itself was the read")
        assertWindowCadence()
    }

    @Test
    fun userPresenceDoesNotRestartTheWindow() {
        connectAndFirstRead()
        expireWindow()
        val present = status(userPresent = true)
        tick(present)
        assertIdleCadence(present)
    }

    @Test
    fun stayingUnlockedDoesNotRestartTheWindow() {
        connectAndFirstRead()
        tick(status(locked = false)) // the unlock itself is a trigger
        expireWindow(status(locked = false))
        assertIdleCadence(status(locked = false))
    }

    @Test
    fun lockingDoesNotRestartTheWindow() {
        connectAndFirstRead()
        tick(status(locked = false))
        expireWindow()
        tick(status(locked = true))
        assertIdleCadence(status(locked = true))
    }

    @Test
    fun closingAClosureDoesNotRestartTheWindow() {
        connectAndFirstRead()
        tick(open(doorOpen))
        expireWindow()
        tick(open(ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_CLOSED)))
        assertIdleCadence()
    }

    @Test
    fun aClosureAlreadyOpenAtConnectIsNotATrigger() {
        policy.onConnect(now)
        assertTrue(policy.onStatus(open(doorOpen), true, now))
        expireWindow(open(doorOpen))
        assertIdleCadence(open(doorOpen))
    }

    @Test
    fun aStatusWithoutClosuresIsNotATrigger() {
        connectAndFirstRead()
        tick(open(doorOpen))
        expireWindow()
        tick(TeslaVcsec.Status(locked = true, asleep = false, userPresent = false))
        assertIdleCadence()
    }

    private companion object {
        const val TICK = 10_000L
    }
}
