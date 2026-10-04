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
        if (energyAddedKwh.isNaN() || energyAddedKwh.isInfinite()) return null
        if (socStartPercent.isNaN() || socStartPercent.isInfinite()) return null
        if (socEndPercent.isNaN() || socEndPercent.isInfinite()) return null
        if (newCapacityKwh != null) {
            if (newCapacityKwh.isNaN() || newCapacityKwh.isInfinite()) return null
            if (newCapacityKwh <= 0.0) return null
        }
        if (energyAddedKwh <= 0.0) return null
        if (socStartPercent < 0.0) return null
        if (socEndPercent > 100.0) return null
        if (socStartPercent >= socEndPercent) return null
        val delta = socEndPercent - socStartPercent
        if (delta < 5.0) return null
        val usableCapacity = energyAddedKwh / (delta / 100.0)
        val soh = newCapacityKwh?.let { usableCapacity / it * 100.0 }
        return CapacityEstimate(usableCapacity, soh, delta)
    }
}
