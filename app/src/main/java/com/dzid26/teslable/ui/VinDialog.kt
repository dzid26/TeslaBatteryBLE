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
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.core.TeslaNames

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
        title = { Text("VIN") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                label = { Text("17-character VIN") },
                isError = mismatch,
                supportingText = {
                    Text(
                        text =
                            when {
                                mismatch -> "Doesn't match this car's advertised name"
                                valid -> "Saved for this car; never logged."
                                else -> "Printed on the windshield or the driver's door jamb."
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
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
