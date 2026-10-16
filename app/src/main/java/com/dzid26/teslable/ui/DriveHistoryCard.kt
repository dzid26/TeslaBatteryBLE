// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.DriveWindow
import com.dzid26.teslable.core.history.FullRangeScaleEstimator
import com.dzid26.teslable.core.history.drivePoints
import com.dzid26.teslable.core.history.totalDrop
import com.dzid26.teslable.core.units.DistanceUnit
import java.util.Locale

/**
 * Battery percent over distance driven: the driving counterpart of the battery
 * history card, which plots percent over time. Percent is the sub-percent SOC
 * (the rated range over the fitted full range); points where the car was
 * regenerating are green. The window is the last 15, 50 or 150 km (10, 30 or
 * 100 mi) or everything since the last charge. Each trip is its own line, so a
 * charge or a long parked gap never gets a connecting stroke.
 */
@Composable
internal fun DriveHistoryCard(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    // Injectable for the Paparazzi snapshots; production opens on the middle window.
    initialWindow: DriveWindow = DriveWindow.MEDIUM,
) {
    var window by rememberSaveable { mutableStateOf(initialWindow) }
    val unit = LocalDistanceUnit.current
    // Up to 20k samples: fit and match only when the data or the window changes.
    val scale = remember(samples) { FullRangeScaleEstimator.estimate(samples) }
    val points =
        remember(samples, drives, window, unit, scale) { drivePoints(samples, drives, scale, window.miles(unit), window.sinceLastCharge) }
    val used = remember(points) { points.totalDrop { it.socPercent } }
    val plotted =
        remember(points, unit) {
            points.map { Plotted(unit.fromMiles(it.milesDriven.toFloat()).toDouble(), it.socPercent, it.regenerating, it.trip) }
        }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Drive history", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            DriveChips(window, unit) { window = it }
            Spacer(Modifier.height(8.dp))
            if (plotted.size < 2 || plotted.last().distance <= 0.0) {
                Text(
                    text = "No drives since the last charge yet. Battery percent is plotted against distance driven.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RangeChart(points = plotted, modifier = Modifier.fillMaxWidth().height(160.dp))
                Spacer(Modifier.height(6.dp))
                Legend()
                Spacer(Modifier.height(6.dp))
                DriveFooter(
                    plotted.last().distance,
                    unit,
                    used,
                    wholeStepsOnly =
                        scale?.pinned != true,
                )
            }
        }
    }
}

/** The distance-window chips: 15 / 50 / 150 km (10 / 30 / 100 mi) and since the last charge. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DriveChips(
    window: DriveWindow,
    unit: DistanceUnit,
    onWindow: (DriveWindow) -> Unit,
) {
    // Four chips do not fit a narrow phone on one line; they wrap whole.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DriveWindow.entries.forEach { entry ->
            val label = entry.label(unit)?.let { "$it ${unit.symbol}" } ?: "Since charge"
            FilterChip(selected = window == entry, onClick = { onWindow(entry) }, label = { Text(label, maxLines = 1) })
        }
    }
}

/** "Driven: N km \u00B7 N% used", plus a note while the percent is only whole levels. */
@Composable
private fun DriveFooter(
    driven: Double,
    unit: DistanceUnit,
    used: Float,
    wholeStepsOnly: Boolean,
) {
    Text(
        text = "Driven: ${formatDistance(driven, unit)} \u00B7 ${formatPercentTenths(used.coerceAtLeast(0f))}% used",
        style = MaterialTheme.typography.bodySmall,
    )
    if (wholeStepsOnly) {
        Text(
            text = "Whole-percent steps until the full range is learned.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendItem("Battery %", driveChartColor)
        LegendItem("Regen", chargingChartColor)
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color,
) {
    Text(
        text = "● $label",
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

/** A distance already in [unit], shown with the unit's symbol by the shared formatter. */
internal fun formatDistance(
    distance: Double,
    unit: DistanceUnit,
): String = unit.format((distance / unit.fromMiles(1f)).toFloat())

internal fun formatWholePercent(percent: Float): String = String.format(Locale.getDefault(), "%.0f%%", percent)

private fun formatPercentTenths(percent: Float): String = String.format(Locale.getDefault(), "%.1f", percent)
