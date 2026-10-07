// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.batteryPercent
import com.dzid26.teslable.ble.connectionDisplay
import com.dzid26.teslable.core.history.BatterySample

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
    onSaveVin: (String, String) -> Unit,
    onWake: (String?) -> Unit,
    onReadSoc: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var viewingBleName by rememberSaveable { mutableStateOf<String?>(null) }
    var vinTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var initialised by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!initialised) {
            initialised = true
            // The controller already restored the last opened car from disk;
            // without permissions the cars list is where the fallback button
            // lives, so start there instead of on the car view.
            viewingBleName = if (permissionsGranted) state.selectedBleName else null
        }
    }
    // A notification tap (or any external open request) switches to that car.
    LaunchedEffect(state.openVehicleRequest) {
        val requested = state.openVehicleRequest
        if (requested != null) {
            if (permissionsGranted) viewingBleName = requested
            onOpenRequestConsumed()
        }
    }

    val bleName = viewingBleName
    val vehicle = bleName?.let { name -> state.vehicles.firstOrNull { it.bleName == name } }
    val advert = bleName?.let { name -> state.devices.firstOrNull { it.name == name } }
    val address =
        vehicle?.address
            ?: advert?.address
            ?: bleName?.let { name ->
                state.connections.values
                    .firstOrNull { it.name == name }
                    ?.address
            }

    if (bleName != null && address != null) {
        CarScreen(
            state = state,
            vehicle = vehicle,
            advert = advert,
            address = address,
            history = history,
            onBack = { viewingBleName = null },
            onPair = { onPairKey(address) },
            onRefresh = {
                // One pull does the right thing for the current state: reconnect,
                // wake the car, or read the battery.
                val connection = state.connections[address]
                when {
                    connection?.phase != ConnectionPhase.READY -> onOpenVehicle(address)
                    connection.status?.asleep != false -> onWake(null)
                    else -> onReadSoc()
                }
            },
            onOpenSettings = onOpenSettings,
            onToggleTracking = onToggleTracking,
        )
    } else {
        ConnectionsScreen(
            state = state,
            history = history,
            permissionsGranted = permissionsGranted,
            locationServicesEnabled = locationServicesEnabled,
            onRequestPermissions = onRequestPermissions,
            onToggleScan = onToggleScan,
            onToggleTracking = onToggleTracking,
            onOpen = { name, rowAddress ->
                if (permissionsGranted) {
                    viewingBleName = name
                    onOpenVehicle(rowAddress)
                } else {
                    // Connecting without permissions would crash on Android 12+.
                    onRequestPermissions()
                }
            },
            onEditVin = { name -> vinTarget = name },
            onWake = onWake,
            onOpenSettings = onOpenSettings,
        )
    }

    VinEditor(
        state = state,
        target = vinTarget,
        onSaveVin = onSaveVin,
        onDismiss = { vinTarget = null },
    )
}

