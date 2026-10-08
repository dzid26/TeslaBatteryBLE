// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts background tracking after a reboot or an app update, when Android
 * has stopped the foreground service and nothing else would start it again.
 *
 * Both broadcasts are exempt from the background foreground-service start
 * restriction. The receiver does not connect anything itself: it only calls
 * [BleTrackingService.start], whose `onStartCommand` reconnects the paired cars.
 */
class AutoStartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val state = BleControllerHolder.get(context).state.value
        val start =
            shouldAutoStartTracking(
                trackingEnabled = state.trackingEnabled,
                hasPairedCar = state.vehicles.any { it.isPaired },
                blePermissionsGranted = hasBlePermissions(context),
                notificationsGranted = hasNotificationPermission(context),
            )
        if (!start) {
            Log.i(LOG_TAG, "Not restarting tracking after ${intent.action}: nothing to track or permissions missing")
            return
        }
        Log.i(LOG_TAG, "Restarting tracking after ${intent.action}")
        BleTrackingService.start(context)
    }

    private fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOG_TAG = "AutoStart"
    }
}

/**
 * Whether a reboot or an update should bring the tracking service back: the
 * owner left tracking on, at least one car has an enrolled key to reconnect
 * to, and the runtime permissions the service needs are still granted. This
 * is not [shouldTrack], which also requires a READY connection.
 */
fun shouldAutoStartTracking(
    trackingEnabled: Boolean,
    hasPairedCar: Boolean,
    blePermissionsGranted: Boolean,
    notificationsGranted: Boolean,
): Boolean = trackingEnabled && hasPairedCar && blePermissionsGranted && notificationsGranted
