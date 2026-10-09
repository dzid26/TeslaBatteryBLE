// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ChargingStateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DischargeProjectionTest {
    private fun sample(
        minutes: Long,
        percent: Int,
        state: ChargingStateKind? = ChargingStateKind.Disconnected,
    ) = BatterySample(
        timestampMillis = minutes * MINUTE,
        batteryLevel = percent,
        chargingState = state,
        chargeLimit = null,
    )

    private fun drive(
        minutes: Long,
        odometer: Int?,
    ) = DriveSample(
        timestampMillis = minutes * MINUTE,
        vehicleId = "",
        shiftState = null,
        speed = null,
        power = null,
        odometerInHundredthsOfAMile = odometer,
    )

    /** A drive read right after each battery sample, all at one odometer: the car never moved. */
    private fun stationary(samples: List<BatterySample>) = samples.map { drive(it.timestampMillis / MINUTE, ODOMETER) }

    /** Projects a minute after the last sample, over a 6 h range unless told otherwise. */
    private fun project(
        samples: List<BatterySample>,
        drives: List<DriveSample> = stationary(samples),
        rangeMillis: Long = 6 * HOUR,
        nowMillis: Long = samples.last().timestampMillis + MINUTE,
    ) = dischargeProjection(samples, drives, rangeMillis, nowMillis)

    @Test
    fun `carries the parked rate half the range forward`() {
        // 10 points over two hours -> 15 points lower over the three-hour horizon.
        val projection = assertNotNull(project(listOf(sample(0, 80), sample(60, 75), sample(120, 70))))
        assertEquals(55, projection.projectedPercent)
        assertEquals(3 * HOUR, projection.horizonMillis)
        assertEquals(120 * MINUTE + 3 * HOUR, projection.projectedAtMillis)
    }

    @Test
    fun `the horizon is half the selected range`() {
        val samples = listOf(sample(0, 80), sample(60, 75), sample(120, 70))
        assertEquals(12 * HOUR, assertNotNull(project(samples, rangeMillis = 24 * HOUR)).horizonMillis)
        assertEquals(84 * HOUR, assertNotNull(project(samples, rangeMillis = 7 * 24 * HOUR)).horizonMillis)
    }

    @Test
    fun `only the selected range feeds the rate`() {
        // The 95% reading is older than the 6 h range: 2 points over the last two hours remain.
        val samples = listOf(sample(0, 95), sample(400, 80), sample(460, 79), sample(520, 78))
        assertEquals(75, assertNotNull(project(samples)).projectedPercent)
    }

    @Test
    fun `a long overnight gap with an unchanged odometer counts`() {
        // Parked at 80% in the evening, no reads while asleep, 75% at the wake-up
        // read ten hours later; the drive read comes a minute after it.
        val samples = listOf(sample(0, 80), sample(600, 75))
        val drives = listOf(drive(1, ODOMETER), drive(601, ODOMETER))
        // 5 points over 10 h, carried over the 12 h horizon of a day range.
        assertEquals(69, assertNotNull(project(samples, drives, rangeMillis = 24 * HOUR)).projectedPercent)
    }

    @Test
    fun `a gap with an odometer change is a drive and does not count`() {
        // Parked 80 -> 79, an unseen drive to 70 (5 miles on the odometer), parked 70 -> 69.
        val samples = listOf(sample(0, 80), sample(60, 79), sample(300, 70), sample(360, 69))
        val drives = listOf(drive(0, ODOMETER), drive(60, ODOMETER), drive(300, ODOMETER + 500), drive(360, ODOMETER + 500))
        // Two parked hours lost 2 points: 1 %/h, 3 points over the horizon.
        assertEquals(66, assertNotNull(project(samples, drives)).projectedPercent)
    }

    @Test
    fun `a charging pair does not count`() {
        val samples =
            listOf(
                sample(0, 60),
                sample(60, 59),
                sample(90, 70, ChargingStateKind.Charging),
                sample(120, 80, ChargingStateKind.Charging),
                sample(150, 80, ChargingStateKind.Complete),
                sample(210, 79),
            )
        // Two parked hours lost 2 points; the charge in between does not offset them.
        assertEquals(76, assertNotNull(project(samples)).projectedPercent)
    }

    @Test
    fun `a missing drive sample excludes the pair`() {
        // No drive read near the 120 min sample (the one at 125 is past the tolerance),
        // and the one at 180 has no odometer: only the first pair is known parked.
        val samples = listOf(sample(0, 80), sample(60, 79), sample(120, 70), sample(180, 69))
        val drives = listOf(drive(0, ODOMETER), drive(60, ODOMETER), drive(125, ODOMETER), drive(180, null))
        // One parked hour lost 1 point: 1 %/h, 3 points over the horizon.
        assertEquals(66, assertNotNull(project(samples, drives)).projectedPercent)
        // With no drive samples at all nothing is known parked.
        assertNull(project(samples, emptyList()))
    }

    @Test
    fun `a parked rounding blip cancels out`() {
        // 80 -> 79 -> 80 -> 79 -> 78: the blip back to 80 cancels its drop, leaving
        // 2 points over four hours, 0.5 %/h; summing only the drops would say 3.
        val samples = listOf(sample(0, 80), sample(60, 79), sample(90, 80), sample(120, 79), sample(240, 78))
        // Six points over the 12 h horizon of a day range.
        assertEquals(72, assertNotNull(project(samples, rangeMillis = 24 * HOUR)).projectedPercent)
    }

    @Test
    fun `a sleeping car keeps its projection until the horizon has passed`() {
        // No reads while asleep: the last sample is 8 h old, inside the 12 h horizon of a day range.
        val samples = listOf(sample(0, 80), sample(120, 79))
        assertNotNull(project(samples, rangeMillis = 24 * HOUR, nowMillis = (120 + 8 * 60) * MINUTE))
        // Past the horizon the whole line would lie in the past.
        assertNull(project(samples, rangeMillis = 24 * HOUR, nowMillis = (120 + 13 * 60) * MINUTE))
    }

    @Test
    fun `null while charging`() {
        assertNull(project(listOf(sample(0, 70, ChargingStateKind.Charging), sample(30, 80, ChargingStateKind.Charging))))
    }

    @Test
    fun `null when the last sample is older than the horizon`() {
        // Five hours old, past the 3 h horizon of a 6 h range.
        assertNull(project(listOf(sample(0, 80), sample(30, 70)), nowMillis = 330 * MINUTE))
    }

    @Test
    fun `null when the percent rose`() {
        assertNull(project(listOf(sample(0, 70), sample(30, 75))))
    }

    @Test
    fun `null with under ten minutes of parked time`() {
        assertNull(project(listOf(sample(0, 80), sample(5, 79))))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val ODOMETER = 1_234_500
    }
}
