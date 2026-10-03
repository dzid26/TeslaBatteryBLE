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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.TeslaAdvert

@Composable
fun ScannerScreen(
    state: BleUiState,
    permissionsGranted: Boolean,
    locationServicesEnabled: Boolean,
    onRequestPermissions: () -> Unit,
    onToggleScan: () -> Unit,
    onVinChange: (String) -> Unit,
    onConnect: (TeslaAdvert) -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text("Tesla BLE scanner", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            if (!permissionsGranted) {
                Button(onClick = onRequestPermissions) {
                    Text("Grant Bluetooth permissions")
                }
                Spacer(Modifier.height(8.dp))
            }
            if (!locationServicesEnabled) {
                Text(
                    text = "Location services are off. Many phones return no BLE scan results until they are enabled.",
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

            Button(onClick = onToggleScan, enabled = permissionsGranted) {
                Text(if (state.scanning) "Stop scan" else "Scan for Teslas")
            }
            Spacer(Modifier.height(12.dp))

            Text(
                text = if (state.devices.isEmpty()) {
                    "No Tesla advertisements seen yet"
                } else {
                    "${state.devices.size} Tesla(s) found"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
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
                        expectedName = state.expectedBleName,
                        onClick = { onConnect(device) },
                    )
                }
            }

            ConnectionPanel(state = state, onDisconnect = onDisconnect)
        }
    }
}

@Composable
private fun DeviceRow(
    device: TeslaAdvert,
    expectedName: String?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            val isMatch = expectedName != null && expectedName.equals(device.name, ignoreCase = true)
            Text(
                text = if (isMatch) "${device.name}  (VIN match)" else device.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${device.address}   RSSI ${device.rssi} dBm   connectable=${device.connectable}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: BleUiState,
    onDisconnect: () -> Unit,
) {
    val showPanel = state.phase != ConnectionPhase.IDLE || state.connectedAddress != null
    if (!showPanel) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text("phase: ${state.phase}", style = MaterialTheme.typography.bodySmall)
            state.connectedAdvertisedName?.let {
                Text("advertised name: $it", style = MaterialTheme.typography.bodySmall)
            }
            state.gattDeviceName?.let {
                Text("GATT device name: $it", style = MaterialTheme.typography.bodySmall)
            }
            state.mtu?.let {
                Text("MTU: $it", style = MaterialTheme.typography.bodySmall)
            }
            state.services.forEach { service ->
                Text("service ${service.uuid}", style = MaterialTheme.typography.bodySmall)
                service.characteristicUuids.forEach { characteristic ->
                    Text("   char $characteristic", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.phase != ConnectionPhase.IDLE && state.phase != ConnectionPhase.DISCONNECTED) {
                Button(onClick = onDisconnect, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Disconnect")
                }
            }
            if (state.log.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Log", style = MaterialTheme.typography.labelLarge)
                state.log.takeLast(6).forEach { line ->
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
