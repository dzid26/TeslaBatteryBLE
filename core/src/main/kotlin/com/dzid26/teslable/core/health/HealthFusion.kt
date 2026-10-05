// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import kotlin.math.abs

/**
 * Fuses the rated-range and energy-delta SoH estimates into one display value.
 *
 * Gaps between the two sources above 5 percentage points are flagged as a
 * mismatch so the UI can ask the user to check the input data.
 */
data class FusedSoH(
    val sohPercent: Double,
    val spreadPoints: Double,
    val sources: Int,
    val mismatch: Boolean,
)

object HealthFusion {
    fun fuse(
        ratedRangeSohPercent: Double?,
        energyDeltaSohPercent: Double?,
    ): FusedSoH? {
        val rated = ratedRangeSohPercent?.takeUnless { it.isNaN() }
        val delta = energyDeltaSohPercent?.takeUnless { it.isNaN() }
        if (rated == null && delta == null) return null
        if (rated != null && delta == null) {
            return FusedSoH(rated, 0.0, 1, false)
        }
        if (rated == null && delta != null) {
            return FusedSoH(delta, 0.0, 1, false)
        }
        val spread = abs(rated!! - delta!!)
        return FusedSoH((rated + delta) / 2.0, spread, 2, spread > 5.0)
    }
}
