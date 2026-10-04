// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.dzid26.teslable.core.TeslaNames

class TeslaScanner(
    context: Context,
    private val onDevices: (List<TeslaAdvert>) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = LinkedHashMap<String, TeslaAdvert>()

    private val callback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            if (name == null || !TeslaNames.isTeslaBleName(name)) {
                return
            }
            found[result.device.address] = TeslaAdvert(
                name = name,
                address = result.device.address,
                rssi = result.rssi,
            )
            onDevices(found.values.sortedByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("Scan failed with error $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val bleAdapter = adapter
        if (bleAdapter == null || !bleAdapter.isEnabled) {
            onLog("Bluetooth is unavailable or turned off")
            return
        }
        val scanner = bleAdapter.bluetoothLeScanner
        if (scanner == null) {
            onLog("BLE scanner unavailable")
            return
        }
        found.clear()
        onDevices(emptyList())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        onLog("Scanning for Tesla advertisements (name pattern S<hex>C)")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        adapter?.bluetoothLeScanner?.stopScan(callback)
    }
}
