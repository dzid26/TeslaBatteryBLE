// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dzid26.teslable.R
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.protocol.TeslaNames

/**
 * The one-time VIN entry for a car, opened from the cars list. The Save button
 * only enables when the VIN hashes to that car's advertised name, so a typo is
 * caught before it is stored.
 */
@Composable
internal fun VinDialog(
    bleName: String,
    initialVin: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initialVin) }
    val normalized = Vehicle.normalizeVin(input)
    val expected =
        if (normalized.length == Vehicle.VIN_LENGTH) {
            runCatching { TeslaNames.bleName(normalized) }.getOrNull()
        } else {
            null
        }
    val mismatch = normalized.length == Vehicle.VIN_LENGTH && expected != null && expected != bleName
    val valid = normalized.length == Vehicle.VIN_LENGTH && expected == bleName
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vin_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text(stringResource(R.string.vin_label)) },
                isError = mismatch,
                supportingText = {
                    Text(
                        text =
                            when {
                                mismatch -> stringResource(R.string.vin_mismatch)
                                valid -> stringResource(R.string.vin_saved)
                                else -> stringResource(R.string.vin_hint)
                            },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(normalized) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
