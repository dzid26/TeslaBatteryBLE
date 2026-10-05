// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * CSV codec for the append-only battery history:
 * `vehicleId,timestampMillis,percent,chargeLimit,chargingState,socPercent,rangeMiles,`
 * `batteryLevel,usableBatteryLevel,ratedRangeMiles,estRangeMiles,idealRangeMiles,`
 * `chargeEnergyAdded,chargeMilesAddedRated,chargeMilesAddedIdeal`.
 *
 * Pre-1.0 hygiene: rows from older formats are dropped on load, not migrated.
 */
object BatteryHistoryCsv {
    private const val COLUMNS = 15

    fun encode(sample: BatterySample): String =
        listOf(
            sample.vehicleId,
            sample.timestampMillis.toString(),
            sample.percent.toString(),
            sample.chargeLimit?.toString() ?: "",
            sample.chargingState?.replace(',', ' ') ?: "",
            sample.socPercent?.toString() ?: "",
            sample.rangeMiles?.toString() ?: "",
            sample.batteryLevel?.toString() ?: "",
            sample.usableBatteryLevel?.toString() ?: "",
            sample.ratedRangeMiles?.toString() ?: "",
            sample.estRangeMiles?.toString() ?: "",
            sample.idealRangeMiles?.toString() ?: "",
            sample.chargeEnergyAdded?.toString() ?: "",
            sample.chargeMilesAddedRated?.toString() ?: "",
            sample.chargeMilesAddedIdeal?.toString() ?: "",
        ).joinToString(",")

    /** Parses one data line; returns null for the header, a malformed row, or an old-format row. */
    fun parse(line: String): BatterySample? {
        val parts = line.split(',')
        if (parts.size < COLUMNS) return null
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
            batteryLevel = parts[7].toIntOrNull(),
            usableBatteryLevel = parts[8].toIntOrNull(),
            ratedRangeMiles = parts[9].toFloatOrNull(),
            estRangeMiles = parts[10].toFloatOrNull(),
            idealRangeMiles = parts[11].toFloatOrNull(),
            chargeEnergyAdded = parts[12].toFloatOrNull(),
            chargeMilesAddedRated = parts[13].toFloatOrNull(),
            chargeMilesAddedIdeal = parts[14].toFloatOrNull(),
        )
    }
}
