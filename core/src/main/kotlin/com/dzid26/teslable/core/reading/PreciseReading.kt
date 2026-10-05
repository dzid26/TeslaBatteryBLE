// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.reading

import com.dzid26.teslable.core.protocol.TeslaCommands

/**
 * The most precise battery values a charge state can offer. The car reports
 * whole-percent levels but floats for range, so preferring these fields keeps
 * sub-percent and sub-mile information the integer fields would drop.
 */
data class PreciseReading(
    /** SOC in percent: the usable level when plausible, else the raw level. */
    val socPercent: Float?,
    /** Estimated range in miles when plausible, else the rated range. */
    val rangeMiles: Float?,
) {
    companion object {
        /**
         * Resolves [charge] with a documented fallback order:
         *
         * - SOC: `usable_battery_level` when it is a plausible percentage
         *   (1..100), otherwise `battery_level`.
         * - Range: `est_battery_range` when it is finite and positive,
         *   otherwise `battery_range` (rated).
         *
         * Absent and implausible values (zero, negative, NaN, infinite, over
         * 100%) are treated as missing, so a stale field falls through to the
         * next source instead of pinning a bogus 0% or 0 mi. A value is null
         * only when no source qualifies.
         */
        fun from(charge: TeslaCommands.Charge): PreciseReading =
            PreciseReading(
                socPercent =
                    plausiblePercent(charge.usableBatteryLevel)
                        ?: plausiblePercent(charge.batteryLevel),
                rangeMiles =
                    plausibleRange(charge.estBatteryRange)
                        ?: plausibleRange(charge.batteryRange),
            )

        private fun plausiblePercent(level: Int?): Float? = level?.takeIf { it in 1..100 }?.toFloat()

        private fun plausibleRange(miles: Float?): Float? = miles?.takeIf { it.isFinite() && it > 0f }
    }
}
