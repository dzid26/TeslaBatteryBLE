package com.dzid26.teslable.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.core.protocol.AntiReplayWindow
import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.TeslaCrypto
import com.dzid26.teslable.core.protocol.TeslaSession
import com.dzid26.teslable.core.protocol.TeslaSessionRequests
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
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
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val keyStore = PairingKeyStore(appContext)
    private val knownCarStore = KnownCarStore(appContext)
    private val knownCars = mutableMapOf<String, KnownCar>()
    private val clients = mutableMapOf<String, TeslaGattClient>()
    private val failedAddresses = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var keySlotQueue: List<Int> = emptyList()
    private val clientAddress = TeslaCrypto.randomBytes(16)
    private val sessions = mutableMapOf<String, MutableMap<Domain, TeslaSession>>()
    private val pendingSessions = mutableMapOf<String, PendingSession>()
    private val pendingCommands = mutableMapOf<String, PendingCommand>()
    private var chargeAfterSession = false
    private var sessionRetryAttempts = 0
    private var reconnectAttempts = 0
    private var wakeRefreshAttempts = 0
    private var readChargeAfterWake = false

    private val wakeRefresh = object : Runnable {
        override fun run() {
            val address = _state.value.selectedAddress ?: return
            if (_state.value.connections[address]?.status?.asleep == false) return
            if (wakeRefreshAttempts++ >= WAKE_REFRESH_MAX_ATTEMPTS) {
                readChargeAfterWake = false
                return
            }
            requestVcsecStatus(address)
            handler.postDelayed(this, WAKE_REFRESH_MS)
        }
    }

    private val reconnect = object : Runnable {
        override fun run() {
            val address = _state.value.selectedAddress ?: return
            val phase = _state.value.connections[address]?.phase
            if (phase == ConnectionPhase.READY || phase == ConnectionPhase.CONNECTING) return
            log("${nameFor(address)}: reconnecting")
            connect(address)
        }
    }

    private fun scheduleReconnect(address: String) {
        if (_state.value.selectedAddress != address) return
        if (reconnectAttempts >= RECONNECT_MAX_ATTEMPTS) {
            log("${nameFor(address)}: reconnect stopped after $reconnectAttempts attempts")
            return
        }
        reconnectAttempts++
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, RECONNECT_DELAY_MS)
    }

    private val sessionRetry = object : Runnable {
        override fun run() {
            val address = _state.value.selectedAddress ?: return
            if (_state.value.connections[address]?.phase != ConnectionPhase.READY) return
            val needed = SESSION_DOMAINS.filter { sessions[address]?.containsKey(it) != true }
            if (needed.isEmpty()) return
            if (sessionRetryAttempts++ >= SESSION_MAX_ATTEMPTS) {
                log("${nameFor(address)}: session not established (${needed.joinToString { it.name }})")
                return
            }
            sendSessionRequests(address, needed)
            handler.postDelayed(this, SESSION_RETRY_MS)
        }
    }

    private data class PendingSession(val address: String, val domain: Domain)

    private data class PendingCommand(
        val address: String,
        val domain: Domain,
        val requestId: ByteArray,
        val kind: CommandKind,
        val window: AntiReplayWindow = AntiReplayWindow(),
    )

    private enum class CommandKind { WAKE, CHARGE }

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

    init {
        knownCarStore.load().forEach { knownCars[it.name] = it }
        if (!prefs.getBoolean(KEY_TRACKING_ENABLED, true)) {
            _state.update { it.copy(trackingEnabled = false) }
        }
        val savedVin = prefs.getString(KEY_VIN, "").orEmpty()
        if (savedVin.isNotEmpty()) {
            setVinInput(savedVin)
        }
    }

    fun setVinInput(input: String) {
        val expected = if (input.length == VIN_LENGTH) {
            runCatching { TeslaNames.bleName(input) }.getOrNull()
        } else {
            null
        }
        if (expected != null) {
            prefs.edit().putString(KEY_VIN, input).apply()
        }
        _state.update { it.copy(vinInput = input, expectedBleName = expected) }
    }

    fun startScan() {
        if (!_state.value.trackingEnabled) return
        clients.values.forEach { it.close() }
        clients.clear()
        failedAddresses.clear()
        handler.removeCallbacks(whitelistPoll)
        handler.removeCallbacks(sessionRetry)
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(wakeRefresh)
        keySlotQueue = emptyList()
        sessions.clear()
        pendingSessions.clear()
        pendingCommands.clear()
        chargeAfterSession = false
        readChargeAfterWake = false
        sessionRetryAttempts = 0
        reconnectAttempts = 0
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
        reconnectAttempts = 0
        _state.update { it.copy(selectedAddress = address) }
        when (_state.value.connections[address]?.phase) {
            ConnectionPhase.READY -> {
                requestVcsecStatus(address)
                requestKeySlot(address)
                startSession(address)
                stopScan()
            }

            ConnectionPhase.FAILED, ConnectionPhase.DISCONNECTED, ConnectionPhase.IDLE, null ->
                connect(address)

            else -> Unit
        }
    }

    /**
     * Reconnects to a remembered car when nothing is connected: starts a scan so
     * the paired car is discovered and auto-connected again.
     */
    fun ensureConnected() {
        if (!_state.value.trackingEnabled) return
        if (knownCars.isEmpty() || !hasBlePermissions(appContext)) return
        if (_state.value.scanning) return
        if (_state.value.connections.values.any { it.phase in ACTIVE_PHASES }) return
        startScan()
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

    /** Master switch: off tears everything down, on reconnects to paired cars. */
    fun setTrackingEnabled(enabled: Boolean) {
        if (_state.value.trackingEnabled == enabled) return
        prefs.edit().putBoolean(KEY_TRACKING_ENABLED, enabled).apply()
        _state.update { it.copy(trackingEnabled = enabled) }
        if (enabled) {
            log("Background tracking enabled")
            ensureConnected()
        } else {
            disableTracking()
        }
    }

    private fun disableTracking() {
        BleTrackingService.stop(appContext)
        scanner.stop()
        clients.values.forEach { it.close() }
        clients.clear()
        failedAddresses.clear()
        handler.removeCallbacks(pairingTimeout)
        handler.removeCallbacks(whitelistPoll)
        handler.removeCallbacks(sessionRetry)
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(wakeRefresh)
        keySlotQueue = emptyList()
        sessions.clear()
        pendingSessions.clear()
        pendingCommands.clear()
        chargeAfterSession = false
        readChargeAfterWake = false
        sessionRetryAttempts = 0
        reconnectAttempts = 0
        wakeRefreshAttempts = 0
        whitelistPollAttempts = 0
        _state.update {
            it.copy(
                scanning = false,
                devices = emptyList(),
                connections = emptyMap(),
                selectedAddress = null,
                pairingPhase = PairingPhase.IDLE,
                log = emptyList(),
            )
        }
        log("Background tracking disabled")
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
        val existing = _state.value.connections[address]
        _state.update {
            it.copy(
                connections = it.connections + (
                    address to TeslaConnection(
                        address = address,
                        name = nameFor(address),
                        phase = ConnectionPhase.CONNECTING,
                        gattDeviceName = existing?.gattDeviceName,
                        services = existing?.services ?: emptyList(),
                        mtu = existing?.mtu,
                        status = existing?.status,
                        keySlot = existing?.keySlot,
                        sessions = existing?.sessions ?: emptyList(),
                        charge = existing?.charge,
                    )
                    )
            )
        }
        val client = TeslaGattClient(appContext, listenerFor(address))
        clients[address] = client
        client.connect(device)
    }

    private fun onDevicesFound(devices: List<TeslaAdvert>) {
        val selection = _state.value.selectedAddress
        var movedSelection: String? = null
        val strongestByName = devices.groupBy { it.name }
            .mapValues { (_, found) -> found.maxBy { it.rssi } }
        for ((name, device) in strongestByName) {
            val known = knownCars[name] ?: continue
            if (known.address == device.address) continue
            // The car is advertising from a new address; follow it.
            rememberCar(name, device.address, known.gattName)
            if (selection == known.address) {
                movedSelection = device.address
            }
        }
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
            state.copy(
                devices = visibleDevices(devices, connections),
                connections = connections,
                selectedAddress = movedSelection ?: state.selectedAddress,
            )
        }
        autoConnect()
    }

    /**
     * Shows a single row: the connected car if there is one, otherwise the
     * remembered car, otherwise the strongest advertisement.
     */
    private fun visibleDevices(
        devices: List<TeslaAdvert>,
        connections: Map<String, TeslaConnection>,
    ): List<TeslaAdvert> {
        if (devices.isEmpty()) return emptyList()
        val active = devices.filter { connections[it.address]?.phase in ACTIVE_PHASES }
        if (active.isNotEmpty()) return listOf(active.maxBy { it.rssi })
        val known = devices.filter { knownCars.containsKey(it.name) }
        return listOf((known.ifEmpty { devices }).maxBy { it.rssi })
    }

    private fun autoConnect() {
        if (!_state.value.trackingEnabled) return
        val state = _state.value
        val target = state.devices.firstOrNull() ?: return
        if (!knownCars.containsKey(target.name)) return
        if (state.selectedAddress != null && state.selectedAddress != target.address) return
        if (target.address in clients || target.address in failedAddresses) return
        if (state.selectedAddress == null) {
            _state.update { it.copy(selectedAddress = target.address) }
        }
        log("${nameFor(target.address)}: reconnecting to paired car")
        connect(target.address)
    }

    private fun rememberCar(address: String) {
        val connection = _state.value.connections[address] ?: return
        rememberCar(connection.name, address, connection.gattDeviceName)
    }

    private fun rememberCar(name: String, address: String, gattName: String?) {
        if (!TeslaNames.isTeslaBleName(name)) return
        val car = KnownCar(address = address, name = name, gattName = gattName)
        if (knownCars[name] == car) return
        knownCars[name] = car
        knownCarStore.save(knownCars.values.toList())
        log("${nameFor(address)}: remembered for reconnect")
    }

    private fun listenerFor(address: String) = object : TeslaGattClient.Listener {
        override fun onPhase(phase: ConnectionPhase) {
            if (phase == ConnectionPhase.FAILED || phase == ConnectionPhase.DISCONNECTED) {
                failedAddresses.add(address)
                clients.remove(address)?.close()
                val hadData = _state.value.connections[address]?.let {
                    it.sessions.isNotEmpty() || it.status != null || it.charge != null
                } == true
                val display = if (hadData) ConnectionPhase.DISCONNECTED else ConnectionPhase.FAILED
                updateConnection(address) { it.copy(phase = display) }
                scheduleReconnect(address)
                return
            }
            updateConnection(address) { it.copy(phase = phase) }
            if (phase == ConnectionPhase.READY) {
                reconnectAttempts = 0
                if (_state.value.selectedAddress == address) {
                    requestVcsecStatus(address)
                    requestKeySlot(address)
                    startSession(address)
                    stopScan()
                }
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
            if (handleSessionInfo(address, message)) return
            if (handleEncryptedResponse(address, message)) return

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
                if (!status.asleep) {
                    handler.removeCallbacks(wakeRefresh)
                }
                updateConnection(address) { it.copy(status = status) }
                log(
                    "${nameFor(address)}: VCSEC status locked=${status.locked} " +
                        "asleep=${status.asleep} userPresent=${status.userPresent}"
                )
                if (!status.asleep && readChargeAfterWake) {
                    readChargeAfterWake = false
                    requestChargeState()
                }
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

    private fun startSession(address: String) {
        val keyPair = keyStore.load()
        if (keyPair == null) {
            log("${nameFor(address)}: no pairing key yet")
            return
        }
        val vin = _state.value.vinInput
        if (vin.length != VIN_LENGTH) {
            log("${nameFor(address)}: enter your VIN to establish a session")
            return
        }
        sessionRetryAttempts = 0
        sendSessionRequests(
            address,
            SESSION_DOMAINS.filter { sessions[address]?.containsKey(it) != true },
        )
        handler.removeCallbacks(sessionRetry)
        handler.postDelayed(sessionRetry, SESSION_RETRY_MS)
    }

    private fun sendSessionRequests(address: String, domains: List<Domain>) {
        val keyPair = keyStore.load() ?: return
        for (domain in domains) {
            val uuid = TeslaCrypto.randomBytes(16)
            val routing = if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                TeslaCrypto.randomBytes(16)
            } else {
                clientAddress
            }
            pendingSessions[uuid.toHex()] = PendingSession(address, domain)
            val request = TeslaSessionRequests.buildSessionInfoRequest(
                domain = domain,
                publicKeyRaw = keyPair.publicKeyRaw,
                routingAddress = routing,
                uuid = uuid,
            )
            log("${nameFor(address)}: session request ${domain.name}")
            if (clients[address]?.send(request) != true) {
                log("${nameFor(address)}: failed to send session request")
            }
        }
    }

    private fun handleSessionInfo(address: String, bytes: ByteArray): Boolean {
        val message = runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
            ?: return false
        val encodedInfo = message.session_info?.toByteArray() ?: return false
        val challenge = message.request_uuid.toByteArray()
        val pending = pendingSessions.remove(challenge.toHex()) ?: return false
        val tag = message.signature_data?.session_info_tag?.tag?.toByteArray()
        if (tag == null) {
            log("${nameFor(address)}: session info missing tag")
            return true
        }
        val keyPair = keyStore.load()
        val vin = _state.value.vinInput
        val session = if (keyPair != null && vin.length == VIN_LENGTH) {
            TeslaSession.import(
                privateKeyPkcs8 = keyPair.privateKeyPkcs8,
                publicKeyRaw = keyPair.publicKeyRaw,
                vin = vin,
                challenge = challenge,
                encodedInfo = encodedInfo,
                tag = tag,
            )
        } else {
            null
        }
        if (session == null) {
            log("${nameFor(address)}: session verification failed (${pending.domain.name})")
            return true
        }
        sessions.getOrPut(address) { mutableMapOf() }[pending.domain] = session
        updateConnection(address) {
            it.copy(
                sessions = sessions[address]?.keys?.map { domain -> domain.name }?.sorted()
                    ?: emptyList(),
            )
        }
        log("${nameFor(address)}: session established (${pending.domain.name})")
        rememberCar(address)
        if (SESSION_DOMAINS.all { sessions[address]?.containsKey(it) == true }) {
            handler.removeCallbacks(sessionRetry)
        }
        if (pending.domain == Domain.DOMAIN_INFOTAINMENT && chargeAfterSession) {
            chargeAfterSession = false
            requestChargeState()
        }
        return true
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
            rememberCar(address)
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

    fun wakeVehicle() {
        val address = _state.value.selectedAddress ?: return
        if (_state.value.connections[address]?.status?.asleep == false) {
            log("${nameFor(address)}: car is already awake")
            return
        }
        if (sessions[address]?.containsKey(Domain.DOMAIN_VEHICLE_SECURITY) != true) {
            log("${nameFor(address)}: no VCSEC session to wake with")
            return
        }
        sendAuthenticated(
            address = address,
            domain = Domain.DOMAIN_VEHICLE_SECURITY,
            payload = TeslaCommands.buildWakeRequest(),
            kind = CommandKind.WAKE,
        )
        wakeRefreshAttempts = 0
        readChargeAfterWake = true
        handler.removeCallbacks(wakeRefresh)
        handler.postDelayed(wakeRefresh, WAKE_REFRESH_MS)
    }

    fun requestChargeState() {
        val address = _state.value.selectedAddress ?: return
        if (_state.value.connections[address]?.status?.asleep != false) {
            log("${nameFor(address)}: car is asleep; wake it first")
            return
        }
        if (sessions[address]?.containsKey(Domain.DOMAIN_INFOTAINMENT) == true) {
            sendAuthenticated(
                address = address,
                domain = Domain.DOMAIN_INFOTAINMENT,
                payload = TeslaCommands.buildChargeStateRequest(),
                kind = CommandKind.CHARGE,
            )
        } else {
            chargeAfterSession = true
            log("${nameFor(address)}: requesting Infotainment session for SOC")
            startSession(address)
        }
    }

    private fun sendAuthenticated(
        address: String,
        domain: Domain,
        payload: ByteArray,
        kind: CommandKind,
    ): Boolean {
        val session = sessions[address]?.get(domain) ?: run {
            log("${nameFor(address)}: no session for ${domain.name}")
            return false
        }
        val uuid = TeslaCrypto.randomBytes(16)
        val routing = if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
            TeslaCrypto.randomBytes(16)
        } else {
            clientAddress
        }
        val message = TeslaSessionRequests.buildAuthenticatedRequest(domain, payload, routing, uuid)
        val encrypted = session.encrypt(message, COMMAND_EXPIRES_SECONDS) ?: run {
            log("${nameFor(address)}: encryption failed")
            return false
        }
        val requestId = session.requestId(encrypted) ?: return false
        pendingCommands[uuid.toHex()] = PendingCommand(address, domain, requestId, kind)
        log("${nameFor(address)}: TX ${kind.name.lowercase()} (${encrypted.protobuf_message_as_bytes?.size ?: 0} bytes)")
        return clients[address]?.send(encrypted.encode()) == true
    }

    private fun handleEncryptedResponse(address: String, bytes: ByteArray): Boolean {
        val message = runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
            ?: return false
        if (message.signature_data?.AES_GCM_Response_data == null) return false
        val pending = pendingCommands.remove(message.request_uuid.toByteArray().toHex()) ?: return false
        val session = sessions[pending.address]?.get(pending.domain) ?: return true
        val plaintext = session.decrypt(message, pending.requestId, pending.window)
        if (plaintext == null) {
            log("${nameFor(pending.address)}: response decryption failed (${pending.kind.name.lowercase()})")
            return true
        }
        when (pending.kind) {
            CommandKind.WAKE -> {
                val status = runCatching { TeslaVcsec.parseCommandStatus(plaintext) }.getOrNull()
                log("${nameFor(pending.address)}: wake ${status?.name ?: "response received"}")
            }

            CommandKind.CHARGE -> {
                val charge = runCatching { TeslaCommands.parseChargeState(plaintext) }.getOrNull()
                if (charge != null) {
                    updateConnection(pending.address) { it.copy(charge = charge) }
                    log(
                        "${nameFor(pending.address)}: SOC ${charge.batteryLevel}% " +
                            "(${charge.chargingState ?: "unknown"})"
                    )
                } else {
                    val status = runCatching { TeslaCommands.parseActionStatus(plaintext) }.getOrNull()
                    log(
                        "${nameFor(pending.address)}: charge response missing data" +
                            (status?.let { " ($it)" } ?: "")
                    )
                }
            }
        }
        return true
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
        const val COMMAND_EXPIRES_SECONDS = 5
        const val SESSION_RETRY_MS = 3000L
        const val SESSION_MAX_ATTEMPTS = 20
        const val RECONNECT_DELAY_MS = 5000L
        const val RECONNECT_MAX_ATTEMPTS = 12
        const val WAKE_REFRESH_MS = 5000L
        const val WAKE_REFRESH_MAX_ATTEMPTS = 6
        const val PREFS = "teslable"
        const val KEY_VIN = "vin"
        const val KEY_TRACKING_ENABLED = "tracking_enabled"
        val ACTIVE_PHASES = setOf(
            ConnectionPhase.CONNECTING,
            ConnectionPhase.CONNECTED,
            ConnectionPhase.DISCOVERING,
            ConnectionPhase.READY,
        )
        val SESSION_DOMAINS = listOf(
            Domain.DOMAIN_VEHICLE_SECURITY,
            Domain.DOMAIN_INFOTAINMENT,
        )
    }
}
