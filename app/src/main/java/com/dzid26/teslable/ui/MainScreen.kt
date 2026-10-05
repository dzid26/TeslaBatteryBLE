// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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

/**
 * Two surfaces, no tabs: the cars list, and the car you opened. The last car
 * opened is restored on launch; back is a quiet app-bar arrow plus system back.
 */
@Composable
fun MainScreen(
    state: BleUiState,
    history: List<BatterySample>,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onOpenVehicle: (String) -> Unit,
    onOpenRequestConsumed: () -> Unit,
    onPairKey: (String) -> Unit,
    onVinChange: (String) -> Unit,
    onWake: () -> Unit,
    onReadSoc: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var viewingBleName by rememberSaveable { mutableStateOf<String?>(null) }
    var initialised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialised) {
            initialised = true
            // The controller already restored the last opened car from disk.
            viewingBleName = state.selectedBleName
        }
    }
    // A notification tap (or any external open request) switches to that car.
    LaunchedEffect(state.openVehicleRequest) {
        val requested = state.openVehicleRequest
        if (requested != null) {
            viewingBleName = requested
            onOpenRequestConsumed()
        }
    }

    val bleName = viewingBleName
    val vehicle = bleName?.let { name -> state.vehicles.firstOrNull { it.bleName == name } }
    val advert = bleName?.let { name -> state.devices.firstOrNull { it.name == name } }
    val address =
        vehicle?.address
            ?: advert?.address
            ?: bleName?.let { name -> state.connections.values.firstOrNull { it.name == name }?.address }

    if (bleName != null && address != null) {
        CarScreen(
            state = state,
            bleName = bleName,
            vehicle = vehicle,
            advert = advert,
            address = address,
            history = history,
            onBack = { viewingBleName = null },
            onPair = { onPairKey(address) },
            onVinChange = onVinChange,
            onWake = onWake,
            onReadSoc = onReadSoc,
            onOpenSettings = onOpenSettings,
        )
    } else {
        ConnectionsScreen(
            state = state,
            permissionsGranted = permissionsGranted,
            locationServicesEnabled = locationServicesEnabled,
            onRequestPermissions = onRequestPermissions,
            onToggleScan = onToggleScan,
            onToggleTracking = onToggleTracking,
            onOpen = { name, rowAddress ->
                viewingBleName = name
                onOpenVehicle(rowAddress)
            },
            onPair = { name, rowAddress, ready ->
                viewingBleName = name
                if (ready) onPairKey(rowAddress) else onOpenVehicle(rowAddress)
            },
            onOpenSettings = onOpenSettings,
        )
    }
}

// ------------------------------------------------------------------ cars list

private data class VehicleRow(
    val bleName: String,
    val address: String,
    val title: String,
    val vehicle: Vehicle?,
    val connection: TeslaConnection?,
    val advert: TeslaAdvert?,
)

