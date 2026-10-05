// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * CSV codec for the append-only battery history:
 * `vehicleId,timestampMillis,percent,chargeLimit,chargingState,socPercent,rangeMiles`.
 *
 * Pre-1.0 hygiene: rows from the older five-column format are dropped on load,
 * not migrated.
 */
object BatteryHistoryCsv {
    fun encode(sample: BatterySample): String =
        listOf(
            sample.vehicleId,
            sample.timestampMillis.toString(),
            sample.percent.toString(),
            sample.chargeLimit?.toString() ?: "",
            sample.chargingState?.replace(',', ' ') ?: "",
            sample.socPercent?.toString() ?: "",
            sample.rangeMiles?.toString() ?: "",
        ).joinToString(",")

    /** Parses one data line; returns null for the header, a malformed row, or an old-format row. */
    fun parse(line: String): BatterySample? {
        val parts = line.split(',')
        if (parts.size < 7) return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        val percent = parts[2].toIntOrNull() ?: return null
        return BatterySample(
            timestampMillis = timestamp,
            percent = percent,
            chargingState = parts[4].takeIf { it.isNotEmpty() },
            chargeLimit = parts[3].toIntOrNull(),
            vehicleId = parts[0],
            socPercent = parts[5].toFloatOrNull(),
            rangeMiles = parts[6].toFloatOrNull(),
        )
    }
}
