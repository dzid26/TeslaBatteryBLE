// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.health

import com.dzid26.teslable.core.health.ChargeSession
import com.dzid26.teslable.core.health.EnergyDeltaEstimator
import com.dzid26.teslable.core.health.HealthFusion
import com.dzid26.teslable.core.health.MIN_SWING_PERCENT
import com.dzid26.teslable.core.health.RatedRangeEstimator
import com.dzid26.teslable.core.health.chargeSessions
import com.dzid26.teslable.core.history.BatterySample
import kotlin.math.roundToInt

// Battery-health glue: turns the recorded raw samples into the numbers the car
// view shows. All estimation and session segmentation live in `core/health`;
// this file only selects inputs, counts sessions for the learning gate, and
// flags thin data.

/** Confidence in the estimates, gated on how many charge sessions were seen. */
enum class HealthConfidence { LEARNING, LOW, MEDIUM, HIGH }

/**
 * The health card's data. [sohPercent] is null until the car's factory range
 * (and capacity) are configured, so the card never invents a baseline.
 */
data class HealthSummary(
    val sessions: Int,
    /** Sessions needed before the learning gate opens. */
    val learningTarget: Int,
    val sohPercent: Float?,
    val sohSpreadPoints: Float?,
    val sohMismatch: Boolean,
    val capacityKwh: Float?,
    /** The session's rated constant, kWh per rated mile. */
    val ratedKwhPerMile: Float?,
    val capacitySwingPercent: Float?,
    val fullRangeMiles: Float?,
    /** True when the full range came from the session's scale, not a reading. */
    val fullRangeFromSession: Boolean,
    val rangeSocPercent: Float?,
    val confidence: HealthConfidence,
    /** A plain-language caveat when the inputs are thin, else null. */
    val qualityNote: String?,
)

/**
 * Builds the card's data from one car's samples. [epaRatedRangeMiles] and
 * [newCapacityKwh] are the car's factory figures; while they are unknown the
 * card shows measured capacity only and no SoH percentage.
 */
fun healthSummary(
    samples: List<BatterySample>,
    epaRatedRangeMiles: Float? = null,
    newCapacityKwh: Float? = null,
): HealthSummary {
    val sessions = chargeSessions(samples)
    val lastSession = sessions.lastOrNull()
    // Rated miles are the backbone (research: range-as-soc-signal section 6):
    // a session that measured both energy added and rated miles added gives
    // the rated constant (kWh/mi) and the full-range scale directly. A single
    // rated-range reading is the fallback until such a session exists.
    val backboneSession = sessions.lastOrNull { it.kwhPerMile != null && it.fullRangeMiles != null }
    val energySession =
        sessions.lastOrNull { it.energyAddedKwh != null && it.swingPercent >= MIN_SWING_PERCENT }
    val rangeSample = samples.lastOrNull { it.ratedRangeMiles != null && it.batteryLevel > 0 }
    val fullRangeMiles =
        backboneSession?.fullRangeMiles
            ?: rangeSample?.let { sample -> sample.ratedRangeMiles!! / (sample.batteryLevel / 100f) }
    val ratedKwhPerMile = backboneSession?.kwhPerMile
    val capacityKwh =
        backboneSession?.let { session -> session.kwhPerMile!! * session.fullRangeMiles!! }
            ?: energySession?.let { session ->
                EnergyDeltaEstimator
                    .estimate(
                        energyAddedKwh = session.energyAddedKwh!!,
                        socStartPercent = session.startPercent,
                        socEndPercent = session.endPercent,
                    )?.usableCapacityKwh
            }
    val deltaSoh =
        energySession?.let { session ->
            newCapacityKwh?.let { baseline ->
                EnergyDeltaEstimator
                    .estimate(
                        energyAddedKwh = session.energyAddedKwh!!,
                        socStartPercent = session.startPercent,
                        socEndPercent = session.endPercent,
                        newCapacityKwh = baseline,
                    )?.sohPercent
            }
        }
    val ratedSoh =
        rangeSample?.let { sample ->
            epaRatedRangeMiles?.let { epa ->
                RatedRangeEstimator
                    .estimate(
                        displayedRangeMiles = sample.ratedRangeMiles!!,
                        socPercent = sample.batteryLevel.toFloat(),
                        epaRatedRangeMiles = epa,
                    )?.sohPercent
            }
        }
    val fused = HealthFusion.fuse(ratedSoh, deltaSoh)
    return HealthSummary(
        sessions = sessions.size,
        learningTarget = LEARNING_SESSIONS,
        sohPercent = fused?.sohPercent,
        sohSpreadPoints = fused?.spreadPoints,
        sohMismatch = fused?.mismatch == true,
        capacityKwh = capacityKwh,
        ratedKwhPerMile = ratedKwhPerMile,
        capacitySwingPercent = (backboneSession ?: energySession)?.swingPercent,
        fullRangeMiles = fullRangeMiles,
        fullRangeFromSession = backboneSession?.fullRangeMiles != null,
        rangeSocPercent = rangeSample?.batteryLevel?.toFloat(),
        confidence = confidenceFor(sessions.size),
        qualityNote = qualityNote(backboneSession ?: energySession ?: lastSession, rangeSample),
    )
}

private fun confidenceFor(sessions: Int): HealthConfidence =
    when {
        sessions < LEARNING_SESSIONS -> HealthConfidence.LEARNING
        sessions < MEDIUM_SESSIONS -> HealthConfidence.LOW
        sessions < HIGH_SESSIONS -> HealthConfidence.MEDIUM
        else -> HealthConfidence.HIGH
    }

private fun qualityNote(
    lastSession: ChargeSession?,
    rangeSample: BatterySample?,
): String? {
    val swing = lastSession?.swingPercent
    val rangeLevel = rangeSample?.batteryLevel
    return when {
        swing != null && swing < QUALITY_SWING_PERCENT ->
            "the last charge only swung ${swing.roundToInt()}% SOC"

        rangeLevel != null && rangeLevel < QUALITY_RANGE_SOC ->
            "the range reading was taken at $rangeLevel% SOC"

        else -> null
    }
}

/** Sessions before the "Learning" label gives way to low confidence. */
private const val LEARNING_SESSIONS = 3
private const val MEDIUM_SESSIONS = 10

/** The benchmark product needs about twenty sessions for a verdict; high confidence waits for as many. */
private const val HIGH_SESSIONS = 20

/** A charge swing under this widens the energy-delta error. */
private const val QUALITY_SWING_PERCENT = 20f

/** Rated-range extrapolation gets noisy below this SOC. */
private const val QUALITY_RANGE_SOC = 30
