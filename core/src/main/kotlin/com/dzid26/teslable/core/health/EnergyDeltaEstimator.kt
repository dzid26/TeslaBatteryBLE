// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

/**
 * Energy-delta capacity method: capacity from energy added over an SOC swing.
 *
 * Reliability grows with larger SOC swings; clean uninterrupted sessions with
 * rested endpoints are preferred.
 */
data class CapacityEstimate(
    val usableCapacityKwh: Float,
    val sohPercent: Float?,
    val socDeltaPercent: Float,
)

object EnergyDeltaEstimator {
    fun estimate(
        energyAddedKwh: Float,
        socStartPercent: Float,
        socEndPercent: Float,
        newCapacityKwh: Float? = null,
    ): CapacityEstimate? {
        if (!isValidInputs(energyAddedKwh, socStartPercent, socEndPercent, newCapacityKwh)) return null
        val delta = socEndPercent - socStartPercent
        if (delta < 5f) return null
        val usableCapacity = energyAddedKwh / (delta / 100f)
        val soh = newCapacityKwh?.let { usableCapacity / it * 100f }
        return CapacityEstimate(usableCapacity, soh, delta)
    }

    private fun isValidInputs(
        energyAddedKwh: Float,
        socStartPercent: Float,
        socEndPercent: Float,
        newCapacityKwh: Float?,
    ): Boolean =
        energyAddedKwh.isFinite() &&
            energyAddedKwh > 0f &&
            socStartPercent.isFinite() &&
            socStartPercent >= 0f &&
            socEndPercent.isFinite() &&
            socEndPercent <= 100f &&
            socStartPercent < socEndPercent &&
            (newCapacityKwh == null || (newCapacityKwh.isFinite() && newCapacityKwh > 0f))
}
