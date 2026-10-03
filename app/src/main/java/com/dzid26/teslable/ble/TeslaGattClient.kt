package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID

class TeslaGattClient(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onPhase(phase: ConnectionPhase)
        fun onServices(services: List<GattServiceInfo>)
        fun onGattDeviceName(name: String?)
        fun onMtu(mtu: Int)
        fun onLog(message: String)
    }

    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        close()
        listener.onPhase(ConnectionPhase.CONNECTING)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    listener.onLog("GATT error status $status")
                    listener.onPhase(ConnectionPhase.FAILED)
                }

                newState == BluetoothProfile.STATE_CONNECTED -> {
                    listener.onLog("Connected, discovering services")
                    listener.onPhase(ConnectionPhase.CONNECTED)
                    if (!gatt.discoverServices()) {
                        listener.onLog("discoverServices() returned false")
                        listener.onPhase(ConnectionPhase.FAILED)
                    }
                }

                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onLog("Disconnected")
                    listener.onPhase(ConnectionPhase.DISCONNECTED)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onLog("Service discovery failed with status $status")
                listener.onPhase(ConnectionPhase.FAILED)
                return
            }
            listener.onPhase(ConnectionPhase.DISCOVERING)
            listener.onServices(
                gatt.services.map { service ->
                    GattServiceInfo(
                        uuid = service.uuid,
                        characteristicUuids = service.characteristics.map { it.uuid },
                    )
                }
            )
            if (!gatt.requestMtu(MTU_REQUEST)) {
                listener.onLog("requestMtu() returned false")
                readGattDeviceName(gatt)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                listener.onMtu(mtu)
            } else {
                listener.onLog("MTU negotiation failed with status $status")
            }
            readGattDeviceName(gatt)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleDeviceName(characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleDeviceName(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readGattDeviceName(gatt: BluetoothGatt) {
        val characteristic = gatt
            .getService(GENERIC_ACCESS_SERVICE)
            ?.getCharacteristic(DEVICE_NAME_CHARACTERISTIC)
        if (characteristic == null) {
            listener.onLog("Device name characteristic not exposed")
            listener.onPhase(ConnectionPhase.READY)
            return
        }
        if (!gatt.readCharacteristic(characteristic)) {
            listener.onLog("readCharacteristic() returned false")
            listener.onPhase(ConnectionPhase.READY)
        }
    }

    private fun handleDeviceName(value: ByteArray?) {
        listener.onGattDeviceName(value?.toString(Charsets.UTF_8)?.trim())
        listener.onPhase(ConnectionPhase.READY)
    }

    companion object {
        private const val MTU_REQUEST = 256
        private val GENERIC_ACCESS_SERVICE: UUID =
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_CHARACTERISTIC: UUID =
            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    }
}