private fun vehicleRows(state: BleUiState): List<VehicleRow> {
    val known =
        state.vehicles.map { vehicle ->
            VehicleRow(
                bleName = vehicle.bleName,
                address = vehicle.address,
                title = vehicle.title,
                vehicle = vehicle,
                connection = state.connections[vehicle.address],
                advert = state.devices.firstOrNull { it.name == vehicle.bleName },
            )
        }
    val discovered =
        state.devices
            .filter { device -> state.vehicles.none { it.bleName == device.name } }
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            .map { device ->
                VehicleRow(
                    bleName = device.name,
                    address = device.address,
                    title = state.connections[device.address]?.gattDeviceName ?: device.name,
                    vehicle = null,
                    connection = state.connections[device.address],
                    advert = device,
                )
            }
    return known + discovered
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsScreen(
    state: BleUiState,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onOpen: (String, String) -> Unit,
    onPair: (String, String, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val rows = vehicleRows(state)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cars") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
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

            Button(onClick = onToggleScan, enabled = state.trackingEnabled) {
                Text(if (state.scanning) "Stop scan" else "Scan for Teslas")
            }
            Spacer(Modifier.height(8.dp))

            val connectedCount = state.connections.values.count { it.phase == ConnectionPhase.READY }
            val statusText =
                when {
                    state.scanning ->
                        "Scanning: ${state.devices.size} Tesla(s), $connectedCount connected"

                    state.discovering ->
                        "Looking for your cars..."

                    rows.isNotEmpty() ->
                        "${rows.size} car(s), $connectedCount connected"

                    else -> "No scan yet"
                }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (rows.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No cars yet", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Scan to add your first Tesla.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(rows, key = { it.bleName }) { row ->
                        VehicleCard(
                            row = row,
                            explicitScan = state.explicitScan,
                            onOpen = { onOpen(row.bleName, row.address) },
                            onPair = {
                                onPair(
                                    row.bleName,
                                    row.address,
                                    row.connection?.phase == ConnectionPhase.READY,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(
    row: VehicleRow,
    explicitScan: Boolean,
    onOpen: () -> Unit,
    onPair: () -> Unit,
) {
    val display = connectionDisplay(row.connection, row.advert, showHints = true, vehicle = row.vehicle)
    val level = row.connection?.charge?.batteryLevel
    val paired =
        row.connection?.keySlot != null ||
            row.connection?.sessions?.isNotEmpty() == true ||
            row.connection?.pairing == PairingPhase.OK

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(display.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = display.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor(row.connection),
                )
                Text(
                    text = row.vehicle?.maskedVin ?: row.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (explicitScan && !paired && row.connection != null) {
                    TextButton(onClick = onPair) { Text("Pair") }
                }
            }
            if (level != null) {
                Text(
                    text = "$level%",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                StatusPill(row.connection)
            }
        }
    }
}

// -------------------------------------------------------------------- car view

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarScreen(
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
            PhoneKeyCard(connection, onPair)
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
    onPair: () -> Unit,
) {
    val paired =
        connection?.keySlot != null ||
            connection?.sessions?.isNotEmpty() == true ||
            connection?.pairing == PairingPhase.OK
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Phone key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text =
                            when {
                                !paired -> "Not paired"
                                connection.keySlot != null -> "Paired · slot ${connection.keySlot}"
                                else -> "Paired"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!paired) {
                    Button(
                        onClick = onPair,
                        enabled = connection?.phase == ConnectionPhase.READY,
                    ) {
                        Text("Pair key")
                    }
                }
            }
            val pairing = connection?.pairing ?: PairingPhase.IDLE
            if (pairing != PairingPhase.IDLE) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        when (pairing) {
                            PairingPhase.SENDING -> "Sending pairing request..."
                            PairingPhase.WAITING_FOR_CARD ->
                                "Tap your NFC card on the center console and confirm on the car screen."

                            PairingPhase.OK ->
                                "Key paired: ${connection?.pairingKeyId} — rename the Phone Key in Controls > Locks."

                            PairingPhase.ERROR -> "Pairing failed"
                            PairingPhase.IDLE -> ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (pairing) {
                            PairingPhase.OK -> MaterialTheme.colorScheme.primary
                            PairingPhase.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
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
        Column(Modifier.padding(12.dp)) {
            Text("VIN", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (editing) {
                OutlinedTextField(
                    value = vinInput,
                    onValueChange = onVinChange,
                    singleLine = true,
                    label = { Text("17 characters") },
                    isError = mismatch,
                    supportingText = {
                        if (mismatch) Text("Doesn't match this car's advertised name")
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
                        text = vehicle?.maskedVin ?: "Not set",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editing = true }) { Text("Edit") }
                }
                Text(
                    text =
                        if (vehicle?.vin != null) {
                            "Used for authenticated sessions; never logged."
                        } else {
                            "Required before pairing and SOC reads."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun StatusPill(connection: TeslaConnection?) {
    val (container, content, label) =
        when {
            connection?.phase == ConnectionPhase.READY && connection.status?.asleep == false ->
                Triple(
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                    "Awake",
                )

            connection?.phase == ConnectionPhase.READY && connection.status?.asleep == true ->
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    "Asleep",
                )

            connection?.phase == ConnectionPhase.FAILED ->
                Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    "Failed",
                )

            else ->
                Triple(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    if (connection?.phase == ConnectionPhase.CONNECTING) "Connecting" else "Disconnected",
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

@Composable
private fun statusColor(connection: TeslaConnection?): Color =
    when {
        connection?.phase == ConnectionPhase.READY && connection.status?.asleep == false ->
            MaterialTheme.colorScheme.primary

        connection?.phase == ConnectionPhase.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
