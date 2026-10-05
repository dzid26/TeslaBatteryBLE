// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

/**
 * Trended rated-range state-of-health method.
 *
 * Single readings carry BMS noise (temperature, rest state, recent driving), so
 * trend over weeks rather than trusting one sample. Best read at 100% SOC on a
 * rested car with the correct EPA baseline for the exact trim/year.
 */
data class RatedRangeSoH(
    val fullRangeMiles: Double,
    val sohPercent: Double,
)

object RatedRangeEstimator {
    fun estimate(
        displayedRangeMiles: Double,
        socPercent: Double,
        epaRatedRangeMiles: Double,
    ): RatedRangeSoH? {
        if (displayedRangeMiles.isNaN() || displayedRangeMiles.isInfinite()) return null
        if (socPercent.isNaN() || socPercent.isInfinite()) return null
        if (epaRatedRangeMiles.isNaN() || epaRatedRangeMiles.isInfinite()) return null
        if (displayedRangeMiles <= 0.0) return null
        if (socPercent <= 0.0 || socPercent > 100.0) return null
        if (epaRatedRangeMiles <= 0.0) return null
        val fullRange = displayedRangeMiles / (socPercent / 100.0)
        val soh = fullRange / epaRatedRangeMiles * 100.0
        return RatedRangeSoH(fullRange, soh)
    }
}
