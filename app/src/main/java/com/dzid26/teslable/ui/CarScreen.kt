// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.PairingPhase
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.chargingStateText
import com.dzid26.teslable.ble.connectionDisplay
import com.dzid26.teslable.ble.vehicleStatusText
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.chargeStats
import com.dzid26.teslable.core.history.within
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// -------------------------------------------------------------------- car view

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarScreen(
    state: BleUiState,
    bleName: String,
    vehicle: Vehicle?,
    advert: TeslaAdvert?,
    address: String,
    history: List<BatterySample>,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onVinChange: (String) -> Unit,
    onWake: () -> Unit,
    onReadSoc: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
) {
    BackHandler { onBack() }
    val connection = state.connections[address]
    val vehicleHistory =
        if (vehicle == null) {
            emptyList()
        } else {
            history.filter { it.vehicleId == vehicle.bleName }
        }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "All cars",
                        )
                    }
                },
                title = {
                    Text(vehicle?.title ?: connection?.gattDeviceName ?: advert?.name ?: "Car")
                },
                actions = {
                    TrackingToggle(
                        checked = state.trackingEnabled,
                        onCheckedChange = onToggleTracking,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HeroCard(connection, advert, vehicle)
            ActionsRow(connection, onWake, onReadSoc)
            PhoneKeyCard(connection, vehicle, onPair)
            VinCard(
                bleName = bleName,
                vehicle = vehicle,
                vinInput = state.vinInput,
                expectedBleName = state.expectedBleName,
                onVinChange = onVinChange,
            )
            BatteryHistoryCard(vehicleHistory)
            LogCard(bleName = bleName, log = state.log)
        }
    }
}

@Composable
private fun HeroCard(
    connection: TeslaConnection?,
    advert: TeslaAdvert?,
    vehicle: Vehicle?,
) {
    val display = connectionDisplay(connection, advert, vehicle = vehicle)
    val level = connection?.charge?.batteryLevel
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(display.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = display.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(connection)
            }
            Spacer(Modifier.height(12.dp))
            if (level != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$level",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "%",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { level / 100f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                )
            } else {
                Text(
                    text = display.stateText,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            connection?.status?.let { status ->
                Text(vehicleStatusText(status), style = MaterialTheme.typography.bodySmall)
            }
            connection?.charge?.let { charge ->
                val details =
                    buildList {
                        charge.chargeLimit?.let { add("Charge limit $it%") }
                        charge.chargingState?.let { add(chargingStateText(it)) }
                    }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsRow(
    connection: TeslaConnection?,
    onWake: () -> Unit,
    onReadSoc: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onWake,
            enabled =
                connection?.sessions?.contains("DOMAIN_VEHICLE_SECURITY") == true &&
                    connection.status?.asleep == true,
        ) {
            Text("Wake vehicle")
        }
        OutlinedButton(
            onClick = onReadSoc,
            enabled =
                connection?.phase == ConnectionPhase.READY &&
                    connection.status?.asleep == false,
        ) {
            Text("Read SOC")
        }
    }
}

@Composable
private fun PhoneKeyCard(
    connection: TeslaConnection?,
    vehicle: Vehicle?,
    onPair: () -> Unit,
) {
    // The stored slot proves enrollment even before the car answers.
    val keySlot = connection?.keySlot ?: vehicle?.keySlot
    val paired = keySlot != null || connection?.pairing == PairingPhase.OK
    val pairing = connection?.pairing ?: PairingPhase.IDLE
    val pairingInProgress = pairing == PairingPhase.SENDING || pairing == PairingPhase.WAITING_FOR_CARD
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text =
                            when {
                                !paired -> "Not paired"
                                keySlot != null -> "Paired · slot $keySlot"
                                else -> "Paired"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!paired) {
                    Button(
                        onClick = onPair,
                        enabled = connection?.phase == ConnectionPhase.READY && !pairingInProgress,
                    ) {
                        Text("Pair key")
                    }
                }
            }
            if (pairing != PairingPhase.IDLE) {
                Spacer(Modifier.height(8.dp))
                PairingStatus(pairing = pairing, keyId = connection?.pairingKeyId)
            }
        }
    }
}

