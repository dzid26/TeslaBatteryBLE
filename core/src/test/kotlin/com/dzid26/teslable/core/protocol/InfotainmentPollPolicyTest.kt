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

    private val doorOpen = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN)
    private val doorClosed = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_CLOSED)

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
        assertTrue(policy.onStatus(status(), true, now), "first awake status after connect reads")
    }

    /** Lets the connect follow-up pass, so the next assertions see a quiet, idle car. */
    private fun settle() {
        assertEquals(listOf(FOLLOW_UP), readsOver(10 * 60_000L))
    }

    @Test
    fun chargingReadsEveryTenSeconds() {
        connectAndFirstRead()
        repeat(6) {
            policy.onChargeReading("Charging")
            assertTrue(tick(), "tick $it")
        }
    }

    @Test
    fun startingCountsAsActive() {
        connectAndFirstRead()
        policy.onChargeReading("Starting")
        assertTrue(tick())
        assertTrue(tick())
    }

    @Test
    fun drivingInDriveReverseOrNeutralReadsEveryTenSeconds() {
        for (shift in listOf("D", "R", "N")) {
            connectAndFirstRead()
            repeat(3) {
                policy.onDriveReading(shift)
                assertTrue(tick(), "$shift $it")
            }
        }
    }

    @Test
    fun parkedAndNonChargingStatesAreNotActive() {
        for (name in listOf("Complete", "Stopped", "Disconnected", "NoPower", "Calibrating", "Unknown", null)) {
            connectAndFirstRead()
            policy.onChargeReading(name)
            policy.onDriveReading("P")
            assertFalse(tick(), "$name")
        }
        connectAndFirstRead()
        policy.onDriveReading("Invalid")
        assertFalse(tick())
    }

    @Test
    fun readsStopRightAwayWhenActivityEnds() {
        connectAndFirstRead()
        policy.onChargeReading("Charging")
        assertTrue(tick())
        policy.onChargeReading("Complete")
        assertFalse(tick())
        // Only the connect follow-up remains: no window, no idle reads.
        assertEquals(listOf(FOLLOW_UP - 2 * TICK), readsOver(60 * 60_000L))
    }

    @Test
    fun activeCadenceKeepsGoingForHours() {
        connectAndFirstRead()
        repeat(2_000) {
            policy.onDriveReading("D")
            assertTrue(tick())
        }
    }

    @Test
    fun connectReadsOnceThenFollowsUpOnceAfterSixtySeconds() {
        connectAndFirstRead()
        assertEquals(listOf(FOLLOW_UP), readsOver(60 * 60_000L))
    }

    @Test
    fun connectWhileAsleepReadsNothingUntilTheWake() {
        policy.onConnect(now)
        assertTrue(readsOver(30 * 60_000L, status(asleep = true)).isEmpty())
        assertTrue(tick(), "wake read")
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L))
    }

    @Test
    fun noReadsWhileAsleep() {
        connectAndFirstRead()
        policy.onChargeReading("Charging")
        assertTrue(readsOver(2 * 60 * 60_000L, status(asleep = true)).isEmpty())
    }

    @Test
    fun asleepDropsAPendingFollowUp() {
        connectAndFirstRead()
        readsOver(2 * TICK, status(asleep = true))
        // The connect follow-up fell due while asleep and is gone; only the wake's own follow-up is left.
        assertTrue(readsOver(FOLLOW_UP, status(asleep = true)).isEmpty())
        assertTrue(tick(), "wake read")
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L))
    }

    @Test
    fun noReadsWithoutASession() {
        policy.onConnect(now)
        assertFalse(tick(sessionReady = false))
        assertFalse(tick(sessionReady = false))
        assertTrue(tick(sessionReady = true), "reads as soon as the session exists")
    }

    @Test
    fun wakeFromSleepReadsAtOnceThenOnceMore() {
        connectAndFirstRead()
        settle()
        readsOver(5 * TICK, status(asleep = true))
        assertTrue(tick(), "read right away on wake")
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L))
    }

    @Test
    fun wakeReadShowingActivityTakesOverAtTenSeconds() {
        connectAndFirstRead()
        settle()
        readsOver(3 * TICK, status(asleep = true))
        assertTrue(tick())
        policy.onChargeReading("Charging")
        assertTrue(tick())
        assertTrue(tick())
    }

    @Test
    fun wakeReadWaitsForTheSession() {
        connectAndFirstRead()
        settle()
        readsOver(3 * TICK, status(asleep = true))
        assertFalse(tick(sessionReady = false))
        assertTrue(tick(sessionReady = true))
        assertFalse(tick(), "the wake read is not repeated")
    }

    @Test
    fun readingsFromBeforeSleepDoNotCountAsActiveAfterWake() {
        connectAndFirstRead()
        policy.onChargeReading("Charging")
        readsOver(3 * TICK, status(asleep = true))
        assertTrue(tick(), "wake read")
        assertFalse(tick(), "a stale charging reading must not keep the 10 s cadence")
    }

    @Test
    fun lockedToUnlockedReadsAtOnceThenOnceMore() {
        connectAndFirstRead()
        settle()
        assertTrue(tick(status(locked = false)))
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L, status(locked = false)))
    }

    @Test
    fun openingAnyClosureReadsAtOnceThenOnceMore() {
        val all =
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
        for (closures in all) {
            connectAndFirstRead()
            settle()
            assertTrue(tick(status(closures = closures)), "$closures")
            assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L, status(closures = closures)), "$closures")
        }
    }

    @Test
    fun closingAClosureIsAnEventToo() {
        connectAndFirstRead()
        assertTrue(tick(status(closures = doorOpen)), "opening reads")
        settle2(status(closures = doorOpen))
        assertTrue(tick(status(closures = doorClosed)))
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L, status(closures = doorClosed)))
    }

    @Test
    fun aSecondClosureChangingWhileOneIsOpenIsAnEvent() {
        connectAndFirstRead()
        tick(status(closures = doorOpen))
        settle2(status(closures = doorOpen))
        val both = doorOpen.copy(chargePort = ClosureState_E.CLOSURESTATE_OPEN)
        assertTrue(tick(status(closures = both)))
    }

    @Test
    fun userRequestCountsAsTheReadThenFollowsUpOnce() {
        connectAndFirstRead()
        settle()
        policy.onUserRequest(now)
        assertFalse(tick(), "the request itself was the read")
        assertEquals(listOf(FOLLOW_UP - TICK), readsOver(30 * 60_000L))
    }

    @Test
    fun aNewEventReplacesAPendingFollowUp() {
        connectAndFirstRead()
        readsOver(40_000L) // 40 s into the connect follow-up
        assertTrue(tick(status(locked = false)), "unlock event at 50 s")
        // Only one follow-up, 60 s after the unlock, not one for the connect as well.
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L, status(locked = false)))
    }

    @Test
    fun aClosureAlreadyOpenAtConnectIsNotAnEvent() {
        policy.onConnect(now)
        assertTrue(policy.onStatus(status(closures = doorOpen), true, now))
        // Only the connect follow-up, nothing for the open door.
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L, status(closures = doorOpen)))
    }

    @Test
    fun userPresenceIsNotAnEvent() {
        connectAndFirstRead()
        settle()
        assertTrue(readsOver(60 * 60_000L, status(userPresent = true)).isEmpty())
    }

    @Test
    fun stayingUnlockedIsNotAnEvent() {
        connectAndFirstRead()
        tick(status(locked = false))
        readsOver(30 * 60_000L, status(locked = false))
        assertTrue(readsOver(60 * 60_000L, status(locked = false)).isEmpty())
    }

    @Test
    fun lockingIsNotAnEvent() {
        connectAndFirstRead()
        tick(status(locked = false))
        readsOver(30 * 60_000L, status(locked = false))
        assertFalse(tick(status(locked = true)))
        assertTrue(readsOver(60 * 60_000L, status(locked = true)).isEmpty())
    }

    @Test
    fun aStatusWithoutClosuresIsNotAnEvent() {
        connectAndFirstRead()
        settle()
        assertTrue(readsOver(10 * 60_000L, TeslaVcsec.Status(locked = true, asleep = false, userPresent = false)).isEmpty())
    }

    /** Lets any pending follow-up pass with a fixed status. */
    private fun settle2(status: TeslaVcsec.Status) {
        readsOver(10 * 60_000L, status)
    }

    private companion object {
        const val TICK = 10_000L
        const val FOLLOW_UP = InfotainmentPollPolicy.FOLLOW_UP_DELAY_MS
    }
}