/** The cars-list VIN editor; renders nothing unless a car is being edited. */
@Composable
private fun VinEditor(
    state: BleUiState,
    target: String?,
    onSaveVin: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (target == null) return
    val vehicle = state.vehicles.firstOrNull { it.bleName == target } ?: return
    VinDialog(
        bleName = target,
        initialVin = vehicle.vin ?: "",
        onSave = { vin ->
            onSaveVin(target, vin)
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

// ------------------------------------------------------------------ cars list

private data class VehicleRow(
    val bleName: String,
    val address: String,
    val title: String,
    val vehicle: Vehicle?,
    val connection: TeslaConnection?,
    val advert: TeslaAdvert?,
    val lastKnown: BatterySample?,
)

private fun vehicleRows(
    state: BleUiState,
    history: List<BatterySample>,
): List<VehicleRow> {
    val known =
        state.vehicles
            .sortedBy { it.bleName }
            .map { vehicle ->
                VehicleRow(
                    bleName = vehicle.bleName,
                    address = vehicle.address,
                    title = vehicle.title,
                    vehicle = vehicle,
                    connection = state.connections[vehicle.address],
                    advert = state.devices.firstOrNull { it.name == vehicle.bleName },
                    lastKnown = history.lastOrNull { it.vehicleId == vehicle.bleName },
                )
            }
    val discovered =
        state.devices
            .filter { device -> state.vehicles.none { it.bleName == device.name } }
            .sortedBy { it.name }
            .map { device ->
                VehicleRow(
                    bleName = device.name,
                    address = device.address,
                    title = state.connections[device.address]?.gattDeviceName ?: device.name,
                    vehicle = null,
                    connection = state.connections[device.address],
                    advert = device,
                    lastKnown = null,
                )
            }
    return known + discovered
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsScreen(
    state: BleUiState,
    history: List<BatterySample>,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
    onOpen: (String, String) -> Unit,
    onEditVin: (String) -> Unit,
    onWake: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val rows = vehicleRows(state, history)
    // A fresh install has nothing to show, so start the scan it would ask for;
    // the global Enable toggle still gates it.
    LaunchedEffect(Unit) {
        if (rows.isEmpty() && permissionsGranted && state.trackingEnabled) {
            onToggleScan()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cars") },
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
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = {
                if (!permissionsGranted) {
                    onRequestPermissions()
                } else if (!state.scanning) {
                    onToggleScan()
                }
            },
            state = pullState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            indicator = {
                // The pill is the manual-pull affordance only; the status row
                // below carries the scan spinner, so a running scan never
                // shows two spinners.
                if (pullState.distanceFraction > 0f) {
                    RefreshPill(
                        state = pullState,
                        isRefreshing = state.scanning,
                        pullLabel = "Scan for cars",
                        refreshingLabel = null,
                    )
                }
            },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
            ) {
                if (!permissionsGranted) {
                    PermissionCard(
                        title = "Allow Bluetooth access",
                        body =
                            "TeslaBatteryBLE finds and talks to your Tesla over Bluetooth. " +
                                "Android also requires location permission for BLE scans; the app " +
                                "never reads your location and nothing leaves the phone.",
                        button = "Grant permissions",
                        onClick = onRequestPermissions,
                    )
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

                val connectedCount = state.connections.values.count { it.phase == ConnectionPhase.READY }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.scanning || state.discovering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text =
                                if (rows.isNotEmpty()) {
                                    "${rows.size} car(s), $connectedCount connected"
                                } else {
                                    "No scan yet"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                VehicleList(
                    rows = rows,
                    scanning = state.scanning,
                    onOpen = onOpen,
                    onEditVin = onEditVin,
                    onWake = onWake,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.height(8.dp))
                LogCard(state.log)
            }
        }
    }
}

/** The empty state or the car cards; the log sits pinned below this list. */
@Composable
private fun VehicleList(
    rows: List<VehicleRow>,
    scanning: Boolean,
    onOpen: (String, String) -> Unit,
    onEditVin: (String) -> Unit,
    onWake: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = if (scanning) "Looking for your cars…" else "No cars yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    if (scanning) {
                        "Keep the app open and stay near the car."
                    } else {
                        "Pull down to scan."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(rows, key = { it.bleName }) { row ->
                VehicleCard(
                    row = row,
                    onOpen = { onOpen(row.bleName, row.address) },
                    onEditVin = { onEditVin(row.bleName) },
                    onWake = { onWake(row.bleName) },
                )
            }
        }
    }
}

/** A short, explicit prompt the wizard or a system settings screen backs up. */
@Composable
private fun PermissionCard(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) {
                Text(button)
            }
        }
    }
}

@Composable
private fun VehicleCard(
    row: VehicleRow,
    onOpen: () -> Unit,
    onEditVin: () -> Unit,
    onWake: () -> Unit,
) {
    val display = connectionDisplay(row.connection, row.advert, vehicle = row.vehicle)
    val reading = batteryPercent(row.connection, row.lastKnown, System.currentTimeMillis())
    val canWake =
        row.connection?.status?.asleep == true &&
            row.connection?.sessions?.contains("DOMAIN_VEHICLE_SECURITY") == true
    val vin = row.vehicle?.vin
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { if (row.vehicle != null) menuOpen = true },
                ),
    ) {
        Box {
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
                        text = vin ?: row.address,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (vin != null) FontFamily.Monospace else null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reading != null) {
                    Text(
                        text = "${reading.value}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (reading.stale) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                } else {
                    StatusPill(row.connection)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Wake") },
                    enabled = canWake,
                    onClick = {
                        menuOpen = false
                        onWake()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (vin == null) "Add VIN" else "Edit VIN") },
                    onClick = {
                        menuOpen = false
                        onEditVin()
                    },
                )
            }
        }
    }
}

/** The app-wide log; every line is tagged with the car it came from. */
@Composable
private fun LogCard(log: List<LogEntry>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Log", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            if (log.isEmpty()) {
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
                    log.takeLast(100).forEach { line ->
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

/** Shared by the cars list and the car view, so the two app bars cannot drift. */
@Composable
internal fun TrackingToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 8.dp, end = 4.dp),
    ) {
        Text("Enable", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Shared by the cars list and the car view. */
@Composable
internal fun StatusPill(
    connection: TeslaConnection?,
    onAsleepClick: (() -> Unit)? = null,
) {
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
    val asleep = connection?.phase == ConnectionPhase.READY && connection.status?.asleep == true
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        modifier = if (asleep && onAsleepClick != null) Modifier.clickable { onAsleepClick() } else Modifier,
    ) {
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
