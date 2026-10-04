package com.dzid26.teslable.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.PairingPhase
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(
    state: BleUiState,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onVinChange: (String) -> Unit,
    onConnect: (String) -> Unit,
    onPairKey: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("TeslaBatteryBLE") }) },
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

            Button(onClick = onToggleScan) {
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
                            "Tap your NFC card on the center console, then confirm on the car screen."

                        PairingPhase.OK -> "Key paired: ${state.pairingKeyId}"
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
            }

            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Log", style = MaterialTheme.typography.labelLarge)
                state.log.takeLast(5).forEach { line ->
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
            val advertisedName = connection?.name ?: device.name
            val displayName = connection?.gattDeviceName ?: advertisedName
            val isMatch = expectedName != null && expectedName.equals(advertisedName, ignoreCase = true)
            Text(
                text = if (isMatch) "$displayName  (VIN match)" else displayName,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${device.address}   RSSI ${device.rssi} dBm   connectable=${device.connectable}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (connection != null) {
                Text(
                    text = connectionSummary(connection),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (connection.phase) {
                        ConnectionPhase.FAILED -> MaterialTheme.colorScheme.error
                        ConnectionPhase.READY -> MaterialTheme.colorScheme.primary
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
        }
    }
}

private fun connectionSummary(connection: TeslaConnection): String = when (connection.phase) {
    ConnectionPhase.IDLE -> "not connected"

    ConnectionPhase.CONNECTING -> "connecting..."

    ConnectionPhase.CONNECTED -> "connected, discovering services..."

    ConnectionPhase.DISCOVERING -> "discovering services..."

    ConnectionPhase.READY -> buildString {
        append("connected")
        connection.mtu?.let { append(" | MTU $it") }
        append(" | ${connection.services.size} services")
    }

    ConnectionPhase.FAILED -> "connection failed - tap to retry"

    ConnectionPhase.DISCONNECTED -> "disconnected - tap to reconnect"
}
