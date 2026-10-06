// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.health

import com.dzid26.teslable.core.history.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSummaryTest {
    private fun sample(
        minutes: Long,
        level: Int,
        state: String? = "Disconnected",
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
                sample(10, 60, "Charging", energyAddedKwh = 7.5f),
                sample(20, 60),
                sample(30, 70, "Charging", energyAddedKwh = 7.5f),
                sample(40, 70),
            )
        assertEquals(HealthConfidence.LEARNING, healthSummary(twoSessions).confidence)
        assertEquals(2, healthSummary(twoSessions).sessions)
        assertEquals(3, healthSummary(twoSessions).learningTarget)

        val threeSessions =
            twoSessions + listOf(sample(50, 80, "Charging", energyAddedKwh = 7.5f), sample(60, 80))
        assertEquals(HealthConfidence.LOW, healthSummary(threeSessions).confidence)
    }

    @Test
    fun `capacity comes from the last charge session`() {
        val samples =
            listOf(
                sample(0, 20),
                sample(10, 20, "Charging", energyAddedKwh = 0f),
                sample(20, 40, "Charging", energyAddedKwh = 13.125f, milesAddedRated = 60f),
                sample(30, 60, "Charging", energyAddedKwh = 26.25f, milesAddedRated = 120f),
                sample(40, 60),
            )
        val summary = healthSummary(samples)
        assertNotNull(summary.capacityKwh)
        assertEquals(65.625f, summary.capacityKwh!!, 0.01f)
        assertEquals(40f, summary.capacitySwingPercent!!, 0.01f)
        // The backbone constant: 26.25 kWh over 120 rated miles.
        assertEquals(0.21875f, summary.ratedKwhPerMile!!, 0.0001f)
        // Session scale: 120 miles over a 40% swing -> 300 miles full range.
        assertEquals(300f, summary.fullRangeMiles!!, 0.01f)
    }

    @Test
    fun `the session scale wins over a single rated reading`() {
        val samples =
            listOf(
                sample(0, 20),
                sample(10, 20, "Charging", energyAddedKwh = 0f),
                sample(20, 60, "Charging", energyAddedKwh = 26.25f, milesAddedRated = 120f),
                sample(30, 60, ratedRangeMiles = 174f),
            )
        // The single reading extrapolates to 290 mi; the session's scale wins.
        assertEquals(300f, healthSummary(samples).fullRangeMiles!!, 0.01f)
    }

    @Test
    fun `full range extrapolates the rated range`() {
        val summary = healthSummary(listOf(sample(0, 80, ratedRangeMiles = 216f)))
        assertEquals(270f, summary.fullRangeMiles!!, 0.01f)
        assertEquals(80f, summary.rangeSocPercent!!, 0.01f)
    }

    @Test
    fun `soh needs the factory baseline`() {
        val samples =
            listOf(
                sample(0, 20),
                sample(10, 20, "Charging", energyAddedKwh = 0f),
                sample(20, 60, "Charging", energyAddedKwh = 26.25f, milesAddedRated = 120f),
                sample(30, 60),
                sample(40, 80, ratedRangeMiles = 216f),
            )
        assertNull(healthSummary(samples).sohPercent)

        val withBaselines = healthSummary(samples, epaRatedRangeMiles = 300f, newCapacityKwh = 75f)
        assertNotNull(withBaselines.sohPercent)
        // Rated range: 216/0.8 = 270 -> 90%; energy delta: 65.625/75 = 87.5%.
        assertEquals(88.75f, withBaselines.sohPercent!!, 0.01f)
        assertEquals(2.5f, withBaselines.sohSpreadPoints!!, 0.01f)
        assertTrue(!withBaselines.sohMismatch)
    }

    @Test
    fun `thin inputs are flagged`() {
        val smallSwing =
            healthSummary(listOf(sample(0, 50, "Charging", energyAddedKwh = 1f), sample(5, 55, "Charging", energyAddedKwh = 3f)))
        assertNotNull(smallSwing.qualityNote)

        val lowSoc = healthSummary(listOf(sample(0, 20, ratedRangeMiles = 60f)))
        assertNotNull(lowSoc.qualityNote)
    }
}
