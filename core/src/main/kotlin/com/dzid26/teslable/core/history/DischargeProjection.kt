// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ShiftStateKind
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a parked car's battery is heading at its parked drain rate over the range. */
data class DischargeProjection(
    /** The latest sample, where the projected line starts. */
    val from: BatterySample,
    /** Percent projected one horizon after the last sample. */
    val projectedPercent: Int,
    /** Wall-clock time the projection refers to. */
    val projectedAtMillis: Long,
    /** How far ahead the projection looks: half of the selected range. */
    val horizonMillis: Long,
    /** The parked drain rate the line follows, in percentage points lost per hour. */
    val percentPerHour: Double,
)

/**
 * Projects the parked drain: the drain rate of the parked stretches in the
 * selected range is carried half the range forward, matching the chart's
 * expanded x-axis.
 *
 * Only parked pairs of consecutive battery samples feed the rate. A pair is
 * parked when neither sample is charging, neither is in gear (R, N or D: a
 * car stopped at a light keeps its odometer but is mid-drive), and the
 * odometer did not move between them. Each sample's drive reading is the
 * [DriveSample] nearest to it in time, within [DRIVE_MATCH_MILLIS] (a drive
 * read follows every charge read). A sample with no such reading is not known to be parked, so
 * its pairs are left out. A long gap with no readings still counts when the
 * odometer is unchanged across it: the overnight drop up to the wake-up
 * reading is exactly the drain this projects, while a drive no phone saw
 * shows up as an odometer change and is left out.
 *
 * The rate is the summed signed percent change over the summed duration of
 * the parked pairs, so a rounding blip that drops and recovers while parked
 * cancels out instead of inflating the drop.
 *
 * While charging it still projects: the line shows where the battery would
 * head if unplugged now, next to the charge projection. Returns null once the
 * whole projection lies in the past (the last sample is older than the
 * horizon), when the range holds less than [MIN_RATE_SPAN_MILLIS] of parked
 * time, or when the summed drop is not positive: a rising or flat line would
 * make the rate meaningless.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param rangeMillis the selected range: its samples feed the rate, and the projection looks half of it ahead.
 */
fun dischargeProjection(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    rangeMillis: Long,
    nowMillis: Long,
): DischargeProjection? {
    val last = samples.lastOrNull() ?: return null
    val horizon = rangeMillis / 2
    if (nowMillis - last.timestampMillis > horizon) return null

    val windowed = samples.filter { it.timestampMillis >= last.timestampMillis - rangeMillis }
    val parked = parkedDrain(windowed, drives.filter { it.odometerInHundredthsOfAMile != null })
    if (parked.millis < MIN_RATE_SPAN_MILLIS || parked.drop <= 0) return null

    val projected =
        (last.percent - parked.drop.toDouble() * horizon / parked.millis)
            .roundToInt()
            .coerceIn(0, 100)
    return DischargeProjection(
        from = last,
        projectedPercent = projected,
        projectedAtMillis = last.timestampMillis + horizon,
        horizonMillis = horizon,
        percentPerHour = parked.drop.toDouble() * HOUR / parked.millis,
    )
}

/** The summed signed percent drop and duration of the parked pairs. */
private class ParkedDrain(
    val drop: Int,
    val millis: Long,
)

/** Sums the parked pairs of [samples]; [drives] all carry an odometer and are oldest first. */
private fun parkedDrain(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
): ParkedDrain {
    var drop = 0
    var millis = 0L
    val readings = driveReadingsAt(samples, drives)
    for (index in 1 until samples.size) {
        val a = samples[index - 1]
        val b = samples[index]
        val readingA = readings[index - 1]
        val readingB = readings[index]
        val stayed =
            readingA != null &&
                readingB != null &&
                readingA.odometerInHundredthsOfAMile == readingB.odometerInHundredthsOfAMile &&
                !readingA.inGear &&
                !readingB.inGear
        if (stayed && !a.isCharging && !b.isCharging) {
            drop += a.percent - b.percent
            millis += b.timestampMillis - a.timestampMillis
        }
    }
    return ParkedDrain(drop, millis)
}

/** In R, N or D: the car is being driven even if the odometer has not ticked yet. */
private val DriveSample.inGear: Boolean
    get() = shiftState == ShiftStateKind.R || shiftState == ShiftStateKind.N || shiftState == ShiftStateKind.D

/**
 * The drive sample nearest to each battery sample, or null when none lies
 * within [DRIVE_MATCH_MILLIS]. One sweep, since both lists are oldest first.
 */
private fun driveReadingsAt(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
): List<DriveSample?> {
    var next = 0
    return samples.map { sample ->
        // Advance to the first drive sample after this battery sample; the
        // nearest one is either it or the one just before.
        while (next < drives.size && drives[next].timestampMillis <= sample.timestampMillis) next++
        listOfNotNull(drives.getOrNull(next - 1), drives.getOrNull(next))
            .minByOrNull { abs(it.timestampMillis - sample.timestampMillis) }
            ?.takeIf { abs(it.timestampMillis - sample.timestampMillis) <= DRIVE_MATCH_MILLIS }
    }
}

private const val MINUTE = 60_000L

private const val HOUR = 60 * MINUTE

/** Less parked time than this makes the rate too noisy to extrapolate. */
private const val MIN_RATE_SPAN_MILLIS = 10 * MINUTE

/** A drive sample this close to a battery sample gives its odometer; a drive read follows each charge read. */
private const val DRIVE_MATCH_MILLIS = 2 * MINUTE
