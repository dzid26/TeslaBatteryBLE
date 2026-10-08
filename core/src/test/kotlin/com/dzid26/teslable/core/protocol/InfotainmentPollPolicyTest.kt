// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
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
    ) = VehicleStatus(
        vehicleLockState = if (locked) VehicleLockState_E.VEHICLELOCKSTATE_LOCKED else VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
        vehicleSleepStatus =
            if (asleep) VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP else VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
        userPresence =
            if (userPresent) UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT else UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        closureStatuses = closures,
    )

    private val doorOpen = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN)
    private val doorClosed = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_CLOSED)

    /** Advances the clock by one 10 s VCSEC tick and feeds the status; returns whether a read is due. */
    private fun tick(
        status: VehicleStatus = status(),
        sessionReady: Boolean = true,
    ): Boolean {
        now += TICK
        return policy.onStatus(status, sessionReady, now)
    }

    /** Ticks for [ms] and returns the times (ms since the start) at which a read was due. */
    private fun readsOver(
        ms: Long,
        status: VehicleStatus = status(),
    ): List<Long> {
        val start = now
        val reads = mutableListOf<Long>()
        while (now - start < ms) if (tick(status)) reads += now - start
        return reads
    }

    private fun connectAndFirstRead() {
        policy.onFreshStart(now)
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
            policy.onChargeReading(ChargingStateKind.Charging)
            assertTrue(tick(), "tick $it")
        }
    }

    @Test
    fun startingCountsAsActive() {
        connectAndFirstRead()
        policy.onChargeReading(ChargingStateKind.Starting)
        assertTrue(tick())
        assertTrue(tick())
    }

    @Test
    fun drivingInDriveReverseOrNeutralReadsEveryTenSeconds() {
        for (shift in listOf(ShiftStateKind.D, ShiftStateKind.R, ShiftStateKind.N)) {
            connectAndFirstRead()
            repeat(3) {
                policy.onDriveReading(shift)
                assertTrue(tick(), "$shift $it")
            }
        }
    }

    @Test
    fun parkedAndNonChargingStatesAreNotActive() {
        for (kind in listOf(null) +
            ChargingStateKind.entries.filter { it != ChargingStateKind.Charging && it != ChargingStateKind.Starting }) {
            connectAndFirstRead()
            policy.onChargeReading(kind)
            policy.onDriveReading(ShiftStateKind.P)
            assertFalse(tick(), "$kind")
        }
        connectAndFirstRead()
        policy.onDriveReading(ShiftStateKind.Invalid)
        assertFalse(tick())
    }

    @Test
    fun readsStopRightAwayWhenActivityEnds() {
        connectAndFirstRead()
        policy.onChargeReading(ChargingStateKind.Charging)
        assertTrue(tick())
        policy.onChargeReading(ChargingStateKind.Complete)
        assertFalse(tick())
        // Only the connect follow-up remains: no window, no idle reads.
        assertEquals(listOf(FOLLOW_UP - 2 * TICK), readsOver(60 * 60_000L))
    }

    @Test
    fun activeCadenceKeepsGoingForHours() {
        connectAndFirstRead()
        repeat(2_000) {
            policy.onDriveReading(ShiftStateKind.D)
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
        policy.onFreshStart(now)
        assertTrue(readsOver(30 * 60_000L, status(asleep = true)).isEmpty())
        assertTrue(tick(), "wake read")
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L))
    }

    @Test
    fun aReconnectIsNotAnEventButKeepsTheTransitionMemory() {
        connectAndFirstRead()
        settle()
        // The link drops: no statuses for a while, and the car stays as it was. Nothing to read.
        now += 5 * 60_000L
        assertFalse(policy.onStatus(status(), true, now))
        assertTrue(readsOver(30 * 60_000L).isEmpty())
    }

    @Test
    fun onlyTheFirstLinkReadyIsAFreshStart() {
        policy.onLinkReady(now)
        assertTrue(policy.onStatus(status(), true, now), "first READY reads")
        settle()
        policy.onLinkReady(now) // a reconnect: nothing to read, no follow-up
        assertTrue(readsOver(30 * 60_000L).isEmpty())
    }

    @Test
    fun aWakeWhileTheLinkWasDownIsAnEventAfterTheReconnect() {
        connectAndFirstRead()
        readsOver(3 * TICK, status(asleep = true))
        now += 5 * 60_000L // link down; the car woke meanwhile
        assertTrue(policy.onStatus(status(), true, now))
    }

    @Test
    fun anUnlockOrClosureChangeWhileTheLinkWasDownIsAnEventAfterTheReconnect() {
        connectAndFirstRead()
        settle()
        now += 5 * 60_000L
        assertTrue(policy.onStatus(status(locked = false), true, now))
        settle2(status(locked = false))
        now += 5 * 60_000L
        assertTrue(policy.onStatus(status(locked = false, closures = doorOpen), true, now))
    }

    @Test
    fun aFreshStartForgetsTheTransitionMemory() {
        connectAndFirstRead()
        settle()
        now += 5 * 60_000L
        policy.onFreshStart(now)
        // Unlocked and a door open since before: the fresh start reads once, those are not events.
        val changed = status(locked = false, closures = doorOpen)
        assertTrue(policy.onStatus(changed, true, now))
        assertEquals(listOf(FOLLOW_UP), readsOver(30 * 60_000L, changed))
    }

    @Test
    fun aClosureGoingAjarCountsAsOpening() {
        connectAndFirstRead()
        settle()
        val ajar = status(closures = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_AJAR))
        assertTrue(tick(ajar))
    }

    @Test
    fun anUnknownClosureIsNotOpen() {
        connectAndFirstRead()
        settle()
        val unknown = status(closures = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_UNKNOWN))
        assertFalse(tick(unknown))
    }

    @Test
    fun noReadsWhileAsleep() {
        connectAndFirstRead()
        policy.onChargeReading(ChargingStateKind.Charging)
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
        policy.onFreshStart(now)
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
        policy.onChargeReading(ChargingStateKind.Charging)
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
        policy.onChargeReading(ChargingStateKind.Charging)
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
        policy.onFreshStart(now)
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
    fun anAllClosedStatusAfterOneWithoutClosuresIsNotAnEvent() {
        connectAndFirstRead()
        settle()
        assertTrue(readsOver(10 * 60_000L, status(closures = ClosureStatuses())).isEmpty())
    }

    /** Lets any pending follow-up pass with a fixed status. */
    private fun settle2(status: VehicleStatus) {
        readsOver(10 * 60_000L, status)
    }

    private companion object {
        const val TICK = 10_000L
        const val FOLLOW_UP = InfotainmentPollPolicy.FOLLOW_UP_DELAY_MS
    }
}
