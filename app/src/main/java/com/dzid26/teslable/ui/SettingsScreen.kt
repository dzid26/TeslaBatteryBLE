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
                        text = "Vehicle key",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Include key in Android backup",
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
                                "Android can restore the key on a new phone. " +
                                    "Requires encrypted backup (Android 12+); older versions keep it out of backups."
                            } else {
                                "The key is encrypted with this device's Keystore and stays out of backups. " +
                                    "On a new phone you re-pair with an NFC card tap."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            title = { Text("Include key in Android backup?") },
            text = {
                Text(
                    "The vehicle key will be stored so Android can back it up. " +
                        "Anyone with your Google backup and device lock could use it near the car — " +
                        "they could read vehicle data and control charging, but they cannot unlock or drive, " +
                        "and adding new keys still needs the NFC card tap plus vehicle confirmation. " +
                        "If you turn this off later, older backup copies may remain until they are replaced.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEnableDialog = false
                        keyStore.setBackupEnabled(true)
                        backupEnabled = true
                    },
                ) { Text("Include in backup") }
            },
            dismissButton = {
                TextButton(onClick = { showEnableDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("Stop backing up the key?") },
            text = {
                Text(
                    "New backups will exclude the key, but copies already stored in Android backup " +
                        "may remain until they are replaced.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDisableDialog = false
                        keyStore.setBackupEnabled(false)
                        backupEnabled = false
                    },
                ) { Text("Stop backup") }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text("Cancel") }
            },
        )
    }
}
