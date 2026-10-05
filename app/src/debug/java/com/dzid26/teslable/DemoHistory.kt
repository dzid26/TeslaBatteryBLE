// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable

import android.content.Context
import com.dzid26.teslable.ble.DemoMode
import com.dzid26.teslable.core.TeslaNames
import com.dzid26.teslable.core.history.BatteryHistoryCsv
import com.dzid26.teslable.core.history.BatterySample
import java.io.File
import kotlin.math.roundToInt

/**
 * DEBUG-ONLY: seeds two days of plausible battery history for the simulated
 * car so demo and screenshot builds open with a populated graph and realistic
 * projections. Every raw field is filled the way the car reports it, so the
 * chart, stats, and both projections read like real data. Runs once, only for
 * the demo car, and only when no history file exists; it never reaches a
 * release build (debug source set plus [DemoMode]).
 */
internal object DemoHistory {
    private const val FILE_NAME = "battery-history.csv"
    private const val CHARGE_LIMIT = 85
    private const val RATED_MILES_PER_PERCENT = 3.0f
    private const val ESTIMATED_MILES_PER_PERCENT = 2.9f
    private const val IDEAL_MILES_PER_PERCENT = 3.2f
    private const val KWH_PER_PERCENT = 0.75f
    private const val MINUTE = 60_000L

    fun seed(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) return
        val vehicleId =
            runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull() ?: return
        file.writeText(
            samples(System.currentTimeMillis(), vehicleId)
                .joinToString(separator = "\n", postfix = "\n") { BatteryHistoryCsv.encode(it) },
        )
    }

    /** One reading in the seeded timeline; [chargeStartPercent] marks a session. */
    private data class Point(
        val minutesAgo: Long,
        val percent: Int,
        val state: String,
        val chargeStartPercent: Int? = null,
    )

    private fun samples(
        now: Long,
        vehicleId: String,
    ): List<BatterySample> {
        val points =
            buildList {
                // 48 h ago: parked at 64%, shedding about a percent an hour.
                addAll(segment(2880, 2520, 64, 63, 60, "Disconnected"))
                // A two-hour drive: 63 -> 54.
                addAll(segment(2520, 2400, 63, 54, 15, "Disconnected"))
                // Parked overnight: 54 -> 52.
                addAll(segment(2400, 1680, 54, 52, 60, "Disconnected"))
                // Another short drive: 52 -> 47.
                addAll(segment(1680, 1560, 52, 47, 15, "Disconnected"))
                // Parked: 47 -> 46.
                addAll(segment(1560, 1440, 47, 46, 60, "Disconnected"))
                // A four-hour AC charge to the limit.
                addAll(segment(1440, 1200, 46, 85, 10, "Charging"))
                // Complete at the limit, then the slow parked drain to 78%.
                add(Point(1200, 85, "Complete"))
                addAll(segment(1140, 360, 84, 80, 60, "Disconnected"))
                addAll(segment(360, 10, 80, 78, 30, "Disconnected"))
            }
        return points
            .distinctBy { it.minutesAgo }
            .map { point -> sample(now, vehicleId, point) }
    }

    /** Samples a straight run from [fromAgo] to [toAgo], inclusive of the start. */
    private fun segment(
        fromAgo: Long,
        toAgo: Long,
        fromPercent: Int,
        toPercent: Int,
        stepMin: Long,
        state: String,
    ): List<Point> {
        val span = (fromAgo - toAgo).coerceAtLeast(1L)
        val points = mutableListOf<Point>()
        var ago = fromAgo
        while (ago >= toAgo) {
            val fraction = (fromAgo - ago).toDouble() / span
            val percent = (fromPercent + (toPercent - fromPercent) * fraction).roundToInt()
            points +=
                Point(
                    minutesAgo = ago,
                    percent = percent,
                    state = state,
                    chargeStartPercent = if (state == "Charging") fromPercent else null,
                )
            ago -= stepMin
        }
        // Always land exactly on the segment end, even when the step overshoots.
        if (points.last().minutesAgo != toAgo) {
            points +=
                Point(
                    minutesAgo = toAgo,
                    percent = toPercent,
                    state = state,
                    chargeStartPercent = if (state == "Charging") fromPercent else null,
                )
        }
        return points
    }

    private fun sample(
        now: Long,
        vehicleId: String,
        point: Point,
    ): BatterySample {
        val rated = point.percent * RATED_MILES_PER_PERCENT
        val added = point.chargeStartPercent?.let { point.percent - it }
        return BatterySample(
            timestampMillis = now - point.minutesAgo * MINUTE,
            batteryLevel = point.percent,
            chargingState = point.state,
            chargeLimit = CHARGE_LIMIT,
            vehicleId = vehicleId,
            usableBatteryLevel = point.percent,
            ratedRangeMiles = rated,
            estRangeMiles = point.percent * ESTIMATED_MILES_PER_PERCENT,
            idealRangeMiles = point.percent * IDEAL_MILES_PER_PERCENT,
            chargeEnergyAdded = added?.let { it * KWH_PER_PERCENT } ?: 0f,
            chargeMilesAddedRated = added?.let { it * RATED_MILES_PER_PERCENT } ?: 0f,
            chargeMilesAddedIdeal = added?.let { it * IDEAL_MILES_PER_PERCENT } ?: 0f,
        )
    }
}
