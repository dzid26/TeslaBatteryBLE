package com.dzid26.teslable.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.PairingPhase
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.connectionDisplay
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.chargeStats
import com.dzid26.teslable.core.history.within
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    state: BleUiState,
    history: List<BatterySample>,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onVinChange: (String) -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onPairKey: () -> Unit,
    onWake: () -> Unit,
    onReadSoc: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TeslaBatteryBLE") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text("Enable", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = state.trackingEnabled,
                            onCheckedChange = onToggleTracking,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
        ) {
            if (!permissionsGranted) {
                Button(onClick = onRequestPermissions) {
                    Text("Grant Bluetooth permissions")
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!locationServicesEnabled) {
                Text(
                    text = "Location services are off. BLE scans return no results until it is enabled.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }

            OutlinedTextField(
                value = state.vinInput,
                onValueChange = onVinChange,
                label = { Text("VIN (optional, highlights your car)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            state.expectedBleName?.let { expected ->
                Text(
                    text = "Advertised name for this VIN: $expected",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(12.dp))

            Button(onClick = onToggleScan, enabled = state.trackingEnabled) {
                Text(if (state.scanning) "Stop scan" else "Scan for Teslas")
            }
            Spacer(Modifier.height(12.dp))

            val connectedCount = state.connections.values.count { it.phase == ConnectionPhase.READY }
            val statusText = when {
                state.scanning ->
                    "Scanning: ${state.devices.size} Tesla(s), $connectedCount connected"

                state.devices.isNotEmpty() ->
                    "Scan stopped: ${state.devices.size} Tesla(s), $connectedCount connected"

                else -> "No scan yet"
            }
            Text(text = statusText, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))

            val selectedConnection = state.selectedAddress?.let { state.connections[it] }
            val canPair = selectedConnection?.phase == ConnectionPhase.READY &&
                state.pairingPhase != PairingPhase.SENDING
            Button(onClick = onPairKey, enabled = canPair) {
                Text("Pair charging key")
            }
            if (state.pairingPhase != PairingPhase.IDLE) {
                Text(
                    text = when (state.pairingPhase) {
                        PairingPhase.SENDING -> "Sending pairing request..."
                        PairingPhase.WAITING_FOR_CARD ->
                            "Tap your NFC card on the center console and confirm on the car screen. " +
                                "Then rename the new Phone Key in Controls > Locks."

                        PairingPhase.OK ->
                            "Key paired: ${state.pairingKeyId} (rename the Phone Key in Controls > Locks)"

                        PairingPhase.ERROR -> "Pairing failed"
                        PairingPhase.IDLE -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state.pairingPhase) {
                        PairingPhase.OK -> MaterialTheme.colorScheme.primary
                        PairingPhase.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(8.dp))

            val selectedSessions = selectedConnection?.sessions ?: emptyList()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onWake,
                    enabled = selectedSessions.contains("DOMAIN_VEHICLE_SECURITY") &&
                        selectedConnection?.status?.asleep == true,
                ) {
                    Text("Wake vehicle")
                }
                Button(
                    onClick = onReadSoc,
                    enabled = selectedConnection?.phase == ConnectionPhase.READY &&
                        selectedConnection.status?.asleep == false,
                ) {
                    Text("Read SOC")
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.devices, key = { it.address }) { device ->
                    DeviceRow(
                        device = device,
                        connection = state.connections[device.address],
                        expectedName = state.expectedBleName,
                        isSelected = device.address == state.selectedAddress,
                        onClick = { onConnect(device.address) },
                    )
                }
                item { BatteryHistoryCard(history) }
                if (state.log.isNotEmpty()) {
                    item {
                        Text(
                            text = "Log",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(state.log.takeLast(100)) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: TeslaAdvert,
    connection: TeslaConnection?,
    expectedName: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            val display = connectionDisplay(connection, device, showHints = true)
            val isMatch = expectedName != null && expectedName.equals(device.name, ignoreCase = true)
            Text(
                text = if (isMatch) "${display.title}  (VIN match)" else display.title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
            )
            if (connection != null) {
                Text(
                    text = display.status,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        connection.phase == ConnectionPhase.FAILED ->
                            MaterialTheme.colorScheme.error

                        connection.phase == ConnectionPhase.READY &&
                            connection.status?.asleep == false ->
                            MaterialTheme.colorScheme.primary

                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            connection?.status?.let { status ->
                Text(
                    text = "locked=${status.locked}  asleep=${status.asleep}  " +
                        "userPresent=${status.userPresent}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            connection?.keySlot?.let { slot ->
                Text(
                    text = "key slot: $slot",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (connection != null && connection.sessions.isNotEmpty()) {
                Text(
                    text = "sessions: ${connection.sessions.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            connection?.charge?.let { charge ->
                val details = buildList {
                    charge.chargeLimit?.let { add("limit $it%") }
                    charge.chargingState?.let { add("charger: $it") }
                }
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryHistoryCard(samples: List<BatterySample>) {
    var range by remember { mutableStateOf(HistoryRange.DAY) }
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
                    windowStart = range.durationMillis?.let { now - it }
                        ?: visible.first().timestampMillis,
                    windowEnd = now,
                    showDate = range != HistoryRange.SIX_HOURS,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                )
            }
            stats?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Since last charge: ${formatDuration(now - it.sinceMillis)} ago · " +
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
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
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

        fun x(timestampMillis: Long): Float =
            left + ((timestampMillis - windowStart).toFloat() / span).coerceIn(0f, 1f) * width

        fun y(percent: Int): Float =
            top + height - ((percent - yMin).toFloat() / (yMax - yMin)) * height

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
                topLeft = Offset(
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

private fun HistoryRange.label(): String = when (this) {
    HistoryRange.SIX_HOURS -> "6h"
    HistoryRange.DAY -> "24h"
    HistoryRange.WEEK -> "7d"
    HistoryRange.ALL -> "All"
}

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")

private fun formatTime(millis: Long, showDate: Boolean): String =
    Instant.ofEpochMilli(millis)
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

