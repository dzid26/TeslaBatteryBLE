// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dzid26.teslable.ble.BleControllerHolder
import com.dzid26.teslable.ble.BleTrackingService
import com.dzid26.teslable.ble.PairingKeyStore
import com.dzid26.teslable.ble.TeslaBleController
import com.dzid26.teslable.ble.hasBlePermissions
import com.dzid26.teslable.ble.isLocationEnabled
import com.dzid26.teslable.ble.requiredBlePermissions
import com.dzid26.teslable.ui.AboutScreen
import com.dzid26.teslable.ui.MainScreen
import com.dzid26.teslable.ui.PermissionState
import com.dzid26.teslable.ui.SettingsScreen
import com.dzid26.teslable.ui.TeslaBleTheme

class MainActivity : ComponentActivity() {
    private lateinit var controller: TeslaBleController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = BleControllerHolder.get(this)
        // A notification tap can ask for a specific car before the UI exists.
        intent
            ?.getStringExtra(BleTrackingService.EXTRA_BLE_NAME)
            ?.let(controller::openVehicleByBleName)
        setContent {
            TeslaBleTheme {
                val context = LocalContext.current

                // Reconnect to a paired car automatically when the app opens.
                LaunchedEffect(Unit) {
                    controller.ensureConnected()
                }

                // Poll RSSI fast only while the screen is showing the app.
                DisposableEffect(Unit) {
                    controller.setUiVisible(true)
                    onDispose { controller.setUiVisible(false) }
                }

                val state by controller.state.collectAsState()
                val batteryHistory by controller.batteryHistory.collectAsState()
                var permissionsGranted by remember { mutableStateOf(hasBlePermissions(context)) }
                var permissionsDeniedForever by remember { mutableStateOf(false) }
                var locationEnabled by remember { mutableStateOf(isLocationEnabled(context)) }
                var screen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }

                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) { result ->
                        permissionsGranted = result.values.all { it } && hasBlePermissions(context)
                        permissionsDeniedForever =
                            !permissionsGranted &&
                            shouldOpenAppSettings(context)
                        if (permissionsGranted) {
                            locationEnabled = isLocationEnabled(context)
                            controller.startScan()
                        }
                    }

                val requestPermissions: () -> Unit = {
                    if (permissionsDeniedForever) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        permissionLauncher.launch(requiredBlePermissions().toTypedArray())
                    }
                }

                val notificationPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {
                        // Start tracking whether or not notifications were allowed: the
                        // foreground service runs either way, it is just silent without
                        // the permission.
                        BleTrackingService.start(context)
                    }

                // Once a car with an enrolled key is connected, hand off to the
                // foreground service so BLE keeps running with the app backgrounded.
                val trackingNeeded = controller.shouldTrack()
                LaunchedEffect(trackingNeeded) {
                    if (!trackingNeeded || BleTrackingService.isRunning) return@LaunchedEffect
                    if (!hasNotificationPermission(context)) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        BleTrackingService.start(context)
                    }
                }

                when (screen) {
                    AppScreen.SETTINGS ->
                        SettingsScreen(
                            keyStore = remember { PairingKeyStore(context) },
                            onClearPairingCache = controller::clearPairingCache,
                            onOpenAbout = { screen = AppScreen.ABOUT },
                            onBack = { screen = AppScreen.MAIN },
                        )

                    AppScreen.ABOUT ->
                        AboutScreen(onBack = { screen = AppScreen.SETTINGS })

                    AppScreen.MAIN ->
                        MainScreen(
                            state = state,
                            history = batteryHistory,
                            permissionState =
                                when {
                                    permissionsGranted -> PermissionState.GRANTED
                                    permissionsDeniedForever -> PermissionState.DENIED_FOREVER
                                    else -> PermissionState.MISSING
                                },
                            locationServicesEnabled = locationEnabled,
                            onRequestPermissions = requestPermissions,
                            onToggleScan = {
                                if (state.scanning) {
                                    controller.stopScan()
                                } else {
                                    permissionsGranted = hasBlePermissions(context)
                                    locationEnabled = isLocationEnabled(context)
                                    if (permissionsGranted) {
                                        controller.startScan()
                                    } else {
                                        requestPermissions()
                                    }
                                }
                            },
                            onToggleTracking = controller::setTrackingEnabled,
                            onOpenVehicle = controller::openVehicle,
                            onOpenRequestConsumed = controller::consumeOpenVehicleRequest,
                            onPairKey = controller::pairKey,
                            onVinChange = controller::setVinInput,
                            onWake = { controller.wakeVehicle() },
                            onReadSoc = { controller.requestChargeState() },
                            onOpenSettings = { screen = AppScreen.SETTINGS },
                        )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent
            .getStringExtra(BleTrackingService.EXTRA_BLE_NAME)
            ?.let(controller::openVehicleByBleName)
    }

    override fun onResume() {
        super.onResume()
        // Android forbids starting a foreground service from the background;
        // retry on resume so tracking comes up after a denied attempt.
        if (!BleTrackingService.isRunning && controller.shouldTrack()) {
            BleTrackingService.start(this)
        }
    }
}

/** Top-level surfaces; the car list is the default and the back destination. */
private enum class AppScreen {
    MAIN,
    SETTINGS,
    ABOUT,
}

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * True when Android will no longer show the permission dialog: every required
 * permission is denied with "don't ask again", so the app must open settings.
 */
private fun shouldOpenAppSettings(context: Context): Boolean {
    val activity = context as? Activity ?: return false
    return requiredBlePermissions().none {
        ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }
}
