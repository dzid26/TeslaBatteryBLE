// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Explains one permission and what the user has to do, then asks for it. The
 * host advances to the next step when a permission is granted and closes the
 * wizard after the last one.
 */
@Composable
fun PermissionWizardDialog(
    step: PermissionStep,
    index: Int,
    total: Int,
    denied: Boolean,
    deniedForever: Boolean,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(step.title) },
        text = {
            Column {
                Text(
                    text = "Step ${index + 1} of $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(step.why, style = MaterialTheme.typography.bodyMedium)
                if (denied) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text =
                            if (deniedForever) {
                                "Android will not show the permission dialog again. " +
                                    "Open app settings to allow it."
                            } else {
                                "Permission denied. You can try again."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(
                    when {
                        deniedForever -> "Open app settings"
                        denied -> "Try again"
                        else -> "Allow"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
