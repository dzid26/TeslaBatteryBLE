// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.asleep
import com.dzid26.teslable.core.protocol.locked
import com.dzid26.teslable.core.protocol.shiftStateKind
import com.dzid26.teslable.core.protocol.userPresent

// One-way conversion: BleRecords kept in the history logs become the app's
// read models (BatterySample, StatusSample, DriveSample). The read models are
// derived on load; nothing ever writes a record back from parsed fields — the
// store logs the car's raw reply verbatim, next to the phone's acquisition
// time (ADR-0008). A value that needs a rule (a gear, a charging state, the status flags) comes
// from the shared properties in protocol/StateViews.kt, never re-derived here.

/**
 * The battery sample for a logged charge reply, or null unless the record
 * holds a charge state with a level and the car's own timestamp. The sample
 * sits on that car timestamp: `acquired_at` never stands in for it (ADR-0006).
 */
fun BleRecord.toBatterySample(vehicleId: String): BatterySample? = charge_state?.toBatterySample(vehicleId)

/**
 * The status sample for a logged VCSEC reply, or null unless the record holds
 * a status and `acquired_at`. VCSEC replies carry no time of their own, so the
 * phone's `acquired_at` is the sample's time.
 */
fun BleRecord.toStatusSample(vehicleId: String): StatusSample? {
    val status = vehicle_status ?: return null
    val time = acquired_at?.toEpochMilli() ?: return null
    return StatusSample(
        timestampMillis = time,
        vehicleId = vehicleId,
        asleep = status.asleep,
        userPresent = status.userPresent,
        locked = status.locked,
    )
}

/**
 * The drive sample for a logged DriveState reply, or null unless the record
 * holds a drive state with the car's own timestamp. The sample sits on that
 * car timestamp: `acquired_at` never stands in for it (ADR-0006, ADR-0008).
 */
fun BleRecord.toDriveSample(vehicleId: String): DriveSample? {
    val drive = drive_state ?: return null
    val time = drive.timestamp?.toEpochMilli() ?: return null
    return DriveSample(
        timestampMillis = time,
        vehicleId = vehicleId,
        shiftState = drive.shiftStateKind,
        speed = drive.speed,
        power = drive.power,
        odometerInHundredthsOfAMile = drive.odometer_in_hundredths_of_a_mile,
    )
}
