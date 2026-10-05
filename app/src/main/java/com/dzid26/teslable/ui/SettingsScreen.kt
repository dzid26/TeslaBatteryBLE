// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.BuildConfig
import com.dzid26.teslable.ble.PairingKeyStore
import com.dzid26.teslable.ble.Vehicle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyStore: PairingKeyStore,
    vehicles: List<Vehicle>,
    onClearPairingCache: () -> Unit,
    onOpenAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    var backupEnabled by remember { mutableStateOf(keyStore.isBackupEnabled()) }
    // One key per car; read them when Settings opens (pairing happens elsewhere).
    val vehicleKeys =
        remember(vehicles) {
            vehicles.mapNotNull { vehicle ->
                keyStore
                    .load(vehicle.bleName)
                    ?.keyId
                    ?.toHex()
                    ?.let { id -> vehicle.title to id }
            }
        }
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                title = { Text("Settings") },
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
        ) {
            VehicleKeysCard(
                backupEnabled = backupEnabled,
                onToggleBackup = { checked ->
                    if (checked) {
                        showEnableDialog = true
                    } else {
                        showDisableDialog = true
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            PairingCard(
                keys = vehicleKeys,
                onClearCache = { showClearCacheDialog = true },
            )
            Spacer(Modifier.height(16.dp))
            AboutCard(onOpenAbout = onOpenAbout)
        }
    }

    if (showEnableDialog) {
        EnableBackupDialog(
            onConfirm = {
                showEnableDialog = false
                keyStore.setBackupEnabled(true)
                backupEnabled = true
            },
            onDismiss = { showEnableDialog = false },
        )
    }

    if (showDisableDialog) {
        DisableBackupDialog(
            onConfirm = {
                showDisableDialog = false
                keyStore.setBackupEnabled(false)
                backupEnabled = false
            },
            onDismiss = { showDisableDialog = false },
        )
    }

    if (showClearCacheDialog) {
        ClearPairingCacheDialog(
            onConfirm = {
                showClearCacheDialog = false
                onClearPairingCache()
            },
            onDismiss = { showClearCacheDialog = false },
        )
    }
}

@Composable
private fun VehicleKeysCard(
    backupEnabled: Boolean,
    onToggleBackup: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Vehicle keys",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Include vehicle keys in Android backup",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = backupEnabled,
                    onCheckedChange = onToggleBackup,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    if (backupEnabled) {
                        "Currently: vehicle keys are stored so Android can back them up. " +
                            "Google backups are encrypted with your Google account and device lock, " +
                            "so a new phone can restore pairing from them."
                    } else {
                        "Currently: vehicle keys are encrypted with this device's hardware-backed " +
                            "Keystore (AES) and stay out of Android backups. On a new phone you re-pair " +
                            "with an NFC card tap."
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (backupEnabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        "Android decides when to back up (usually daily, while idle and charging). " +
                            "Check or trigger one in system Settings \u2192 Backup. Android restores " +
                            "it during phone setup, a device transfer, or an app install when " +
                            "automatic restore is on; otherwise restore it manually or re-pair.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PairingCard(
    keys: List<Pair<String, String>>,
    onClearCache: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Pairing",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (keys.isEmpty()) {
                Text(
                    text = "No vehicle keys stored",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                keys.forEach { (title, id) ->
                    Text(
                        text = "$title · ${id.take(8)}…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                    "Each car has its own key, and pairing always enrolls a freshly generated one. " +
                        "Clearing the cache forgets cached key slots and sessions so the pairing flow " +
                        "can be tested again; stored keys are kept, and a car that still has its key " +
                        "is marked paired by the next whitelist check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onClearCache,
                enabled = keys.isNotEmpty(),
            ) {
                Text("Clear pairing cache")
            }
        }
    }
}

@Composable
private fun AboutCard(onOpenAbout: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAbout),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "TeslaBatteryBLE ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "Your Tesla's battery, tracked locally over BLE. " +
                        "No cloud, no account, nothing leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Version, licenses, privacy, and support",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EnableBackupDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Include vehicle keys in Android backup?") },
        text = {
            Text(
                "This applies to every car you have paired. Currently each vehicle key is " +
                    "encrypted with this device's hardware-backed Keystore (AES) and never leaves it. " +
                    "Turning this on stores the keys so Android can back them up; Google backups are " +
                    "encrypted with your Google account and device lock, so exposure requires someone " +
                    "who can restore your backup and unlock your phone. Near the car, that person could " +
                    "read vehicle data and control charging — they cannot unlock or drive, and new keys " +
                    "still need the NFC card tap plus vehicle confirmation. If you turn this off later, " +
                    "older backup copies may remain until they are replaced.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Include in backup") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DisableBackupDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop backing up vehicle keys?") },
        text = {
            Text(
                "New backups will exclude the keys, but copies already stored in Android backup " +
                    "may remain until they are replaced.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Stop backup") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ClearPairingCacheDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear pairing cache?") },
        text = {
            Text(
                "The app forgets the cached key slots and sessions so the pairing flow can be " +
                    "tested again. The stored key is kept and pairing again reuses it. If the car " +
                    "still has the key in its whitelist, the next whitelist check may mark it paired again.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Clear cache") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
