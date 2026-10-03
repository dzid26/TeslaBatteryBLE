package com.dzid26.teslable

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.dzid26.teslable.ble.TeslaBleController
import com.dzid26.teslable.ui.ScannerScreen
import com.dzid26.teslable.ui.TeslaBleTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    permissionsGranted = result.values.all { it } && hasBlePermissions(context)
                    if (permissionsGranted) {
                        locationEnabled = isLocationEnabled(context)
                        controller.startScan()
                    }
                }

                ScannerScreen(
                    state = state,
                    permissionsGranted = permissionsGranted,
                    locationServicesEnabled = locationEnabled,
                    onRequestPermissions = {
                        permissionLauncher.launch(requiredBlePermissions().toTypedArray())
                    },
                    onToggleScan = {
                        if (state.scanning) {
                            controller.stopScan()
                        } else {
                            permissionsGranted = hasBlePermissions(context)
                            locationEnabled = isLocationEnabled(context)
                            if (!permissionsGranted) {
                                permissionLauncher.launch(requiredBlePermissions().toTypedArray())
                            } else {
                                controller.startScan()
                            }
                        }
                    },
                    onVinChange = controller::setVinInput,
                    onConnect = { advert -> controller.connect(advert.address) },
                    onDisconnect = controller::disconnect,
                )
            }
        }
    }
}

private fun requiredBlePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

private fun hasBlePermissions(context: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

private fun isLocationEnabled(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
