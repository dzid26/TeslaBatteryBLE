// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

/**
 * Energy-delta capacity method: capacity from energy added over an SOC swing.
 *
 * Reliability grows with larger SOC swings; clean uninterrupted sessions with
 * rested endpoints are preferred.
 */
data class CapacityEstimate(
    val usableCapacityKwh: Double,
    val sohPercent: Double?,
    val socDeltaPercent: Double,
)

object EnergyDeltaEstimator {
    fun estimate(
        energyAddedKwh: Double,
        socStartPercent: Double,
        socEndPercent: Double,
        newCapacityKwh: Double? = null,
    ): CapacityEstimate? {
        if (!isValidInputs(energyAddedKwh, socStartPercent, socEndPercent, newCapacityKwh)) return null
        val delta = socEndPercent - socStartPercent
        if (delta < 5.0) return null
        val usableCapacity = energyAddedKwh / (delta / 100.0)
        val soh = newCapacityKwh?.let { usableCapacity / it * 100.0 }
        return CapacityEstimate(usableCapacity, soh, delta)
    }

    private fun isValidInputs(
        energyAddedKwh: Double,
        socStartPercent: Double,
        socEndPercent: Double,
        newCapacityKwh: Double?,
    ): Boolean =
        energyAddedKwh.isFinite() &&
            energyAddedKwh > 0.0 &&
            socStartPercent.isFinite() &&
            socStartPercent >= 0.0 &&
            socEndPercent.isFinite() &&
            socEndPercent <= 100.0 &&
            socStartPercent < socEndPercent &&
            (newCapacityKwh == null || (newCapacityKwh.isFinite() && newCapacityKwh > 0.0))
}
