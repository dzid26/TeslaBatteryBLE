// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * Picks the VCSEC status readings that reach each vehicle's status log
 * (ADR-0008). [shouldLogStatus] decides; the gate keeps the newest logged
 * record per vehicle for it to compare against, and holds back the newest
 * reading it skipped. When the link to the car drops, [linkLost] hands that
 * reading over, so each observed stretch in the log ends at the last reading
 * taken before the phone lost the car instead of up to a heartbeat earlier.
 *
 * Not thread-safe: the store calls it under its own lock.
 */
class StatusLogGate {
    private val lastLogged = mutableMapOf<String, BleRecord>()
    private val heldBack = mutableMapOf<String, BleRecord>()

    /** Seeds the newest logged status record for [vehicleId], read back from its log. */
    fun seed(
        vehicleId: String,
        logged: BleRecord,
    ) {
        lastLogged[vehicleId] = logged
    }

    /**
     * Returns [reading] when it should be appended to the log now, or null when
     * the policy skips it; a skipped reading is held back until the next one
     * or [linkLost]. [reading] carries the raw status and the phone's
     * `acquired_at`; a record without either is never logged.
     */
    fun admit(
        vehicleId: String,
        reading: BleRecord,
        firstAfterConnect: Boolean,
    ): BleRecord? {
        val status = reading.vehicle_status ?: return null
        val acquiredAtMillis = reading.acquired_at?.toEpochMilli() ?: return null
        if (!shouldLogStatus(lastLogged[vehicleId], status, acquiredAtMillis, firstAfterConnect)) {
            heldBack[vehicleId] = reading
            return null
        }
        heldBack.remove(vehicleId)
        lastLogged[vehicleId] = reading
        return reading
    }

    /**
     * The newest reading held back for [vehicleId], to append when the link to
     * the car drops; null when the last reading was already logged.
     */
    fun linkLost(vehicleId: String): BleRecord? = heldBack.remove(vehicleId)?.also { lastLogged[vehicleId] = it }
}
