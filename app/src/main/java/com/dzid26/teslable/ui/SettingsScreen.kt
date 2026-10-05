// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.PairingKeyStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyStore: PairingKeyStore,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }

    var backupEnabled by remember { mutableStateOf(keyStore.isBackupEnabled()) }
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }

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
                            onCheckedChange = { checked ->
                                if (checked) {
                                    showEnableDialog = true
                                } else {
                                    showDisableDialog = true
                                }
                            },
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
                }
            }
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
