// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.PairingPhase
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
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
            ?: bleName?.let { name ->
                state.connections.values
                    .firstOrNull { it.name == name }
                    ?.address
            }

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
            onToggleTracking = onToggleTracking,
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
                if (permissionsGranted) {
                    viewingBleName = name
                    onOpenVehicle(rowAddress)
                } else {
                    // Connecting without permissions would crash on Android 12+.
                    onRequestPermissions()
                }
            },
            onPair = { name, rowAddress, ready ->
                if (!permissionsGranted) {
                    onRequestPermissions()
                } else {
                    viewingBleName = name
                    if (ready) onPairKey(rowAddress) else onOpenVehicle(rowAddress)
                }
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
                    .padding(innerPadding)
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
    explicitScan: Boolean,
    onOpen: () -> Unit,
    onPair: () -> Unit,
) {
    val display = connectionDisplay(row.connection, row.advert, showHints = true, vehicle = row.vehicle)
    val level = row.connection?.charge?.batteryLevel
    val paired =
        row.vehicle?.keySlot != null ||
            row.connection?.keySlot != null ||
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
                    val pairingInProgress =
                        row.connection.pairing == PairingPhase.CHECKING ||
                            row.connection.pairing == PairingPhase.SENDING ||
                            row.connection.pairing == PairingPhase.WAITING_FOR_CARD
                    TextButton(
                        onClick = onPair,
                        enabled = !pairingInProgress,
                    ) {
                        Text("Pair")
                    }
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
internal fun StatusPill(connection: TeslaConnection?) {
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