@Composable
private fun PairingStatus(
    pairing: PairingPhase,
    keyId: String?,
) {
    val inProgress = pairing == PairingPhase.SENDING || pairing == PairingPhase.WAITING_FOR_CARD
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                text = pairingStatusText(pairing, keyId),
                style = MaterialTheme.typography.bodySmall,
                color =
                    when (pairing) {
                        PairingPhase.OK -> MaterialTheme.colorScheme.primary
                        PairingPhase.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            if (inProgress) {
                Text(
                    text = "Waiting for the car to confirm the key...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pairingStatusText(
    pairing: PairingPhase,
    keyId: String?,
): String =
    when (pairing) {
        PairingPhase.SENDING -> "Pairing request sent — waiting for the car."
        PairingPhase.WAITING_FOR_CARD ->
            "Tap your NFC card on the center console and confirm on the car screen."

        PairingPhase.OK -> "Key paired: $keyId — rename the Phone Key in Controls > Locks."
        PairingPhase.ERROR -> "Pairing failed. Check the car screen and try again."
        PairingPhase.IDLE -> ""
    }

@Composable
private fun VinCard(
    bleName: String,
    vehicle: Vehicle?,
    vinInput: String,
    expectedBleName: String?,
    onVinChange: (String) -> Unit,
) {
    var editing by rememberSaveable(bleName) { mutableStateOf(false) }
    val mismatch =
        vinInput.length == VIN_LENGTH &&
            expectedBleName != null &&
            expectedBleName != bleName
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = vinInput,
                    onValueChange = onVinChange,
                    singleLine = true,
                    label = { Text("VIN") },
                    isError = mismatch,
                    supportingText = {
                        Text(
                            text =
                                when {
                                    mismatch -> "Doesn't match this car's advertised name"
                                    vehicle?.vin != null -> "Used for authenticated sessions; never logged."
                                    else -> "Required before pairing and SOC reads."
                                },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { editing = false }) { Text("Done") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "VIN",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = vehicle?.maskedVin ?: "Not set",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun LogCard(
    bleName: String,
    log: List<LogEntry>,
) {
    val lines = log.filter { it.vehicleId == null || it.vehicleId == bleName }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Log", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (lines.isEmpty()) {
                Text(
                    text = "No activity yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val logScroll = rememberScrollState()
                LaunchedEffect(logScroll.maxValue) {
                    logScroll.scrollTo(logScroll.maxValue)
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(logScroll),
                ) {
                    lines.takeLast(100).forEach { line ->
                        Text(
                            text = line.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- battery card

@Composable
private fun BatteryHistoryCard(samples: List<BatterySample>) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.DAY) }
    val now = System.currentTimeMillis()
    val visible = samples.within(range, now)
    val stats = chargeStats(samples)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Battery history", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { entry ->
                    FilterChip(
                        selected = range == entry,
                        onClick = { range = entry },
                        label = { Text(entry.label()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    text = "No samples yet. SOC is recorded while the car is awake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BatteryChart(
                    samples = visible,
                    windowStart =
                        range.durationMillis?.let { now - it }
                            ?: visible.first().timestampMillis,
                    windowEnd = now,
                    showDate = range != HistoryRange.SIX_HOURS,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                )
            }
            stats?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        "Since last charge: ${formatDuration(now - it.sinceMillis)} ago · " +
                            "${it.currentPercent}% now · ${it.usedPercent}% used",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "min ${it.minPercent}% · max ${it.maxPercent}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BatteryChart(
    samples: List<BatterySample>,
    windowStart: Long,
    windowEnd: Long,
    showDate: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val chargingColor = Color(0xFF43A047)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle =
        MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        val left = 36.dp.toPx()
        val bottom = 20.dp.toPx()
        val top = 8.dp.toPx()
        val right = 8.dp.toPx()
        val width = size.width - left - right
        val height = size.height - top - bottom
        if (width <= 0f || height <= 0f) return@Canvas

        val minPercent = samples.minOf { it.percent }
        val maxPercent = samples.maxOf { it.percent }
        var yMin = ((minPercent - 2).coerceAtLeast(0) / 5) * 5
        var yMax = (((maxPercent + 2).coerceAtMost(100) + 4) / 5) * 5
        if (yMax <= yMin) {
            yMin = (yMin - 5).coerceAtLeast(0)
            yMax = (yMin + 5).coerceAtMost(100)
        }
        val span = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()

        fun x(timestampMillis: Long): Float = left + ((timestampMillis - windowStart).toFloat() / span).coerceIn(0f, 1f) * width

        fun y(percent: Int): Float = top + height - ((percent - yMin).toFloat() / (yMax - yMin)) * height

        listOf(yMin, (yMin + yMax) / 2, yMax).forEach { value ->
            val gridY = y(value)
            drawLine(
                color = gridColor,
                start = Offset(left, gridY),
                end = Offset(left + width, gridY),
                strokeWidth = 1.dp.toPx(),
            )
            val label = textMeasurer.measure("$value%", labelStyle)
            drawText(
                textLayoutResult = label,
                topLeft =
                    Offset(
                        x = left - label.size.width - 6.dp.toPx(),
                        y = gridY - label.size.height / 2f,
                    ),
            )
        }

        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            drawLine(
                color = if (current.isCharging) chargingColor else lineColor,
                start = Offset(x(previous.timestampMillis), y(previous.percent)),
                end = Offset(x(current.timestampMillis), y(current.percent)),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        if (samples.size == 1) {
            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = Offset(x(samples.first().timestampMillis), y(samples.first().percent)),
            )
        }

        val startLabel = textMeasurer.measure(formatTime(windowStart, showDate), labelStyle)
        drawText(
            textLayoutResult = startLabel,
            topLeft = Offset(left, size.height - startLabel.size.height),
        )
        val endLabel = textMeasurer.measure(formatTime(windowEnd, showDate), labelStyle)
        drawText(
            textLayoutResult = endLabel,
            topLeft = Offset(left + width - endLabel.size.width, size.height - endLabel.size.height),
        )
    }
}

private fun HistoryRange.label(): String =
    when (this) {
        HistoryRange.SIX_HOURS -> "6h"
        HistoryRange.DAY -> "24h"
        HistoryRange.WEEK -> "7d"
        HistoryRange.ALL -> "All"
    }

private const val VIN_LENGTH = 17

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")

private fun formatTime(
    millis: Long,
    showDate: Boolean,
): String =
    Instant
        .ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(if (showDate) dateTimeFormatter else timeFormatter)

private fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    val days = minutes / (24 * 60)
    val hours = minutes % (24 * 60) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
