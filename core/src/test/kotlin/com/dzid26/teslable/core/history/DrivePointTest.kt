// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ChargingStateKind
import com.dzid26.teslable.core.protocol.ShiftStateKind
import com.dzid26.teslable.core.units.DistanceUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class DrivePointTest {
    private fun sample(
        minutes: Long,
        level: Int,
        rated: Float? = null,
        state: ChargingStateKind = ChargingStateKind.Disconnected,
    ) = BatterySample(
        timestampMillis = minutes * MINUTE,
        batteryLevel = level,
        chargingState = state,
        chargeLimit = null,
        ratedRangeMiles = rated,
    )

    private fun drive(
        minutes: Long,
        odometer: Int?,
        shift: ShiftStateKind? = ShiftStateKind.D,
        power: Int? = 20,
    ) = DriveSample(
        timestampMillis = minutes * MINUTE + 1_000,
        vehicleId = "",
        shiftState = shift,
        speed = null,
        power = power,
        odometerInHundredthsOfAMile = odometer,
    )

    @Test
    fun `places a drive on miles driven from its first point`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 78), sample(20, 75)),
                listOf(drive(0, 100_000, ShiftStateKind.P), drive(10, 100_850), drive(20, 101_500)),
            )
        assertEquals(listOf(0.0, 8.5, 15.0), points.map { it.milesDriven })
        assertEquals(listOf(80f, 78f, 75f), points.map { it.socPercent })
    }

    @Test
    fun `divides the rated range by the full range for the percent`() {
        val points =
            drivePoints(
                listOf(sample(0, 80, rated = 200f), sample(10, 78, rated = 195f)),
                listOf(drive(0, 100_000), drive(10, 100_500)),
                scale = pinnedScale,
            )
        assertEquals(listOf(80f, 78f), points.map { it.socPercent })
    }

    @Test
    fun `falls back to the displayed level without a full range or a rated range`() {
        val points =
            drivePoints(
                listOf(sample(0, 80, rated = 200f), sample(10, 78)),
                listOf(drive(0, 100_000), drive(10, 100_500)),
                scale = null,
            )
        assertEquals(listOf(80f, 78f), points.map { it.socPercent })
        val noRated =
            drivePoints(
                listOf(sample(0, 80), sample(10, 78)),
                listOf(drive(0, 100_000), drive(10, 100_500)),
                scale = pinnedScale,
            )
        assertEquals(listOf(80f, 78f), noRated.map { it.socPercent })
    }

    @Test
    fun `keeps only the parked samples next to a drive`() {
        val points =
            drivePoints(
                listOf(sample(0, 85), sample(60, 84), sample(70, 80), sample(80, 79), sample(140, 78)),
                listOf(
                    drive(0, 100_000, ShiftStateKind.P),
                    drive(60, 100_000, ShiftStateKind.P),
                    drive(70, 101_000),
                    drive(80, 102_000, ShiftStateKind.P),
                    drive(140, 102_000, ShiftStateKind.P),
                ),
            )
        assertEquals(listOf(60L, 70L, 80L), points.map { it.timestampMillis / MINUTE })
    }

    @Test
    fun `marks negative power in gear as regenerating`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 79)),
                listOf(drive(0, 100_000, power = 30), drive(10, 100_500, power = -15)),
            )
        assertEquals(listOf(false, true), points.map { it.regenerating })
    }

    @Test
    fun `leaves out samples with no odometer nearby`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 79), sample(20, 78)),
                listOf(drive(0, 100_000), drive(10, null), drive(20, 101_000)),
            )
        assertEquals(listOf(0L, 20L), points.map { it.timestampMillis / MINUTE })
    }

    @Test
    fun `keeps every reading of a stationary run`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(1, 80), sample(2, 79), sample(10, 78)),
                listOf(drive(0, 100_000), drive(1, 100_000), drive(2, 100_000), drive(10, 100_500)),
            )
        assertEquals(listOf(0L, 1L, 2L, 10L), points.map { it.timestampMillis / MINUTE })
    }

    @Test
    fun `keeps the regen flag per reading when readings share an odometer value`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(1, 80), sample(2, 79)),
                listOf(drive(0, 100_000, power = 10), drive(1, 100_000, power = -5), drive(2, 100_000, power = 8)),
            )
        assertEquals(listOf(false, true, false), points.map { it.regenerating })
    }

    @Test
    fun `stops at the last charge before the newest drive reading when asked`() {
        val points =
            drivePoints(
                listOf(sample(0, 40), sample(10, 39), sample(20, 55, state = ChargingStateKind.Charging), sample(30, 80), sample(40, 79)),
                listOf(drive(0, 100_000), drive(10, 100_500), drive(20, 100_500), drive(30, 100_500), drive(40, 101_000)),
                sinceLastCharge = true,
            )
        assertEquals(listOf(30L, 40L), points.map { it.timestampMillis / MINUTE })
        assertEquals(listOf(0.0, 5.0), points.map { it.milesDriven })
    }

    @Test
    fun `a charge in progress does not hide the drive before it`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 78), sample(20, 79, state = ChargingStateKind.Charging)),
                listOf(drive(0, 100_000), drive(10, 100_500), drive(20, 100_500)),
                sinceLastCharge = true,
            )
        assertEquals(listOf(0L, 10L), points.map { it.timestampMillis / MINUTE })
    }

    @Test
    fun `starts a new trip across a charge so the line does not jump over it`() {
        val points =
            drivePoints(
                listOf(sample(0, 40), sample(10, 39), sample(20, 55, state = ChargingStateKind.Charging), sample(30, 80), sample(40, 79)),
                listOf(drive(0, 100_000), drive(10, 100_500), drive(20, 100_500), drive(30, 100_500), drive(40, 101_000)),
            )
        assertEquals(listOf(0, 0, 1, 1), points.map { it.trip })
    }

    @Test
    fun `starts a new trip after a long gap`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 78), sample(600, 70), sample(610, 69)),
                listOf(drive(0, 100_000), drive(10, 100_500), drive(600, 100_500), drive(610, 101_000)),
            )
        assertEquals(listOf(0, 0, 1, 1), points.map { it.trip })
    }

    @Test
    fun `sums the drop per trip so a charge between trips does not count`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 78), sample(20, 90, state = ChargingStateKind.Charging), sample(30, 88), sample(40, 85)),
                listOf(drive(0, 100_000), drive(10, 100_500), drive(20, 100_500), drive(30, 100_500), drive(40, 101_000)),
            )
        // 80 -> 78 is 2, then 88 -> 85 is 3; first minus last overall (80 - 85) would read -5.
        assertEquals(5f, points.totalDrop { it.socPercent })
    }

    @Test
    fun `keeps only the last miles driven when windowed`() {
        val points =
            drivePoints(
                listOf(sample(0, 80), sample(10, 79), sample(20, 78), sample(30, 77)),
                listOf(drive(0, 100_000), drive(10, 100_500), drive(20, 101_000), drive(30, 101_500)),
                windowMiles = 10.0,
            )
        // 15 mi driven in all; the last 10 start at the 100_500 reading, which becomes 0.
        assertEquals(listOf(10L, 20L, 30L), points.map { it.timestampMillis / MINUTE })
        assertEquals(listOf(0.0, 5.0, 10.0), points.map { it.milesDriven })
    }

    @Test
    fun `a window in kilometres is the round number in kilometres`() {
        assertEquals(9.32, DriveWindow.SHORT.miles(DistanceUnit.KILOMETERS)!!, 0.01)
        assertEquals(10.0, DriveWindow.SHORT.miles(DistanceUnit.MILES)!!, 0.0)
        assertEquals(150, DriveWindow.LONG.label(DistanceUnit.KILOMETERS))
        assertEquals(null, DriveWindow.SINCE_CHARGE.miles(DistanceUnit.KILOMETERS))
    }

    private companion object {
        const val MINUTE = 60_000L
        val pinnedScale =
            FullRangeScale(fullRangeMiles = 250f, offsetMiles = 0f, standardErrorMiles = 0.1f, samples = 10, pinned = true)
    }
}
