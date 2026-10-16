// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.health

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.protocol.ChargingStateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSummaryTest {
    private fun sample(
        minutes: Long,
        level: Int,
        state: ChargingStateKind? = ChargingStateKind.Disconnected,
        limit: Int? = 85,
        ratedRangeMiles: Float? = null,
        energyAddedKwh: Float? = null,
        milesAddedRated: Float? = null,
    ) = BatterySample(
        timestampMillis = minutes * 60_000L,
        batteryLevel = level,
        chargingState = state,
        chargeLimit = limit,
        vehicleId = "Sdemo",
        ratedRangeMiles = ratedRangeMiles,
        chargeEnergyAdded = energyAddedKwh,
        chargeMilesAddedRated = milesAddedRated,
    )

    @Test
    fun `learning gate opens after three sessions`() {
        val twoSessions =
            listOf(
                sample(0, 50),
                sample(10, 60, ChargingStateKind.Charging, energyAddedKwh = 7.5f),
                sample(20, 60),
                sample(30, 70, ChargingStateKind.Charging, energyAddedKwh = 7.5f),
                sample(40, 70),
            )
        assertEquals(HealthConfidence.LEARNING, healthSummary(twoSessions).confidence)
        assertEquals(2, healthSummary(twoSessions).sessions)
        assertEquals(3, healthSummary(twoSessions).learningTarget)

        val threeSessions =
            twoSessions + listOf(sample(50, 80, ChargingStateKind.Charging, energyAddedKwh = 7.5f), sample(60, 80))
        assertEquals(HealthConfidence.LOW, healthSummary(threeSessions).confidence)
    }

    /** A parked climb through 40-70% on a 300-mile scale (miles = 3 x SOC), read every 0.25%. */
    private fun ladder(startMinute: Long = 1000): List<BatterySample> =
        (0..120).map { step ->
            val soc = 40.1f + step * 0.25f
            sample(
                startMinute + step,
                Math.round(soc),
                ratedRangeMiles = 3f * soc,
            )
        }

    @Test
    fun `capacity comes from the last charge session`() {
        val samples =
            ladder() +
                listOf(
                    sample(2000, 20),
                    sample(2010, 20, ChargingStateKind.Charging, energyAddedKwh = 0f),
                    sample(2020, 40, ChargingStateKind.Charging, energyAddedKwh = 13.125f, milesAddedRated = 60f),
                    sample(2030, 60, ChargingStateKind.Charging, energyAddedKwh = 26.25f, milesAddedRated = 120f),
                    sample(2040, 60),
                )
        val summary = healthSummary(samples)
        assertNotNull(summary.capacityKwh)
        assertEquals(40f, summary.capacitySwingPercent!!, 0.01f)
        // The backbone constant: 26.25 kWh over 120 rated miles.
        assertEquals(0.21875f, summary.ratedKwhPerMile!!, 0.0001f)
        // The fitted scale, not the session's swing, gives the full range.
        assertEquals(300f, summary.fullRangeMiles!!, 2f)
        assertEquals(0.21875f * summary.fullRangeMiles!!, summary.capacityKwh!!, 0.01f)
    }

    @Test
    fun `full range comes from the fitted level steps`() {
        val summary = healthSummary(ladder())
        assertEquals(300f, summary.fullRangeMiles!!, 2f)
        assertTrue(summary.fullRangeSteps > 20)
        // A single reading is not a scale.
        assertNull(healthSummary(listOf(sample(0, 80, ratedRangeMiles = 216f))).fullRangeMiles)
    }

    @Test
    fun `soh needs the factory baseline`() {
        val samples =
            ladder() +
                listOf(
                    sample(2000, 20),
                    sample(2010, 20, ChargingStateKind.Charging, energyAddedKwh = 0f),
                    sample(2020, 60, ChargingStateKind.Charging, energyAddedKwh = 26.25f, milesAddedRated = 120f),
                    sample(2030, 60),
                )
        assertNull(healthSummary(samples).sohPercent)

        val withBaselines = healthSummary(samples, epaRatedRangeMiles = 330f, newCapacityKwh = 75f)
        assertNotNull(withBaselines.sohPercent)
        // Fitted range: ~300/330 = ~90.9%; energy delta: 65.625/75 = 87.5%.
        assertEquals(89.2f, withBaselines.sohPercent!!, 1.2f)
        assertTrue(!withBaselines.sohMismatch)
    }

    @Test
    fun `thin inputs are flagged`() {
        val smallSwing =
            healthSummary(
                listOf(
                    sample(0, 50, ChargingStateKind.Charging, energyAddedKwh = 1f),
                    sample(5, 55, ChargingStateKind.Charging, energyAddedKwh = 3f),
                ),
            )
        assertNotNull(smallSwing.qualityNote)
    }
}
