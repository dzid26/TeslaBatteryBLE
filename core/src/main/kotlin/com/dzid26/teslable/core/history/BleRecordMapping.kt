// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.TeslaVcsec

// One-way conversion: BleRecords kept in the history logs become the app's
// read models (BatterySample, StatusSample). The read models are derived on
// load; nothing ever writes a record back from parsed fields — the store logs
// the car's raw reply verbatim, next to the phone's acquisition time
// (ADR-0008).

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
    val raw = vehicle_status ?: return null
    val time = acquired_at?.toEpochMilli() ?: return null
    val parsed = TeslaVcsec.statusOf(raw)
    return StatusSample(
        timestampMillis = time,
        vehicleId = vehicleId,
        asleep = parsed.asleep,
        userPresent = parsed.userPresent,
        locked = parsed.locked,
    )
}
