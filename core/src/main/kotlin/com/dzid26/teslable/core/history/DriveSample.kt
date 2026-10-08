// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * One DriveState reading, derived from a logged [BleRecord] (ADR-0008). Like
 * [BatterySample] it sits on the car's own clock: the time is
 * `DriveState.timestamp`, never the phone's `acquired_at`.
 *
 * It keeps only what drive detection needs; the full DriveState, navigation
 * destination and route included, stays in the log. The odometer lets the
 * parked-drain projection spot drives it saw no readings of: a rise between
 * two samples means the car moved in the gap.
 */
data class DriveSample(
    /** The car's own `DriveState.timestamp`. */
    val timestampMillis: Long,
    /** Which vehicle the reading came from: the advertised BLE name, as in [BatterySample.vehicleId]. */
    val vehicleId: String,
    /** `shift_state` as `P`, `R`, `N`, `D`, `Invalid` or `SNA`; null when the car sent none. */
    val shiftState: String?,
    /** `speed`, as the car reports it; null when absent. */
    val speed: Int?,
    /** `power`: coarse drive power in kW, whose sign separates driving from regenerating; null when absent. */
    val power: Int?,
    /** `odometer_in_hundredths_of_a_mile`: the odometer in 0.01 mi steps; null when absent. */
    val odometerInHundredthsOfAMile: Int?,
)
