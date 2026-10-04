package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import com.dzid26.teslable.core.TeslaNames
import com.dzid26.teslable.core.protocol.TeslaPairing
import com.dzid26.teslable.core.protocol.TeslaVcsec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TeslaBleController(context: Context) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val keyStore = PairingKeyStore(appContext)
    private val clients = mutableMapOf<String, TeslaGattClient>()
    private val failedAddresses = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var keySlotQueue: List<Int> = emptyList()

    private val pairingTimeout = Runnable {
        if (_state.value.pairingPhase == PairingPhase.SENDING) {
            _state.update { it.copy(pairingPhase = PairingPhase.WAITING_FOR_CARD) }
            log("No pairing response; tap the card and confirm on the car screen")
        }
    }

    private var whitelistPollAttempts = 0
    private val whitelistPoll = object : Runnable {
        override fun run() {
            val address = _state.value.selectedAddress ?: return
            val phase = _state.value.pairingPhase
            if (phase == PairingPhase.OK || phase == PairingPhase.ERROR) return
            if (whitelistPollAttempts++ >= WHITELIST_MAX_ATTEMPTS) {
                log("Key not enrolled yet; tap the card and retry if needed")
                return
            }
            clients[address]?.send(TeslaVcsec.buildWhitelistInfoRequest())
            handler.postDelayed(this, WHITELIST_POLL_MS)
        }
    }

    private val scanner = TeslaScanner(
        context = appContext,
        onDevices = ::onDevicesFound,
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
        handler.removeCallbacks(whitelistPoll)
        keySlotQueue = emptyList()
        _state.update {
            it.copy(
                scanning = true,
                devices = emptyList(),
                connections = emptyMap(),
                selectedAddress = null,
                pairingPhase = PairingPhase.IDLE,
                log = emptyList(),
            )
        }
        scanner.start()
    }

    fun stopScan() {
        scanner.stop()
        _state.update { it.copy(scanning = false) }
    }

    fun onTeslaClicked(address: String) {
        _state.update { it.copy(selectedAddress = address) }
        when (_state.value.connections[address]?.phase) {
            ConnectionPhase.READY -> {
                requestVcsecStatus(address)
                requestKeySlot(address)
            }

            ConnectionPhase.FAILED, ConnectionPhase.DISCONNECTED, null -> connect(address)
            else -> Unit
        }
    }

    fun pairKey() {
        val address = _state.value.selectedAddress
        val connection = address?.let { _state.value.connections[it] }
        if (address == null || connection?.phase != ConnectionPhase.READY) {
            log("No selected car ready for pairing")
            return
        }
        val keyPair = keyStore.loadOrCreate()
        val keyId = keyPair.keyId.toHex()
        _state.update { it.copy(pairingPhase = PairingPhase.SENDING, pairingKeyId = keyId) }
        log("${nameFor(address)}: pairing key $keyId")
        val request = TeslaPairing.buildAddKeyRequest(keyPair.publicKeyRaw)
        if (clients[address]?.send(request) != true) {
            _state.update { it.copy(pairingPhase = PairingPhase.ERROR) }
            log("${nameFor(address)}: failed to send pairing request")
        } else {
            handler.removeCallbacks(pairingTimeout)
            handler.postDelayed(pairingTimeout, PAIRING_TIMEOUT_MS)
            whitelistPollAttempts = 0
            handler.removeCallbacks(whitelistPoll)
            handler.postDelayed(whitelistPoll, WHITELIST_POLL_MS)
        }
    }

    fun close() {
        scanner.stop()
        clients.values.forEach { it.close() }
        clients.clear()
        handler.removeCallbacks(pairingTimeout)
        handler.removeCallbacks(whitelistPoll)
        keySlotQueue = emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun connect(address: String) {
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
            if (phase == ConnectionPhase.READY && _state.value.selectedAddress == address) {
                requestVcsecStatus(address)
                requestKeySlot(address)
                stopScan()
            }
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

        override fun onMessage(message: ByteArray) {
            val pairing = runCatching { TeslaPairing.parseAddKeyResponse(message) }.getOrNull()
            if (pairing != null) {
                handler.removeCallbacks(pairingTimeout)
                val phase = when (pairing) {
                    TeslaPairing.Result.OK -> PairingPhase.OK
                    TeslaPairing.Result.WAITING_FOR_CARD -> PairingPhase.WAITING_FOR_CARD
                    TeslaPairing.Result.ERROR -> PairingPhase.ERROR
                }
                _state.update { it.copy(pairingPhase = phase) }
                log("${nameFor(address)}: pairing ${pairing.name.lowercase()}")
                return
            }

            val whitelist = runCatching { TeslaVcsec.parseWhitelistInfoResponse(message) }.getOrNull()
            if (whitelist != null) {
                handleWhitelistInfo(address, whitelist)
                return
            }

            val entry = runCatching { TeslaVcsec.parseWhitelistEntryResponse(message) }.getOrNull()
            if (entry != null) {
                handleWhitelistEntry(address, entry)
                return
            }

            val status = runCatching { TeslaVcsec.parseStatusResponse(message) }.getOrNull()
            if (status != null) {
                updateConnection(address) { it.copy(status = status) }
                log(
                    "${nameFor(address)}: VCSEC status locked=${status.locked} " +
                        "asleep=${status.asleep} userPresent=${status.userPresent}"
                )
            } else {
                log("${nameFor(address)}: RX ${message.size} bytes ${message.toHex()}")
            }
        }

        override fun onLog(message: String) {
            log("${nameFor(address)}: $message")
        }
    }

    private fun requestVcsecStatus(address: String) {
        val request = TeslaVcsec.buildStatusRequest()
        log("${nameFor(address)}: TX ${request.size} bytes ${request.toHex()}")
        if (clients[address]?.send(request) != true) {
            log("${nameFor(address)}: failed to send VCSEC status request")
        }
    }

    private fun handleWhitelistInfo(address: String, whitelist: WhitelistInfo) {
        val stored = keyStore.load()
        val keyId = stored?.keyId?.toHex()
        val enrolled = stored != null && whitelist.whitelistEntries.any {
            it.publicKeySHA1.toByteArray().copyOf(stored.keyId.size).contentEquals(stored.keyId)
        }
        if (stored != null && enrolled && keyId != null) {
            val wasEnrolled = _state.value.pairingPhase == PairingPhase.OK
            handler.removeCallbacks(whitelistPoll)
            _state.update { it.copy(pairingPhase = PairingPhase.OK, pairingKeyId = keyId) }
            if (!wasEnrolled) {
                log("${nameFor(address)}: key enrolled (${whitelist.numberOfEntries} keys)")
            }
            val index = whitelist.whitelistEntries.indexOfFirst {
                it.publicKeySHA1.toByteArray().copyOf(stored.keyId.size).contentEquals(stored.keyId)
            }
            val slots = occupiedSlots(whitelist.slotMask)
            keySlotQueue = if (index in slots.indices) {
                listOf(slots[index]) + slots.filterIndexed { i, _ -> i != index }
            } else {
                slots
            }
            requestNextKeySlot(address)
        } else if (whitelistPollAttempts == 1) {
            log("${nameFor(address)}: whitelist has ${whitelist.numberOfEntries} keys")
        }
    }

    private fun handleWhitelistEntry(address: String, entry: WhitelistEntryInfo) {
        val stored = keyStore.load()
        val matches = stored != null && (
            entry.publicKey?.PublicKeyRaw?.toByteArray()?.contentEquals(stored.publicKeyRaw) == true ||
                entry.keyId?.publicKeySHA1?.toByteArray()
                    ?.copyOf(stored.keyId.size)
                    ?.contentEquals(stored.keyId) == true
            )
        if (matches) {
            keySlotQueue = emptyList()
            updateConnection(address) { it.copy(keySlot = entry.slot) }
            log("${nameFor(address)}: key slot ${entry.slot}")
        } else {
            requestNextKeySlot(address)
        }
    }

    private fun requestKeySlot(address: String) {
        if (_state.value.connections[address]?.keySlot != null) return
        if (keyStore.load() == null) return
        clients[address]?.send(TeslaVcsec.buildWhitelistInfoRequest())
    }

    private fun requestNextKeySlot(address: String) {
        val slot = keySlotQueue.firstOrNull()
        if (slot == null) {
            log("${nameFor(address)}: could not locate our key slot")
            return
        }
        keySlotQueue = keySlotQueue.drop(1)
        clients[address]?.send(TeslaVcsec.buildWhitelistEntryRequest(slot))
    }

    private fun occupiedSlots(slotMask: Int): List<Int> =
        (0 until Int.SIZE_BITS).filter { (slotMask ushr it) and 1 == 1 }

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

    private fun ByteArray.toHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun log(message: String) {
        _state.update { it.copy(log = (it.log + message).takeLast(LOG_MAX_LINES)) }
    }

    private companion object {
        const val VIN_LENGTH = 17
        const val LOG_MAX_LINES = 200
        const val PAIRING_TIMEOUT_MS = 5000L
        const val WHITELIST_POLL_MS = 2000L
        const val WHITELIST_MAX_ATTEMPTS = 30
    }
}
