// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.health.HealthConfidence
import com.dzid26.teslable.health.HealthSummary
import kotlin.math.roundToInt

/**
 * Battery health, first cut: measured capacity and full range from the car's
 * own readings, with a confidence pill and the session-count learning gate.
 * No SoH percentage until the factory range is configured, and never any
 * cell-imbalance, pack-temperature, or lifespan claims.
 */
@Composable
internal fun BatteryHealthCard(summary: HealthSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Battery health",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                ConfidencePill(summary.confidence)
            }
            Spacer(Modifier.height(8.dp))

            summary.sohPercent?.let { soh ->
                EstimateRow(
                    label = "Capacity (SoH)",
                    value = "${soh.roundToInt()}%",
                    detail =
                        if (summary.sohMismatch) {
                            "rated-range and energy-delta sources differ"
                        } else {
                            "rated-range + energy-delta estimate"
                        },
                )
            }
            summary.capacityKwh?.let { kwh ->
                EstimateRow(
                    label = "Usable capacity",
                    value = "~${(kwh * 10).roundToInt() / 10f} kWh",
                    detail =
                        summary.ratedKwhPerMile?.let { constant ->
                            "rated constant ${(constant * 100).roundToInt() / 100f} kWh/mi"
                        } ?: "energy added over the last charge",
                )
            }
            summary.fullRangeMiles?.let { miles ->
                EstimateRow(
                    label = "Full range",
                    value = "~${miles.roundToInt()} mi",
                    detail = "rated range at ${summary.rangeSocPercent?.roundToInt()}% SOC",
                )
            }
            if (summary.capacityKwh == null && summary.fullRangeMiles == null && summary.sohPercent == null) {
                Text(
                    text = "No readings yet. Charge once with the app open, then read the battery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = confidenceLine(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.qualityNote?.let { note ->
                Text(
                    text = "Thin data: $note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "Measured on this phone from your own readings. A state-of-health " +
                        "percentage appears once the car's factory range is set.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EstimateRow(
    label: String,
    value: String,
    detail: String,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ConfidencePill(confidence: HealthConfidence) {
    val (container, content, label) =
        when (confidence) {
            HealthConfidence.LEARNING ->
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Learning",
                )

            HealthConfidence.LOW ->
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Low confidence",
                )

            HealthConfidence.MEDIUM ->
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Medium confidence",
                )

            HealthConfidence.HIGH ->
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "High confidence",
                )
        }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun confidenceLine(summary: HealthSummary): String =
    when (summary.confidence) {
        HealthConfidence.LEARNING ->
            "Learning — ${summary.sessions} of ${summary.learningTarget} charge sessions recorded."

        HealthConfidence.LOW ->
            "${summary.sessions} charge sessions recorded; confidence stays low until more."

        HealthConfidence.MEDIUM -> "${summary.sessions} charge sessions recorded."

        HealthConfidence.HIGH -> "${summary.sessions} charge sessions recorded."
    }
