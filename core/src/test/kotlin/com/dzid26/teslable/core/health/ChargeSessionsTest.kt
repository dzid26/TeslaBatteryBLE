// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargeSessionsTest {
    private fun sample(
        minutes: Long,
        level: Int,
        state: ChargingStateKind? = ChargingStateKind.Charging,
        energyAddedKwh: Float? = null,
        milesAddedRated: Float? = null,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = level,
        chargingState = state,
        chargeLimit = null,
        chargeEnergyAdded = energyAddedKwh,
        chargeMilesAddedRated = milesAddedRated,
    )

    @Test
    fun `a session first seen mid-charge counts only what was added since the first sample`() {
        // Plugged in at 20%, but the app first saw the car at 50% with 22.5 kWh already added.
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Disconnected),
                sample(300, 50, energyAddedKwh = 22.5f, milesAddedRated = 100f),
                sample(330, 65, energyAddedKwh = 33.75f, milesAddedRated = 150f),
                sample(360, 80, energyAddedKwh = 45f, milesAddedRated = 200f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(50f, session.startPercent)
        assertEquals(80f, session.endPercent)
        assertEquals(22.5f, session.energyAddedKwh)
        assertEquals(100f, session.milesAddedRated)

        // 22.5 kWh over the 30% swing is 75 kWh; the running total over that swing would say 150.
        val estimate = EnergyDeltaEstimator.estimate(session.energyAddedKwh!!, session.startPercent, session.endPercent)!!
        assertEquals(75f, estimate.usableCapacityKwh, 0.001f)
        // The rated backbone agrees: 0.225 kWh per mile over a 333 mile full range.
        assertEquals(75f, session.kwhPerMile!! * session.fullRangeMiles!!, 0.01f)
    }

    @Test
    fun `a counter that went down was restarted, so the last value is what the session added`() {
        // Starting still shows the previous session's totals until the car restarts them.
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Starting, energyAddedKwh = 38.4f, milesAddedRated = 170f),
                sample(1, 20, energyAddedKwh = 0f, milesAddedRated = 0f),
                sample(60, 50, energyAddedKwh = 22.5f, milesAddedRated = 100f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(22.5f, session.energyAddedKwh)
        assertEquals(100f, session.milesAddedRated)
    }

    @Test
    fun `a restart that climbs back above the first value is still a restart`() {
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Starting, energyAddedKwh = 5f),
                sample(1, 20, energyAddedKwh = 0f),
                sample(60, 50, energyAddedKwh = 22.5f),
            )
        // Subtracting the stale 5 kWh would report 17.5.
        assertEquals(22.5f, chargeSessions(samples).single().energyAddedKwh)
    }

    @Test
    fun `a total missing from the first sample counts as zero there`() {
        val samples =
            listOf(
                sample(0, 20, energyAddedKwh = 0f),
                sample(20, 40, energyAddedKwh = 13.125f, milesAddedRated = 60f),
                sample(30, 60, energyAddedKwh = 26.25f, milesAddedRated = 120f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(26.25f, session.energyAddedKwh)
        assertEquals(120f, session.milesAddedRated)
    }

    @Test
    fun `a total that is unreported or did not grow stays null`() {
        // One sample cannot say what was added while it was being watched.
        val single = chargeSessions(listOf(sample(0, 50, energyAddedKwh = 7.5f, milesAddedRated = 34f))).single()
        assertNull(single.energyAddedKwh)
        assertNull(single.milesAddedRated)

        val unreportedAtEnd = chargeSessions(listOf(sample(0, 50, energyAddedKwh = 5f), sample(10, 55))).single()
        assertNull(unreportedAtEnd.energyAddedKwh)

        val flat = chargeSessions(listOf(sample(0, 50, energyAddedKwh = 5f), sample(10, 55, energyAddedKwh = 5f))).single()
        assertNull(flat.energyAddedKwh)
    }

    @Test
    fun `Starting then Charging is one session that begins at Starting`() {
        val samples =
            listOf(
                sample(0, 20, ChargingStateKind.Starting, energyAddedKwh = 0f),
                sample(1, 21, energyAddedKwh = 0.2f),
                sample(30, 35, energyAddedKwh = 11.25f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(20f, session.startPercent)
        assertEquals(35f, session.endPercent)
        assertEquals(11.25f, session.energyAddedKwh)
    }

    @Test
    fun `a short Stopped or NoPower blip does not split a session`() {
        for (pause in listOf(ChargingStateKind.Stopped, ChargingStateKind.NoPower)) {
            val samples =
                listOf(
                    sample(0, 40, energyAddedKwh = 10f),
                    sample(5, 40, pause, energyAddedKwh = 10f),
                    sample(9, 41, energyAddedKwh = 10.5f),
                    sample(60, 60, energyAddedKwh = 20f),
                )
            val sessions = chargeSessions(samples)
            assertEquals(1, sessions.size, pause.name)
            assertEquals(40f, sessions.single().startPercent, pause.name)
            assertEquals(60f, sessions.single().endPercent, pause.name)
            assertEquals(10f, sessions.single().energyAddedKwh, pause.name)
        }
    }

    @Test
    fun `a pause that resumes through Starting stays in the session`() {
        val samples =
            listOf(
                sample(0, 40, energyAddedKwh = 10f),
                sample(5, 40, ChargingStateKind.Stopped, energyAddedKwh = 10f),
                sample(7, 40, ChargingStateKind.Starting, energyAddedKwh = 10f),
                sample(8, 41, energyAddedKwh = 10.2f),
            )
        assertEquals(1, chargeSessions(samples).size)
    }

    @Test
    fun `a pause shorter than ten minutes is bridged and ten minutes is not`() {
        val nineMinutes = listOf(sample(0, 40), sample(1, 40, ChargingStateKind.Stopped), sample(9, 41))
        assertEquals(1, chargeSessions(nineMinutes).size)

        val tenMinutes = listOf(sample(0, 40), sample(1, 40, ChargingStateKind.Stopped), sample(10, 41))
        assertEquals(2, chargeSessions(tenMinutes).size)
    }

    @Test
    fun `a long Stopped gap makes two sessions that each carry their own deltas`() {
        val samples =
            listOf(
                sample(0, 30, energyAddedKwh = 0f),
                sample(40, 50, energyAddedKwh = 15f),
                sample(45, 50, ChargingStateKind.Stopped, energyAddedKwh = 15f),
                sample(120, 50, ChargingStateKind.Stopped, energyAddedKwh = 15f),
                sample(125, 50, ChargingStateKind.Starting, energyAddedKwh = 15f),
                sample(160, 70, energyAddedKwh = 30f),
            )
        val sessions = chargeSessions(samples)
        assertEquals(2, sessions.size)
        assertEquals(30f, sessions[0].startPercent)
        assertEquals(50f, sessions[0].endPercent)
        assertEquals(15f, sessions[0].energyAddedKwh)
        assertEquals(50f, sessions[1].startPercent)
        assertEquals(70f, sessions[1].endPercent)
        assertEquals(15f, sessions[1].energyAddedKwh)
    }

    @Test
    fun `a pause is counted from the last charging sample, not from the session start`() {
        val samples =
            listOf(
                sample(0, 40),
                sample(8, 41),
                sample(9, 41, ChargingStateKind.Stopped),
                sample(15, 42),
                sample(16, 42, ChargingStateKind.NoPower),
                sample(30, 43),
            )
        // The first pause lasts 7 minutes and the second 15, whatever the session's age.
        assertEquals(2, chargeSessions(samples).size)
    }

    @Test
    fun `Complete ends a session even when charging resumes minutes later`() {
        val samples =
            listOf(
                sample(0, 60, energyAddedKwh = 0f),
                sample(30, 84, energyAddedKwh = 18f),
                sample(32, 85, ChargingStateKind.Complete, energyAddedKwh = 18.75f),
                // The limit was raised and the car started again.
                sample(35, 85, ChargingStateKind.Starting, energyAddedKwh = 18.75f),
                sample(60, 95, energyAddedKwh = 26.25f),
            )
        val sessions = chargeSessions(samples)
        assertEquals(2, sessions.size)
        // The Complete sample belongs to neither session.
        assertEquals(84f, sessions[0].endPercent)
        assertEquals(18f, sessions[0].energyAddedKwh)
        assertEquals(85f, sessions[1].startPercent)
        assertEquals(7.5f, sessions[1].energyAddedKwh)
    }

    @Test
    fun `Disconnected ends a session`() {
        val samples =
            listOf(
                sample(0, 40, energyAddedKwh = 5f),
                sample(20, 50, energyAddedKwh = 12.5f),
                sample(25, 50, ChargingStateKind.Disconnected),
                sample(30, 50, ChargingStateKind.Starting, energyAddedKwh = 0f),
                sample(60, 60, energyAddedKwh = 7.5f),
            )
        val sessions = chargeSessions(samples)
        assertEquals(2, sessions.size)
        assertEquals(7.5f, sessions[0].energyAddedKwh)
        assertEquals(7.5f, sessions[1].energyAddedKwh)
    }

    @Test
    fun `a missing or unrecognised state ends a session`() {
        for (state in listOf(null, ChargingStateKind.Unknown, ChargingStateKind.Calibrating)) {
            val samples = listOf(sample(0, 40), sample(5, 41, state), sample(6, 42))
            assertEquals(2, chargeSessions(samples).size, state.toString())
        }
    }

    @Test
    fun `a dropped link does not end a session`() {
        val samples =
            listOf(
                sample(0, 40, energyAddedKwh = 10f),
                // The app was out of range for three hours; the car's totals kept running.
                sample(180, 80, energyAddedKwh = 40f),
            )
        val session = chargeSessions(samples).single()
        assertEquals(30f, session.energyAddedKwh)
        assertEquals(75f, EnergyDeltaEstimator.estimate(30f, session.startPercent, session.endPercent)!!.usableCapacityKwh, 0.001f)
    }

    @Test
    fun `history without charging has no sessions`() {
        assertTrue(chargeSessions(emptyList()).isEmpty())
        val idle =
            listOf(
                sample(0, 80, ChargingStateKind.Disconnected),
                sample(5, 80, ChargingStateKind.Stopped),
                sample(10, 79, ChargingStateKind.NoPower),
            )
        assertTrue(chargeSessions(idle).isEmpty())
    }

    @Test
    fun `rated constants need miles and a usable swing`() {
        val session = ChargeSession(startPercent = 20f, endPercent = 60f, energyAddedKwh = 26.25f, milesAddedRated = 120f)
        assertEquals(0.21875f, session.kwhPerMile!!, 0.0001f)
        assertEquals(300f, session.fullRangeMiles!!, 0.01f)

        val noMiles = session.copy(milesAddedRated = null)
        assertNull(noMiles.kwhPerMile)
        assertNull(noMiles.fullRangeMiles)

        // Under the 5% minimum swing the scale is unusable, though the constant stands.
        val thin = session.copy(endPercent = 24f)
        assertNull(thin.fullRangeMiles)
        assertEquals(0.21875f, thin.kwhPerMile!!, 0.0001f)
    }
}
