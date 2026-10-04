package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.dzid26.teslable.core.TeslaGatt
import com.dzid26.teslable.core.framing.BleFramer

class TeslaGattClient(
    private val context: Context,
    private val listener: Listener,
) {

    interface Listener {
        fun onPhase(phase: ConnectionPhase)
        fun onServices(services: List<GattServiceInfo>)
        fun onGattDeviceName(name: String?)
        fun onMtu(mtu: Int)
        fun onMessage(message: ByteArray)
        fun onLog(message: String)
    }

    private var gatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = DEFAULT_MTU
    private var descriptorWriteDone = false
    private var mtuDone = false
    private var deviceNameRequested = false
    private val framer = BleFramer()

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
        txCharacteristic = null
        rxCharacteristic = null
        negotiatedMtu = DEFAULT_MTU
        descriptorWriteDone = false
        mtuDone = false
        deviceNameRequested = false
    }

    fun send(payload: ByteArray): Boolean {
        val gatt = gatt ?: return false
        val characteristic = txCharacteristic ?: run {
            listener.onLog("TX characteristic not ready")
            return false
        }
        val chunkSize = (negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(MIN_CHUNK_SIZE)
        for (chunk in BleFramer.encode(payload, chunkSize)) {
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    chunk,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (!started) {
                listener.onLog("writeCharacteristic() failed")
                return false
            }
        }
        return true
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

            val service = gatt.getService(TeslaGatt.SERVICE_UUID)
            if (service == null) {
                listener.onLog("Tesla GATT service not found")
                descriptorWriteDone = true
            } else {
                txCharacteristic = service.getCharacteristic(TeslaGatt.TO_VEHICLE_UUID)
                rxCharacteristic = service.getCharacteristic(TeslaGatt.FROM_VEHICLE_UUID)
                listener.onLog(
                    "TX characteristic: ${txCharacteristic != null}, " +
                        "RX characteristic: ${rxCharacteristic != null}"
                )
                val rx = rxCharacteristic
                if (rx == null) {
                    descriptorWriteDone = true
                } else {
                    subscribe(gatt, rx)
                }
            }

            if (!gatt.requestMtu(MTU_REQUEST)) {
                listener.onLog("requestMtu() returned false")
                mtuDone = true
            }
            maybeReadDeviceName(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                listener.onMtu(mtu)
            } else {
                listener.onLog("MTU negotiation failed with status $status")
            }
            mtuDone = true
            maybeReadDeviceName(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (descriptor.uuid == TeslaGatt.CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                listener.onLog(
                    if (status == BluetoothGatt.GATT_SUCCESS) "Notifications enabled"
                    else "Notification setup failed with status $status"
                )
                descriptorWriteDone = true
                maybeReadDeviceName(gatt)
            }
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

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            listener.onLog("setCharacteristicNotification() failed")
            descriptorWriteDone = true
            maybeReadDeviceName(gatt)
            return
        }
        val descriptor = characteristic
            .getDescriptor(TeslaGatt.CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            listener.onLog("CCCD descriptor missing")
            descriptorWriteDone = true
            maybeReadDeviceName(gatt)
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) {
            listener.onLog("writeDescriptor() failed")
            descriptorWriteDone = true
            maybeReadDeviceName(gatt)
        }
    }

    private fun maybeReadDeviceName(gatt: BluetoothGatt) {
        if (descriptorWriteDone && mtuDone && !deviceNameRequested) {
            deviceNameRequested = true
            readGattDeviceName(gatt)
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
        val name = value?.toString(Charsets.UTF_8)?.trim()
        if (!name.isNullOrEmpty()) {
            listener.onLog("Device name: $name")
        }
        listener.onGattDeviceName(name)
        listener.onPhase(ConnectionPhase.READY)
    }

    private fun handleNotification(value: ByteArray?) {
        if (value == null) return
        for (message in framer.feed(value)) {
            listener.onMessage(message)
        }
    }

    companion object {
        private const val MTU_REQUEST = 256
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val MIN_CHUNK_SIZE = 20
        private val GENERIC_ACCESS_SERVICE =
            java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_CHARACTERISTIC =
            java.util.UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    }
}
