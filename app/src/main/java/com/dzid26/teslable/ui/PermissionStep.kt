// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.Manifest

/** One permission the app needs, with the explanation shown before asking. */
data class PermissionStep(
    val permission: String,
    val title: String,
    val why: String,
)

/** Builds the wizard steps for the permissions this Android version needs. */
fun permissionSteps(permissions: List<String>): List<PermissionStep> =
    permissions.mapNotNull { permission ->
        when (permission) {
            Manifest.permission.BLUETOOTH_SCAN ->
                PermissionStep(
                    permission = permission,
                    title = "Allow Bluetooth scanning",
                    why =
                        "TeslaBatteryBLE scans for cars that advertise the Tesla BLE " +
                            "name. Android treats Bluetooth scanning as location-capable, " +
                            "but the app never reads or stores your location.",
                )

            Manifest.permission.BLUETOOTH_CONNECT ->
                PermissionStep(
                    permission = permission,
                    title = "Allow Bluetooth connections",
                    why =
                        "Needed to open the encrypted link to the car you select and " +
                            "read its battery state.",
                )

            Manifest.permission.ACCESS_FINE_LOCATION ->
                PermissionStep(
                    permission = permission,
                    title = "Allow location access",
                    why =
                        "This Android version requires location permission before apps " +
                            "can scan for Bluetooth devices. The app never reads your " +
                            "location, and nothing leaves the phone.",
                )

            else -> null
        }
    }
