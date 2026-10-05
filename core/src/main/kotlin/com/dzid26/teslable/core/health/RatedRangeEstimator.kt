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
    val fullRangeMiles: Float,
    val sohPercent: Float,
)

object RatedRangeEstimator {
    fun estimate(
        displayedRangeMiles: Float,
        socPercent: Float,
        epaRatedRangeMiles: Float,
    ): RatedRangeSoH? {
        if (displayedRangeMiles.isNaN() || displayedRangeMiles.isInfinite()) return null
        if (socPercent.isNaN() || socPercent.isInfinite()) return null
        if (epaRatedRangeMiles.isNaN() || epaRatedRangeMiles.isInfinite()) return null
        if (displayedRangeMiles <= 0f) return null
        if (socPercent <= 0f || socPercent > 100f) return null
        if (epaRatedRangeMiles <= 0f) return null
        val fullRange = displayedRangeMiles / (socPercent / 100f)
        val soh = fullRange / epaRatedRangeMiles * 100f
        return RatedRangeSoH(fullRange, soh)
    }
}
