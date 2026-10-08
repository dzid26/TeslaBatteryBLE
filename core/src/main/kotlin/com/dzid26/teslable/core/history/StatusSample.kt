// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * One VCSEC status reading, derived from a logged [BleRecord] (ADR-0008).
 * Unlike [BatterySample], the time is the phone's clock at receipt: VCSEC
 * status replies carry no time of their own.
 */
data class StatusSample(
    /** Phone clock when the reply arrived (`acquired_at`). */
    val timestampMillis: Long,
    /** Which vehicle the reading came from: the advertised BLE name, as in [BatterySample.vehicleId]. */
    val vehicleId: String,
    val asleep: Boolean,
    /** Someone is in the car (`userPresence`), so it may be driving. */
    val userPresent: Boolean,
    val locked: Boolean,
)
