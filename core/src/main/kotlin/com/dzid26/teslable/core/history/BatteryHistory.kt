// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * One battery reading, recorded whenever a car reports charge state.
 *
 * Storage holds raw car fields only; display values are derived at runtime.
 */
data class BatterySample(
    val timestampMillis: Long,
    /** Displayed SOC (`battery_level`). */
    val batteryLevel: Int,
    val chargingState: String?,
    val chargeLimit: Int?,
    /**
     * Which vehicle the reading came from: the advertised BLE name
     * (`S<sha1(VIN)[:8]>C`), which is stable before a VIN is known. Empty on
     * rows written before per-vehicle history existed.
     */
    val vehicleId: String = "",
    /** Usable SOC (`usable_battery_level`); can sit below [batteryLevel]. */
    val usableBatteryLevel: Int? = null,
    /** `battery_range`: rated range in miles at this SOC. */
    val ratedRangeMiles: Float? = null,
    /** `est_battery_range`: estimated range in miles at this SOC. */
    val estRangeMiles: Float? = null,
    /** `ideal_battery_range`: ideal range in miles at this SOC. */
    val idealRangeMiles: Float? = null,
    /** `charge_energy_added`: kWh added so far this session; 0 when idle. */
    val chargeEnergyAdded: Float? = null,
    /** `charge_miles_added_rated`: rated miles added so far this session. */
    val chargeMilesAddedRated: Float? = null,
    /** `charge_miles_added_ideal`: ideal miles added so far this session. */
    val chargeMilesAddedIdeal: Float? = null,
    /** `charge_rate_mph`: charging speed in miles per hour (int field). */
    val chargeRateMph: Int? = null,
    /** `charge_rate_mph_float`: charging speed in miles per hour (float field). */
    val chargeRateMphFloat: Float? = null,
    /** `charger_power`: charger power in watts. */
    val chargerPower: Int? = null,
    /** `charger_voltage`: charger voltage in volts. */
    val chargerVoltage: Int? = null,
    /** `charging_amps`: charging current limit in amps. */
    val chargingAmps: Int? = null,
) {
    /** The displayed level, derived for the chart, stats, and car view. */
    val percent: Int get() = batteryLevel

    val isCharging: Boolean get() = chargingState == "Charging"
}

/** Time window for the battery graph. */
enum class HistoryRange(
    val durationMillis: Long?,
) {
    SIX_HOURS(6 * 60 * 60 * 1000L),
    DAY(24 * 60 * 60 * 1000L),
    WEEK(7 * 24 * 60 * 60 * 1000L),
    ALL(null),
}

fun List<BatterySample>.within(
    range: HistoryRange,
    nowMillis: Long,
): List<BatterySample> {
    val duration = range.durationMillis ?: return this
    val cutoff = nowMillis - duration
    return filter { it.timestampMillis >= cutoff }
}

/** Summary since the end of the last completed charging session. */
data class ChargeStats(
    val sinceMillis: Long,
    val startPercent: Float,
    val currentPercent: Float,
    val minPercent: Float,
    val maxPercent: Float,
) {
    val usedPercent: Float get() = (startPercent - currentPercent).coerceAtLeast(0f)
}

/**
 * Stats since the last completed charge: the first sample after the last
 * charging sample. Returns null when nothing was charged yet or the car is
 * still charging.
 */
fun chargeStats(samples: List<BatterySample>): ChargeStats? {
    val lastCharging = samples.indexOfLast { it.isCharging }
    if (lastCharging < 0) return null
    val startIndex = lastCharging + 1
    if (startIndex >= samples.size) return null
    val start = samples[startIndex]
    val tail = samples.subList(startIndex, samples.size)
    return ChargeStats(
        sinceMillis = start.timestampMillis,
        startPercent = start.percent.toFloat(),
        currentPercent = tail.last().percent.toFloat(),
        minPercent = tail.minOf { it.percent.toFloat() },
        maxPercent = tail.maxOf { it.percent.toFloat() },
    )
}
