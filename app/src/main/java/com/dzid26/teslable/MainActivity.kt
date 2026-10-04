package com.dzid26.teslable

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dzid26.teslable.ble.TeslaBleController
import com.dzid26.teslable.ui.ScannerScreen
import com.dzid26.teslable.ui.TeslaBleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TeslaBleTheme {
                val context = LocalContext.current
                val controller = remember { TeslaBleController(context.applicationContext) }
                DisposableEffect(Unit) {
                    onDispose { controller.close() }
                }

                val state by controller.state.collectAsState()
                var permissionsGranted by remember { mutableStateOf(hasBlePermissions(context)) }
                var locationEnabled by remember { mutableStateOf(isLocationEnabled(context)) }
                var requestedOnce by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    permissionsGranted = result.values.all { it } && hasBlePermissions(context)
                    if (permissionsGranted) {
                        locationEnabled = isLocationEnabled(context)
                        controller.startScan()
                    }
                }

                val requestPermissions: () -> Unit = {
                    val activity = context as? Activity
                    val deniedForever = requestedOnce && activity != null &&
                        requiredBlePermissions().none {
                            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
                        }
                    if (deniedForever) {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        requestedOnce = true
                        permissionLauncher.launch(requiredBlePermissions().toTypedArray())
                    }
                }

                ScannerScreen(
                    state = state,
                    permissionsGranted = permissionsGranted,
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
                    onVinChange = controller::setVinInput,
                    onConnect = controller::onTeslaClicked,
                    onPairKey = controller::pairKey,
                )
            }
        }
    }
}

private fun requiredBlePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun isLocationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
