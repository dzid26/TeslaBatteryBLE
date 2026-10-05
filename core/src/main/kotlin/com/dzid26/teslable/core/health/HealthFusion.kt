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
    val sohPercent: Float,
    val spreadPoints: Float,
    val sources: Int,
    val mismatch: Boolean,
)

object HealthFusion {
    fun fuse(
        ratedRangeSohPercent: Float?,
        energyDeltaSohPercent: Float?,
    ): FusedSoH? {
        val rated = ratedRangeSohPercent?.takeUnless { it.isNaN() }
        val delta = energyDeltaSohPercent?.takeUnless { it.isNaN() }
        if (rated == null && delta == null) return null
        if (rated != null && delta == null) {
            return FusedSoH(rated, 0f, 1, false)
        }
        if (rated == null && delta != null) {
            return FusedSoH(delta, 0f, 1, false)
        }
        val spread = abs(rated!! - delta!!)
        return FusedSoH((rated + delta) / 2f, spread, 2, spread > 5f)
    }
}
