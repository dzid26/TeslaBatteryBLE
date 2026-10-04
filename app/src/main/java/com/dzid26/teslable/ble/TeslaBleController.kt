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

    private val clients = mutableMapOf<String, TeslaGattClient>()
    private val failedAddresses = mutableSetOf<String>()

    private val scanner = TeslaScanner(
        context = appContext,
        onDevices = ::onDevicesFound,
        onAdvertisementSeen = { count -> _state.update { it.copy(advertisementsSeen = count) } },
        onLog = ::log,
    )

    fun setVinInput(input: String) {
        val expected = if (input.length == VIN_LENGTH) {
            runCatching { TeslaNames.bleName(input) }.getOrNull()
        } else {
            null
        }
        _state.update { it.copy(vinInput = input, expectedBleName = expected) }
    }

    fun startScan() {
        clients.values.forEach { it.close() }
        clients.clear()
        failedAddresses.clear()
        _state.update {
            it.copy(
                scanning = true,
                advertisementsSeen = 0,
                devices = emptyList(),
                connections = emptyMap(),
                log = emptyList(),
            )
        }
        scanner.start()
    }

    fun stopScan() {
        scanner.stop()
        _state.update { it.copy(scanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = appContext
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
            ?.getRemoteDevice(address)
        if (device == null) {
            log("Could not resolve $address")
            return
        }
        clients.remove(address)?.close()
        failedAddresses.remove(address)
        _state.update {
            it.copy(
                connections = it.connections + (
                    address to TeslaConnection(
                        address = address,
                        name = nameFor(address),
                        phase = ConnectionPhase.CONNECTING,
                    )
                    )
            )
        }
        val client = TeslaGattClient(appContext, listenerFor(address))
        clients[address] = client
        client.connect(device)
    }

    fun close() {
        scanner.stop()
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private fun onDevicesFound(devices: List<TeslaAdvert>) {
        _state.update { state ->
            val connections = state.connections.toMutableMap()
            for (device in devices) {
                val existing = connections[device.address]
                if (existing == null) {
                    connections[device.address] = TeslaConnection(
                        address = device.address,
                        name = device.name,
                    )
                } else if (existing.name != device.name) {
                    connections[device.address] = existing.copy(name = device.name)
                }
            }
            state.copy(devices = devices, connections = connections)
        }
        devices
            .map { it.address }
            .filter { it !in clients && it !in failedAddresses }
            .forEach(::connect)
    }

    private fun listenerFor(address: String) = object : TeslaGattClient.Listener {
        override fun onPhase(phase: ConnectionPhase) {
            if (phase == ConnectionPhase.FAILED) {
                failedAddresses.add(address)
            }
            if (phase == ConnectionPhase.DISCONNECTED) {
                clients.remove(address)?.close()
            }
            updateConnection(address) { it.copy(phase = phase) }
        }

        override fun onServices(services: List<GattServiceInfo>) {
            updateConnection(address) { it.copy(services = services) }
        }

        override fun onGattDeviceName(name: String?) {
            updateConnection(address) { it.copy(gattDeviceName = name) }
        }

        override fun onMtu(mtu: Int) {
            updateConnection(address) { it.copy(mtu = mtu) }
        }

        override fun onLog(message: String) {
            log("${nameFor(address)}: $message")
        }
    }

    private fun updateConnection(address: String, transform: (TeslaConnection) -> TeslaConnection) {
        _state.update { state ->
            val connection = state.connections[address] ?: return@update state
            state.copy(connections = state.connections + (address to transform(connection)))
        }
    }

    private fun nameFor(address: String): String {
        val connection = _state.value.connections[address]
        return connection?.gattDeviceName
            ?: connection?.name
            ?: _state.value.devices.firstOrNull { it.address == address }?.name
            ?: address
    }

    private fun log(message: String) {
        _state.update { it.copy(log = (it.log + message).takeLast(LOG_MAX_LINES)) }
    }

    private companion object {
        const val VIN_LENGTH = 17
        const val LOG_MAX_LINES = 200
    }
}
