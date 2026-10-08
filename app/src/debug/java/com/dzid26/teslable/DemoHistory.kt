// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable

import android.content.Context
import com.dzid26.teslable.ble.DemoMode
import com.dzid26.teslable.core.TeslaNames
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import java.io.File
import java.time.Instant
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
    // Mirrors HistoryStore's log layout; the demo seeds it directly.
    private const val HISTORY_DIR_NAME = "battery-history"
    private const val LOG_SUFFIX = ".charge.pblog"
    private const val CHARGE_LIMIT = 85
    private const val RATED_MILES_PER_PERCENT = 3.0f
    private const val ESTIMATED_MILES_PER_PERCENT = 2.9f
    private const val IDEAL_MILES_PER_PERCENT = 3.2f
    private const val KWH_PER_PERCENT = 0.75f
    private const val MINUTE = 60_000L

    fun seed(context: Context) {
        val vehicleId =
            runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull() ?: return
        val dir = File(context.filesDir, HISTORY_DIR_NAME)
        val file = File(dir, "$vehicleId$LOG_SUFFIX")
        if (file.exists()) return
        dir.mkdirs()
        file.writeBytes(ProtoLog.encode(records(System.currentTimeMillis())))
    }

    /** One reading in the seeded timeline; [chargeStartPercent] marks a session. */
    private data class Point(
        val minutesAgo: Long,
        val percent: Int,
        val state: String,
        val chargeStartPercent: Int? = null,
    )

    private fun records(now: Long): List<BleRecord> {
        val points =
            buildList {
                // 48 h ago: parked at 64%, then a morning drive to 55.
                addAll(segment(2880, 2700, 64, 63, 60, "Disconnected"))
                addAll(segment(2700, 2580, 63, 55, 15, "Disconnected"))
                // Parked: 55 -> 54.
                addAll(segment(2580, 2460, 55, 54, 60, "Disconnected"))
                // First charge of the window: a two-hour AC session to 66.
                addAll(segment(2460, 2340, 54, 66, 10, "Charging"))
                // Drive to work: 66 -> 58.
                addAll(segment(2340, 2220, 66, 58, 15, "Disconnected"))
                // Parked: 58 -> 57.
                addAll(segment(2220, 2100, 58, 57, 60, "Disconnected"))
                // Second charge: a two-hour session to 69.
                addAll(segment(2100, 1980, 57, 69, 10, "Charging"))
                // Drive home: 69 -> 60.
                addAll(segment(1980, 1860, 69, 60, 15, "Disconnected"))
                // Parked overnight: 60 -> 58.
                addAll(segment(1860, 1440, 60, 58, 60, "Disconnected"))
                // Third charge: the four-hour AC session to the limit.
                addAll(segment(1440, 1200, 58, 85, 10, "Charging"))
                // Complete at the limit, then the slow parked drain to 78%.
                add(Point(1200, 85, "Complete"))
                addAll(segment(1140, 360, 84, 80, 60, "Disconnected"))
                addAll(segment(360, 10, 80, 78, 30, "Disconnected"))
            }
        // A segment's end and the next segment's start share a timestamp; keep
        // the newer one so a charge session starts on its own first sample.
        return points
            .reversed()
            .distinctBy { it.minutesAgo }
            .reversed()
            .map { point ->
                val charge = sample(now, point)
                // The simulated phone acquires each reply the moment the car stamps it.
                BleRecord(acquired_at = charge.timestamp, charge_state = charge)
            }
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
        point: Point,
    ): ChargeState {
        val rated = point.percent * RATED_MILES_PER_PERCENT
        val added = point.chargeStartPercent?.let { point.percent - it }
        return ChargeState(
            charging_state = chargingState(point.state),
            charge_limit_soc = CHARGE_LIMIT,
            battery_range = rated,
            est_battery_range = point.percent * ESTIMATED_MILES_PER_PERCENT,
            ideal_battery_range = point.percent * IDEAL_MILES_PER_PERCENT,
            battery_level = point.percent,
            usable_battery_level = point.percent,
            charge_energy_added = added?.let { it * KWH_PER_PERCENT } ?: 0f,
            charge_miles_added_rated = added?.let { it * RATED_MILES_PER_PERCENT } ?: 0f,
            charge_miles_added_ideal = added?.let { it * IDEAL_MILES_PER_PERCENT } ?: 0f,
            timestamp = Instant.ofEpochMilli(now - point.minutesAgo * MINUTE),
        )
    }

    private fun chargingState(state: String): ChargeState.ChargingState? =
        when (state) {
            "Charging" -> ChargeState.ChargingState(Charging = Void())
            "Complete" -> ChargeState.ChargingState(Complete = Void())
            "Disconnected" -> ChargeState.ChargingState(Disconnected = Void())
            else -> null
        }
}
