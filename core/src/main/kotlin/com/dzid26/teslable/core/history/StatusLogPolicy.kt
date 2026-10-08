// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.tesla.generated.vcsec.VehicleStatus

/**
 * Whether a VCSEC status reading goes into the vehicle's status log
 * (ADR-0008). A reading is logged when it is the first for the vehicle
 * ([last] is null), the first since the link (re)connected, or when the raw
 * status differs from [last]. An unchanged status is logged again once 15
 * minutes have passed since [last]: that heartbeat tells a sleeping car with
 * a phone connected apart from a phone out of range, because a connected
 * phone leaves a record at least every quarter hour. A reading timed before
 * [last] (the phone's clock moved back) is logged too, since the time that
 * passed is unknown.
 *
 * @param last the newest record logged for this vehicle, or null when there is none.
 * @param acquiredAtMillis the phone's clock when [status] arrived.
 * @param firstAfterConnect true for the first reading since the link became ready.
 */
fun shouldLogStatus(
    last: BleRecord?,
    status: VehicleStatus,
    acquiredAtMillis: Long,
    firstAfterConnect: Boolean,
): Boolean {
    if (last == null || firstAfterConnect || last.vehicle_status != status) return true
    val lastAt = last.acquired_at?.toEpochMilli() ?: return true
    val elapsed = acquiredAtMillis - lastAt
    return elapsed < 0 || elapsed >= STATUS_HEARTBEAT_MILLIS
}

/** An unchanged status is logged again after this long. */
private const val STATUS_HEARTBEAT_MILLIS = 15 * 60_000L
