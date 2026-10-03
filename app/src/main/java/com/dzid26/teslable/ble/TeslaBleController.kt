package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import com.dzid26.teslable.core.TeslaNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TeslaBleController(context: Context) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val scanner = TeslaScanner(
        context = appContext,
        onDevices = { devices -> _state.update { it.copy(devices = devices) } },
        onLog = ::log,
    )

    private val gattListener = object : TeslaGattClient.Listener {
        override fun onPhase(phase: ConnectionPhase) {
            _state.update { it.copy(phase = phase) }
        }

        override fun onServices(services: List<GattServiceInfo>) {
            _state.update { it.copy(services = services) }
        }

        override fun onGattDeviceName(name: String?) {
            _state.update { it.copy(gattDeviceName = name) }
        }

        override fun onMtu(mtu: Int) {
            _state.update { it.copy(mtu = mtu) }
        }

        override fun onLog(message: String) {
            log(message)
        }
    }

    private val gattClient = TeslaGattClient(appContext, gattListener)

    fun setVinInput(input: String) {
        val expected = if (input.length == VIN_LENGTH) {
            runCatching { TeslaNames.bleName(input) }.getOrNull()
        } else {
            null
        }
        _state.update { it.copy(vinInput = input, expectedBleName = expected) }
    }

    fun startScan() {
        _state.update { it.copy(scanning = true, devices = emptyList(), log = emptyList()) }
        scanner.start()
    }

    fun stopScan() {
        scanner.stop()
        _state.update { it.copy(scanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        stopScan()
        val device = appContext
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.getRemoteDevice(address)
        if (device == null) {
            log("Could not resolve device $address")
            return
        }
        val advertisedName = _state.value.devices.firstOrNull { it.address == address }?.name
        _state.update {
            it.copy(
                phase = ConnectionPhase.CONNECTING,
                connectedAddress = address,
                connectedAdvertisedName = advertisedName,
                gattDeviceName = null,
                services = emptyList(),
                mtu = null,
            )
        }
        gattClient.connect(device)
    }

    fun disconnect() {
        gattClient.close()
        _state.update {
            it.copy(
                phase = ConnectionPhase.IDLE,
                connectedAddress = null,
                connectedAdvertisedName = null,
                gattDeviceName = null,
                services = emptyList(),
                mtu = null,
            )
        }
    }

    fun close() {
        scanner.stop()
        gattClient.close()
    }

    private fun log(message: String) {
        _state.update { it.copy(log = (it.log + message).takeLast(LOG_MAX_LINES)) }
    }

    private companion object {
        const val VIN_LENGTH = 17
        const val LOG_MAX_LINES = 200
    }
}
