// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dzid26.teslable.ble.hasBlePermissions
import com.dzid26.teslable.ble.isLocationEnabled
import com.dzid26.teslable.ble.missingBlePermissions
import com.dzid26.teslable.ble.requiredBlePermissions
import com.dzid26.teslable.ble.shouldOpenAppSettings

/**
 * Owns the permission wizard and hands the current state to [content], keeping
 * launcher plumbing out of the screens. [refreshKey] must change when the
 * activity resumes from a system settings screen; [onGranted] runs once all
 * permissions are present so the caller can start scanning and connecting.
 */
@Composable
internal fun PermissionWizardHost(
    refreshKey: Int,
    onGranted: () -> Unit,
    content: @Composable (
        permissionsGranted: Boolean,
        locationServicesEnabled: Boolean,
        requestPermissions: () -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    var permissionsGranted by remember { mutableStateOf(hasBlePermissions(context)) }
    var locationServicesEnabled by remember { mutableStateOf(isLocationEnabled(context)) }
    var wizardOpen by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var deniedForever by remember { mutableStateOf(false) }

    val appSettingsIntent =
        remember(context) {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionsGranted = hasBlePermissions(context)
            denied = !granted
            deniedForever = !granted && shouldOpenAppSettings(context)
            if (granted && missingBlePermissions(context).isEmpty()) {
                wizardOpen = false
                locationServicesEnabled = isLocationEnabled(context)
                onGranted()
            }
        }

    LaunchedEffect(refreshKey) {
        permissionsGranted = hasBlePermissions(context)
        locationServicesEnabled = isLocationEnabled(context)
        if (wizardOpen && missingBlePermissions(context).isEmpty()) {
            wizardOpen = false
            onGranted()
        }
    }

    val allSteps = permissionSteps(requiredBlePermissions())
    val nextStep = allSteps.firstOrNull { it.permission in missingBlePermissions(context) }
    if (wizardOpen && nextStep != null) {
        PermissionWizardDialog(
            step = nextStep,
            index = allSteps.indexOf(nextStep),
            total = allSteps.size,
            denied = denied,
            deniedForever = deniedForever,
            onAllow = {
                if (deniedForever) {
                    context.startActivity(appSettingsIntent)
                } else {
                    launcher.launch(nextStep.permission)
                }
            },
            onDismiss = { wizardOpen = false },
        )
    }

    val requestPermissions: () -> Unit = {
        if (permissionsGranted) {
            onGranted()
        } else {
            wizardOpen = true
            denied = false
            deniedForever = false
        }
    }
    content(permissionsGranted, locationServicesEnabled, requestPermissions)
}
