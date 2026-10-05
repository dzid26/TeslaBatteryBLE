// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.core.TeslaNames
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.protocol.AntiReplayWindow
import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.TeslaCrypto
import com.dzid26.teslable.core.protocol.TeslaPairing
import com.dzid26.teslable.core.protocol.TeslaSession
import com.dzid26.teslable.core.protocol.TeslaSessionRequests
import com.dzid26.teslable.core.protocol.TeslaVcsec
import com.dzid26.teslable.history.BatteryHistoryStore
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns every vehicle the app knows and one [VehicleLink] per car. Scanning,
 * discovery and selection live here; connecting, sessions, pairing, polling and
 * commands live in the link, so one car failing never touches another.
 */
class TeslaBleController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val keyStore = PairingKeyStore(appContext)
    private val vehicleStore = VehicleStore(appContext)
    private val vehicles = mutableMapOf<String, Vehicle>()
    private val historyStore: BatteryHistoryStore

    /** Battery readings recorded from every charge response, oldest first. */
    val batteryHistory: StateFlow<List<BatterySample>> get() = historyStore.samples

    private val links = mutableMapOf<String, VehicleLink>()
    private val handler = Handler(Looper.getMainLooper())
    private val clientAddress = TeslaCrypto.randomBytes(16)
    private var selectedBleName: String? = null
    private var uiVisible = false

    private val scanner =
        TeslaScanner(
            context = appContext,
            onDevices = ::onDevicesFound,
            onLog = ::log,
        )

    private fun demoCar(): DemoMode.Car? = if (DemoMode.isEnabled()) DemoMode.car else null

    private fun createTransport(
        address: String,
        listener: TeslaTransport.Listener,
    ): TeslaTransport =
        demoCar()?.createTransport(address, listener)
            ?: TeslaGattClient(appContext, listener)

    /** Fake adverts in demo mode; the real BLE scanner otherwise. */
    private val demoScan =
        object : Runnable {
            override fun run() {
                onDevicesFound(demoCar()?.adverts() ?: emptyList())
                if (_state.value.scanning || _state.value.discovering) {
                    handler.postDelayed(this, DEMO_SCAN_MS)
                }
            }
        }

    /** Silent discovery is a recovery path, not a permanent scan. */
    private val stopDiscovery =
        Runnable {
            if (_state.value.discovering) {
                stopScanner()
                _state.update { it.copy(discovering = false) }
            }
        }

    init {
        vehicleStore.load().forEach { vehicles[it.bleName] = it }
        historyStore = BatteryHistoryStore(appContext)
        if (!prefs.getBoolean(KEY_TRACKING_ENABLED, true)) {
            _state.update { it.copy(trackingEnabled = false) }
        }
        if (DemoMode.isEnabled()) {
            // The simulated car carries a fixed demo VIN; give it a home once.
            val demoName = runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull()
            if (demoName != null && vehicles[demoName]?.vin == null) {
                updateVehicle(demoName) { it.copy(vin = DemoMode.DEMO_VIN) }
            }
        }
        publishVehicles()
        val saved = prefs.getString(KEY_SELECTED_VEHICLE, null)
        if (saved != null && vehicles.containsKey(saved)) {
            // The last car the owner opened is the default on launch.
            selectVehicle(saved)
        }
    }

    // ---------------------------------------------------------------- UI actions

    fun setVinInput(input: String) {
        val normalized = Vehicle.normalizeVin(input)
        val expected =
            if (normalized.length == Vehicle.VIN_LENGTH) {
                bleNameOf(normalized)
            } else {
                null
            }
        val vehicle = selectedVehicle()
        if (vehicle != null && expected != null) {
            if (vehicle.acceptsVin(normalized)) {
                updateVehicle(vehicle.bleName) { it.copy(vin = normalized) }
                log("${vehicle.title}: VIN saved")
                // A VIN unlocks the sessions; retry the handshake now.
                selectedLink()?.takeIf { it.phase == ConnectionPhase.READY }?.startSession()
            } else {
                log("${vehicle.title}: VIN does not match this car's advertised name")
            }
        } else if (vehicle == null && expected != null) {
            // The selected car is not remembered yet; remember it so the VIN
            // has somewhere to live, then save it if it checks out.
            val link = selectedLink()
            if (link != null) {
                rememberVehicle(link.bleName, link.address, link.gattName())
                val remembered = vehicles[link.bleName]
                if (remembered != null && remembered.acceptsVin(normalized)) {
                    updateVehicle(remembered.bleName) { it.copy(vin = normalized) }
                }
            }
        }
        _state.update { it.copy(vinInput = input, expectedBleName = expected) }
    }

    fun startScan() {
        if (!_state.value.trackingEnabled) return
        // Additive scan: keep the current connections and look for more cars.
        links.values.forEach {
            it.reconnectAttempts = 0
            it.nextRetryAtMs = 0
        }
        _state.update {
            it.copy(
                scanning = true,
                discovering = false,
                explicitScan = true,
            )
        }
        startScanner()
    }

    fun stopScan() {
        handler.removeCallbacks(stopDiscovery)
        stopScanner()
        _state.update { it.copy(scanning = false, discovering = false) }
    }

    /** The UI opened a car by its current address. */
    fun openVehicle(address: String) {
        val name =
            _state.value.connections[address]?.name
                ?: _state.value.devices.firstOrNull { it.address == address }?.name
                ?: vehicles.values.firstOrNull { it.address == address }?.bleName
                ?: return
        openVehicle(name, address)
    }

    /** A notification tap asked for a specific car, by its stable advertised name. */
    fun openVehicleByBleName(bleName: String) {
        val vehicle = vehicles[bleName] ?: return
        openVehicle(bleName, vehicle.address)
    }

    /** Clears the one-shot notification open request once the UI showed it. */
    fun consumeOpenVehicleRequest() {
        _state.update { it.copy(openVehicleRequest = null) }
    }

    private fun openVehicle(
        bleName: String,
        address: String,
    ) {
        selectVehicle(bleName, persist = true)
        _state.update { it.copy(openVehicleRequest = bleName) }
        val link = links[bleName]
        when (link?.phase) {
            ConnectionPhase.READY -> {
                link.requestStatus()
                link.requestKeySlot()
                link.startSession()
                stopScan()
                link.startRssiPoll()
                link.postPoll()
            }

            ConnectionPhase.CONNECTING -> Unit

            else -> ensureLink(bleName, address).connect()
        }
    }

    /**
     * Reconnects every known car when nothing is connected (app open, tracking
     * on, service start). Direct connects first; discovery takes over when a
     * car stopped advertising or moved to a new address. The scan button stays
     * a manual "find new cars" action.
     */
    fun ensureConnected() {
        if (!_state.value.trackingEnabled) return
        if (!hasBlePermissions(appContext)) return
        if (_state.value.scanning) return
        val ordered = orderedVehicles()
        var active = links.values.count { it.phase in ACTIVE_PHASES }
        for (vehicle in ordered) {
            if (active >= MAX_ACTIVE_LINKS) break
            val link = links[vehicle.bleName]
            if (link != null && link.phase in ACTIVE_PHASES) continue
            if (vehicle.address.isEmpty()) continue
            log("${vehicle.title}: reconnecting to paired car")
            ensureLink(vehicle.bleName, vehicle.address).connect()
            active++
        }
        if (_state.value.devices.isEmpty() && ordered.isNotEmpty()) {
            _state.update {
                it.copy(devices = ordered.map { vehicle -> TeslaAdvert(vehicle.bleName, vehicle.address) })
            }
        }
    }

    fun pairKey(address: String) {
        val link =
            linkForAddress(address) ?: run {
                log("No selected car ready for pairing")
                return
            }
        selectVehicle(link.bleName)
        link.pairKey()
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
        handler.removeCallbacks(stopDiscovery)
        stopScanner()
        links.values.forEach { it.close() }
        links.clear()
        handler.removeCallbacksAndMessages(null)
        selectedBleName = null
        _state.update {
            it.copy(
                scanning = false,
                discovering = false,
                explicitScan = false,
                devices = emptyList(),
                connections = emptyMap(),
                selectedBleName = null,
                vinInput = "",
                expectedBleName = null,
                log = emptyList(),
            )
        }
        log("Background tracking disabled")
    }

    fun wakeVehicle(bleName: String? = null) {
        val link = (bleName ?: selectedBleName)?.let(links::get) ?: return
        link.wake()
    }

    fun requestChargeState(bleName: String? = null) {
        val link = (bleName ?: selectedBleName)?.let(links::get) ?: return
        link.requestChargeState()
    }

    /** The UI polls RSSI fast only while it is on screen. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        links.values.forEach { it.stopRssiPoll() }
        if (visible) {
            selectedLink()?.takeIf { it.phase == ConnectionPhase.READY }?.startRssiPoll()
        }
    }

    // ------------------------------------------------------- scanning / discovery

    private fun onDevicesFound(devices: List<TeslaAdvert>) {
        val now = System.currentTimeMillis()
        val strongestByName =
            devices.groupBy { it.name }
                .mapValues { (_, found) -> found.maxBy { it.rssi ?: Int.MIN_VALUE } }
        for ((name, device) in strongestByName) {
            val known = vehicles[name] ?: continue
            vehicles[name] = known.copy(lastSeenMillis = now)
            if (known.address == device.address) continue
            // The car is advertising from a new address; follow it.
            updateVehicle(name) { it.copy(address = device.address) }
            links[name]?.let { link ->
                if (link.address != device.address) link.close()
            }
        }
        _state.update { state ->
            val connections = state.connections.toMutableMap()
            for (device in devices) {
                val existing = connections[device.address]
                if (existing == null) {
                    connections[device.address] =
                        TeslaConnection(
                            address = device.address,
                            name = device.name,
                            rssi = device.rssi,
                        )
                } else if (existing.name != device.name || existing.rssi != device.rssi) {
                    connections[device.address] =
                        existing.copy(
                            name = device.name,
                            rssi = device.rssi,
                        )
                }
            }
            state.copy(
                devices = visibleDevices(devices, connections, state.explicitScan),
                connections = connections,
            )
        }
        publishVehicles()
        autoConnect()
    }

    /**
     * Connected cars stay listed; an explicit scan adds every discovered car
     * (strongest per name), while silent discovery surfaces every known car and
     * at most one unknown one.
     */
    private fun visibleDevices(
        found: List<TeslaAdvert>,
        connections: Map<String, TeslaConnection>,
        explicitScan: Boolean,
    ): List<TeslaAdvert> {
        val visible = mutableListOf<TeslaAdvert>()
        connections.values
            .filter { it.phase in ACTIVE_PHASES || it.phase == ConnectionPhase.DISCONNECTED }
            .forEach { connection ->
                visible += found.firstOrNull { it.address == connection.address }
                    ?: TeslaAdvert(
                        name = connection.name,
                        address = connection.address,
                        rssi = connection.rssi,
                    )
            }
        val candidates =
            if (explicitScan) {
                found
            } else {
                val known = found.filter { vehicles.containsKey(it.name) }
                known.ifEmpty { listOfNotNull(found.maxByOrNull { it.rssi ?: Int.MIN_VALUE }) }
            }
        candidates
            .groupBy { it.name }
            .mapValues { (_, devices) -> devices.maxBy { it.rssi ?: Int.MIN_VALUE } }
            .values
            .forEach { device ->
                if (visible.none { it.address == device.address }) visible += device
            }
        return visible
    }

    /** Connects every known car that is in range, up to the connection cap. */
    private fun autoConnect() {
        if (!_state.value.trackingEnabled) return
        val state = _state.value
        var active = links.values.count { it.phase in ACTIVE_PHASES }
        val now = System.currentTimeMillis()
        for (device in state.devices) {
            if (active >= MAX_ACTIVE_LINKS) break
            if (!vehicles.containsKey(device.name)) continue
            val link = links[device.name]
            if (link != null && link.phase in ACTIVE_PHASES) continue
            if (link != null && now < link.nextRetryAtMs) continue
            log("${device.name}: reconnecting to paired car")
            ensureLink(device.name, device.address).connect()
            active++
        }
    }

    private fun startScanner() {
        if (demoCar() != null) {
            handler.removeCallbacks(demoScan)
            handler.post(demoScan)
        } else {
            scanner.start()
        }
    }

    private fun stopScanner() {
        handler.removeCallbacks(demoScan)
        scanner.stop()
    }

    /** Finds known cars without taking over the scan button (reconnection). */
    private fun startDiscovery() {
        if (!_state.value.trackingEnabled) return
        if (_state.value.scanning || _state.value.discovering) return
        if (!hasBlePermissions(appContext)) return
        _state.update { it.copy(discovering = true, explicitScan = false) }
        handler.removeCallbacks(stopDiscovery)
        handler.postDelayed(stopDiscovery, DISCOVERY_TIMEOUT_MS)
        startScanner()
    }

    /** Silent discovery is done once every known car has a live link. */
    private fun maybeStopDiscovery() {
        if (!_state.value.discovering) return
        val known = vehicles.values
        if (known.isNotEmpty() && known.all { links[it.bleName]?.phase == ConnectionPhase.READY }) {
            stopScan()
        }
    }

    // -------------------------------------------------------- vehicle bookkeeping

    private fun orderedVehicles(): List<Vehicle> =
        vehicles.values.sortedWith(
            compareByDescending<Vehicle> { it.bleName == selectedBleName }
                .thenByDescending { it.lastSeenMillis },
        )

    private fun rememberVehicle(address: String) {
        val connection = _state.value.connections[address] ?: return
        rememberVehicle(connection.name, address, connection.gattDeviceName)
    }

    private fun rememberVehicle(
        name: String,
        address: String,
        gattName: String?,
    ) {
        if (!TeslaNames.isTeslaBleName(name)) return
        val existing = vehicles[name]
        val demoVin =
            if (DemoMode.isEnabled() &&
                name == runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull()
            ) {
                DemoMode.DEMO_VIN
            } else {
                null
            }
        val updated =
            (existing ?: Vehicle(bleName = name, address = address)).copy(
                address = address,
                gattName = gattName ?: existing?.gattName,
                vin = existing?.vin ?: demoVin,
                lastSeenMillis = System.currentTimeMillis(),
            )
        val changed =
            existing == null ||
                existing.address != updated.address ||
                (gattName != null && existing.gattName != gattName) ||
                (updated.vin != null && existing.vin == null)
        vehicles[name] = updated
        vehicleStore.save(vehicles.values)
        publishVehicles()
        if (changed) log("${nameFor(address)}: remembered for reconnect")
    }

    private fun selectedVehicle(): Vehicle? = selectedBleName?.let(vehicles::get)

    private fun selectedLink(): VehicleLink? = selectedBleName?.let(links::get)

    private fun linkForAddress(address: String): VehicleLink? = links.values.firstOrNull { it.address == address }

    private fun selectVehicle(
        name: String,
        persist: Boolean = false,
    ) {
        selectedBleName = name
        if (persist) {
            prefs.edit().putString(KEY_SELECTED_VEHICLE, name).apply()
        }
        val vehicle = vehicles[name]
        _state.update {
            it.copy(
                selectedBleName = name,
                vinInput = vehicle?.vin ?: "",
                expectedBleName = vehicle?.vin?.let(::bleNameOf),
            )
        }
    }

    private fun bleNameOf(vin: String): String? = runCatching { TeslaNames.bleName(vin) }.getOrNull()

    private fun updateVehicle(
        bleName: String,
        transform: (Vehicle) -> Vehicle,
    ) {
        val vehicle = vehicles[bleName] ?: return
        vehicles[bleName] = transform(vehicle)
        vehicleStore.save(vehicles.values)
        publishVehicles()
    }

    private fun publishVehicles() {
        _state.update {
            it.copy(vehicles = vehicles.values.sortedByDescending { vehicle -> vehicle.lastSeenMillis })
        }
    }

    /** One link per car; recreates it when the car shows up at a new address. */
    private fun ensureLink(
        bleName: String,
        address: String,
    ): VehicleLink {
        val existing = links[bleName]
        if (existing != null && existing.address == address) return existing
        existing?.close()
        val link = VehicleLink(bleName, address)
        links[bleName] = link
        return link
    }

    private fun updateConnection(
        address: String,
        transform: (TeslaConnection) -> TeslaConnection,
    ) {
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
        appendLog(null, message)
    }

    private fun appendLog(
        vehicleId: String?,
        message: String,
    ) {
        _state.update { it.copy(log = (it.log + LogEntry(vehicleId, message)).takeLast(LOG_MAX_LINES)) }
    }

    // ------------------------------------------------------------- per-vehicle link

    private data class PendingSession(val domain: Domain)

    private data class PendingCommand(
        val domain: Domain,
        val requestId: ByteArray,
        val kind: CommandKind,
        val window: AntiReplayWindow = AntiReplayWindow(),
    )

    private enum class CommandKind { WAKE, CHARGE }

    /**
     * Everything that belongs to one car: the transport, sessions, pending
     * commands, retry/backoff counters, pairing state and its timers. The
     * controller treats each link as independent; nothing here touches another
     * car's state.
     */
    private inner class VehicleLink(
        val bleName: String,
        var address: String,
    ) {
        var phase: ConnectionPhase = ConnectionPhase.IDLE
        var nextRetryAtMs: Long = 0L

        private var transport: TeslaTransport? = null
        private val sessions = mutableMapOf<Domain, TeslaSession>()
        private val pendingSessions = mutableMapOf<String, PendingSession>()
        private val pendingCommands = mutableMapOf<String, PendingCommand>()
        private var chargeAfterSession = false
        private var sessionRetryAttempts = 0
        var reconnectAttempts = 0
        private var wakeRefreshAttempts = 0
        private var decryptFailures = 0
        private var lastRehandshakeMs = 0L
        private var keySlotQueue: List<Int> = emptyList()
        private var whitelistPollAttempts = 0
        private var pairingPhase = PairingPhase.IDLE
        private var pairingKeyId: String? = null

        val poll =
            object : Runnable {
                override fun run() {
                    if (phase != ConnectionPhase.READY) return
                    transport?.readRssi()
                    requestStatus()
                    handler.postDelayed(this, POLL_MS)
                }
            }

        val rssiPoll =
            object : Runnable {
                override fun run() {
                    if (!uiVisible || phase != ConnectionPhase.READY) return
                    transport?.readRssi()
                    handler.postDelayed(this, RSSI_POLL_MS)
                }
            }

        val reconnect =
            object : Runnable {
                override fun run() {
                    if (!_state.value.trackingEnabled) return
                    if (phase == ConnectionPhase.READY || phase == ConnectionPhase.CONNECTING) return
                    log("${name()}: reconnecting (attempt ${reconnectAttempts + 1})")
                    connect()
                }
            }

        val wakeRefresh =
            object : Runnable {
                override fun run() {
                    if (_state.value.connections[address]?.status?.asleep == false) return
                    if (wakeRefreshAttempts++ >= WAKE_REFRESH_MAX_ATTEMPTS) return
                    requestStatus()
                    handler.postDelayed(this, WAKE_REFRESH_MS)
                }
            }

        val sessionRetry =
            object : Runnable {
                override fun run() {
                    if (phase != ConnectionPhase.READY) return
                    val needed = neededSessions()
                    if (needed.isEmpty()) return
                    if (sessionRetryAttempts++ >= SESSION_MAX_ATTEMPTS) {
                        log("${name()}: session not established (${needed.joinToString { it.name }})")
                        return
                    }
                    sendSessionRequests(needed)
                    handler.postDelayed(this, SESSION_RETRY_MS)
                }
            }

        val pairingTimeout =
            Runnable {
                if (pairingPhase == PairingPhase.SENDING) {
                    pairingPhase = PairingPhase.WAITING_FOR_CARD
                    updateConnection(address) {
                        it.copy(pairing = PairingPhase.WAITING_FOR_CARD, pairingKeyId = pairingKeyId)
                    }
                    log("No pairing response; tap the card and confirm on the car screen")
                }
            }

        val whitelistPoll =
            object : Runnable {
                override fun run() {
                    if (pairingPhase == PairingPhase.OK || pairingPhase == PairingPhase.ERROR) return
                    if (whitelistPollAttempts++ >= WHITELIST_MAX_ATTEMPTS) {
                        log("Key not enrolled yet; tap the card and retry if needed")
                        return
                    }
                    transport?.send(TeslaVcsec.buildWhitelistInfoRequest())
                    handler.postDelayed(this, WHITELIST_POLL_MS)
                }
            }

        fun name(): String {
            val connection = _state.value.connections[address]
            return connection?.gattDeviceName ?: connection?.name ?: bleName
        }

        fun gattName(): String? = _state.value.connections[address]?.gattDeviceName

        fun vin(): String = vehicles[bleName]?.vin.orEmpty()

        private fun log(message: String) {
            this@TeslaBleController.appendLog(bleName, message)
        }

        fun connect() {
            if (address.isEmpty()) return
            if (phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.READY) return
            cancelCallbacks()
            transport?.close()
            transport = null
            nextRetryAtMs = 0
            ensureConnectionEntry()
            phase = ConnectionPhase.CONNECTING
            updateConnection(address) { it.copy(phase = ConnectionPhase.CONNECTING) }
            val client = createTransport(address, listener())
            transport = client
            client.connect(address)
        }

        fun close() {
            cancelCallbacks()
            transport?.close()
            transport = null
            phase = ConnectionPhase.IDLE
            sessions.clear()
            pendingSessions.clear()
            pendingCommands.clear()
            keySlotQueue = emptyList()
            chargeAfterSession = false
            updateConnection(address) {
                it.copy(phase = ConnectionPhase.IDLE, sessions = emptyList())
            }
        }

        fun postPoll() {
            handler.removeCallbacks(poll)
            handler.postDelayed(poll, POLL_MS)
        }

        fun startRssiPoll() {
            handler.removeCallbacks(rssiPoll)
            handler.post(rssiPoll)
        }

        fun stopRssiPoll() {
            handler.removeCallbacks(rssiPoll)
        }

        private fun cancelCallbacks() {
            handler.removeCallbacks(poll)
            handler.removeCallbacks(rssiPoll)
            handler.removeCallbacks(reconnect)
            handler.removeCallbacks(wakeRefresh)
            handler.removeCallbacks(sessionRetry)
            handler.removeCallbacks(whitelistPoll)
            handler.removeCallbacks(pairingTimeout)
        }

        private fun scheduleReconnect() {
            if (!_state.value.trackingEnabled) return
            reconnectAttempts++
            if (reconnectAttempts >= DISCOVERY_AFTER_ATTEMPTS) {
                // Direct connects keep failing; rediscover in case the car
                // stopped advertising or came back at a new address.
                startDiscovery()
            }
            val delay =
                (RECONNECT_DELAY_MS * reconnectAttempts)
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
            nextRetryAtMs = System.currentTimeMillis() + delay
            handler.removeCallbacks(reconnect)
            handler.postDelayed(reconnect, delay)
        }

        private fun ensureConnectionEntry() {
            _state.update { state ->
                if (state.connections.containsKey(address)) {
                    state
                } else {
                    state.copy(
                        connections =
                            state.connections + (
                                address to TeslaConnection(address = address, name = bleName)
                            ),
                    )
                }
            }
        }

        // ------------------------------------------------------------ protocol

        fun requestStatus() {
            if (transport?.send(TeslaVcsec.buildStatusRequest()) != true) {
                log("${name()}: failed to send VCSEC status request")
            }
        }

        /** Infotainment needs an awake car, so it is only requested then. */
        private fun neededSessions(): List<Domain> {
            val awake = _state.value.connections[address]?.status?.asleep == false
            return SESSION_DOMAINS.filter { domain ->
                sessions.containsKey(domain).not() &&
                    (domain != Domain.DOMAIN_INFOTAINMENT || awake)
            }
        }

        fun startSession() {
            val keyPair = keyStore.load()
            if (keyPair == null) {
                log("${name()}: no pairing key yet")
                return
            }
            val vin = vin()
            if (vin.length != Vehicle.VIN_LENGTH) {
                log("${name()}: enter your VIN to establish a session")
                return
            }
            val needed = neededSessions()
            if (needed.isEmpty()) return
            sessionRetryAttempts = 0
            sendSessionRequests(needed)
            handler.removeCallbacks(sessionRetry)
            handler.postDelayed(sessionRetry, SESSION_RETRY_MS)
        }

        private fun sendSessionRequests(domains: List<Domain>) {
            val keyPair = keyStore.load() ?: return
            for (domain in domains) {
                val uuid = TeslaCrypto.randomBytes(16)
                val routing =
                    if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                        TeslaCrypto.randomBytes(16)
                    } else {
                        clientAddress
                    }
                pendingSessions[uuid.toHex()] = PendingSession(domain)
                val request =
                    TeslaSessionRequests.buildSessionInfoRequest(
                        domain = domain,
                        publicKeyRaw = keyPair.publicKeyRaw,
                        routingAddress = routing,
                        uuid = uuid,
                    )
                log("${name()}: session request ${domain.name}")
                if (transport?.send(request) != true) {
                    log("${name()}: failed to send session request")
                }
            }
        }

        private fun handleSessionInfo(bytes: ByteArray): Boolean {
            val message =
                runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
                    ?: return false
            val encodedInfo = message.session_info?.toByteArray() ?: return false
            val challenge = message.request_uuid.toByteArray()
            val pending = pendingSessions.remove(challenge.toHex()) ?: return false
            val tag = message.signature_data?.session_info_tag?.tag?.toByteArray()
            if (tag == null) {
                log("${name()}: session info missing tag")
                return true
            }
            val keyPair = keyStore.load()
            val session =
                if (keyPair != null && vin().length == Vehicle.VIN_LENGTH) {
                    TeslaSession.import(
                        privateKeyPkcs8 = keyPair.privateKeyPkcs8,
                        publicKeyRaw = keyPair.publicKeyRaw,
                        vin = vin(),
                        challenge = challenge,
                        encodedInfo = encodedInfo,
                        tag = tag,
                    )
                } else {
                    null
                }
            if (session == null) {
                log("${name()}: session verification failed (${pending.domain.name})")
                return true
            }
            sessions[pending.domain] = session
            updateConnection(address) {
                it.copy(sessions = sessions.keys.map { domain -> domain.name }.sorted())
            }
            log("${name()}: session established (${pending.domain.name})")
            rememberVehicle(address)
            if (SESSION_DOMAINS.all { sessions.containsKey(it) }) {
                handler.removeCallbacks(sessionRetry)
            }
            if (pending.domain == Domain.DOMAIN_INFOTAINMENT && chargeAfterSession) {
                chargeAfterSession = false
                requestChargeState()
            }
            return true
        }

        fun wake() {
            if (_state.value.connections[address]?.status?.asleep == false) {
                log("${name()}: car is already awake")
                return
            }
            if (!sessions.containsKey(Domain.DOMAIN_VEHICLE_SECURITY)) {
                log("${name()}: no VCSEC session to wake with")
                return
            }
            sendAuthenticated(
                domain = Domain.DOMAIN_VEHICLE_SECURITY,
                payload = TeslaCommands.buildWakeRequest(),
                kind = CommandKind.WAKE,
            )
            wakeRefreshAttempts = 0
            handler.removeCallbacks(wakeRefresh)
            handler.postDelayed(wakeRefresh, WAKE_REFRESH_MS)
        }

        fun requestChargeState() {
            if (_state.value.connections[address]?.status?.asleep != false) {
                log("${name()}: car is asleep; wake it first")
                return
            }
            if (sessions.containsKey(Domain.DOMAIN_INFOTAINMENT)) {
                sendAuthenticated(
                    domain = Domain.DOMAIN_INFOTAINMENT,
                    payload = TeslaCommands.buildChargeStateRequest(),
                    kind = CommandKind.CHARGE,
                )
            } else {
                chargeAfterSession = true
                log("${name()}: requesting Infotainment session for SOC")
                startSession()
            }
        }

        private fun sendAuthenticated(
            domain: Domain,
            payload: ByteArray,
            kind: CommandKind,
        ): Boolean {
            val session =
                sessions[domain] ?: run {
                    log("${name()}: no session for ${domain.name}")
                    return false
                }
            val uuid = TeslaCrypto.randomBytes(16)
            val routing =
                if (domain == Domain.DOMAIN_VEHICLE_SECURITY) {
                    TeslaCrypto.randomBytes(16)
                } else {
                    clientAddress
                }
            val message = TeslaSessionRequests.buildAuthenticatedRequest(domain, payload, routing, uuid)
            val encrypted =
                session.encrypt(message, COMMAND_EXPIRES_SECONDS) ?: run {
                    log("${name()}: encryption failed")
                    return false
                }
            val requestId = session.requestId(encrypted) ?: return false
            pendingCommands[uuid.toHex()] = PendingCommand(domain, requestId, kind)
            return transport?.send(encrypted.encode()) == true
        }

        private fun handleEncryptedResponse(bytes: ByteArray): Boolean {
            val message =
                runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
                    ?: return false
            if (message.signature_data?.AES_GCM_Response_data == null) return false
            val pending =
                pendingCommands.remove(message.request_uuid.toByteArray().toHex())
                    ?: return false
            val session = sessions[pending.domain] ?: return true
            val plaintext = session.decrypt(message, pending.requestId, pending.window)
            if (plaintext == null) {
                decryptFailures++
                log("${name()}: response decryption failed (${pending.kind.name.lowercase()})")
                if (decryptFailures >= 2) {
                    // The car may have rotated its session (reboot, deep sleep);
                    // handshake again, but at most once a minute so a broken link
                    // cannot loop.
                    val now = System.currentTimeMillis()
                    if (now - lastRehandshakeMs > REHANDSHAKE_COOLDOWN_MS) {
                        lastRehandshakeMs = now
                        decryptFailures = 0
                        log("${name()}: re-handshaking after repeated decryption failures")
                        sessions.remove(pending.domain)
                        startSession()
                    }
                }
                return true
            }
            decryptFailures = 0
            when (pending.kind) {
                CommandKind.WAKE -> {
                    val status = runCatching { TeslaVcsec.parseCommandStatus(plaintext) }.getOrNull()
                    log("${name()}: wake ${status?.name ?: "response received"}")
                }

                CommandKind.CHARGE -> {
                    val charge = runCatching { TeslaCommands.parseChargeState(plaintext) }.getOrNull()
                    if (charge != null) {
                        val previous = _state.value.connections[address]?.charge
                        updateConnection(address) { it.copy(charge = charge) }
                        historyStore.record(bleName, charge)
                        if (previous?.batteryLevel != charge.batteryLevel ||
                            previous?.chargingState != charge.chargingState
                        ) {
                            log(
                                "${name()}: SOC ${charge.batteryLevel}% " +
                                    "(${charge.chargingState ?: "unknown"})",
                            )
                        }
                    } else {
                        val status = runCatching { TeslaCommands.parseActionStatus(plaintext) }.getOrNull()
                        log(
                            "${name()}: charge response missing data" +
                                (status?.let { " ($it)" } ?: ""),
                        )
                    }
                }
            }
            return true
        }

        fun pairKey() {
            if (phase != ConnectionPhase.READY) {
                log("No selected car ready for pairing")
                return
            }
            val keyPair = keyStore.loadOrCreate()
            val keyId = keyPair.keyId.toHex()
            pairingPhase = PairingPhase.SENDING
            pairingKeyId = keyId
            updateConnection(address) {
                it.copy(pairing = PairingPhase.SENDING, pairingKeyId = keyId)
            }
            log("${name()}: pairing key $keyId")
            val request = TeslaPairing.buildAddKeyRequest(keyPair.publicKeyRaw)
            if (transport?.send(request) != true) {
                pairingPhase = PairingPhase.ERROR
                updateConnection(address) {
                    it.copy(pairing = PairingPhase.ERROR, pairingKeyId = keyId)
                }
                log("${name()}: failed to send pairing request")
            } else {
                handler.removeCallbacks(pairingTimeout)
                handler.postDelayed(pairingTimeout, PAIRING_TIMEOUT_MS)
                whitelistPollAttempts = 0
                handler.removeCallbacks(whitelistPoll)
                handler.postDelayed(whitelistPoll, WHITELIST_POLL_MS)
            }
        }

        fun requestKeySlot() {
            if (_state.value.connections[address]?.keySlot != null) return
            if (keyStore.load() == null) return
            transport?.send(TeslaVcsec.buildWhitelistInfoRequest())
        }

        private fun requestNextKeySlot() {
            val slot = keySlotQueue.firstOrNull()
            if (slot == null) {
                log("${name()}: could not locate our key slot")
                return
            }
            keySlotQueue = keySlotQueue.drop(1)
            transport?.send(TeslaVcsec.buildWhitelistEntryRequest(slot))
        }

        private fun handleWhitelistInfo(whitelist: WhitelistInfo) {
            val stored = keyStore.load()
            val keyId = stored?.keyId?.toHex()
            val enrolled =
                stored != null &&
                    whitelist.whitelistEntries.any {
                        it.publicKeySHA1.toByteArray().copyOf(stored.keyId.size).contentEquals(stored.keyId)
                    }
            if (stored != null && enrolled && keyId != null) {
                handler.removeCallbacks(whitelistPoll)
                if (pairingPhase == PairingPhase.SENDING || pairingPhase == PairingPhase.WAITING_FOR_CARD) {
                    pairingPhase = PairingPhase.OK
                    pairingKeyId = keyId
                    updateConnection(address) {
                        it.copy(pairing = PairingPhase.OK, pairingKeyId = keyId)
                    }
                    log("${name()}: key enrolled (${whitelist.numberOfEntries} keys)")
                }
                rememberVehicle(address)
                // A freshly enrolled key can open sessions now.
                startSession()
                val index =
                    whitelist.whitelistEntries.indexOfFirst {
                        it.publicKeySHA1.toByteArray().copyOf(stored.keyId.size).contentEquals(stored.keyId)
                    }
                val slots = occupiedSlots(whitelist.slotMask)
                keySlotQueue =
                    if (index in slots.indices) {
                        listOf(slots[index]) + slots.filterIndexed { i, _ -> i != index }
                    } else {
                        slots
                    }
                requestNextKeySlot()
            } else if (whitelistPollAttempts == 1) {
                log("${name()}: whitelist has ${whitelist.numberOfEntries} keys")
            }
        }

        private fun handleWhitelistEntry(entry: WhitelistEntryInfo) {
            val stored = keyStore.load()
            val matches =
                stored != null && (
                    entry.publicKey?.PublicKeyRaw?.toByteArray()?.contentEquals(stored.publicKeyRaw) == true ||
                        entry.keyId?.publicKeySHA1?.toByteArray()
                            ?.copyOf(stored.keyId.size)
                            ?.contentEquals(stored.keyId) == true
                )
            if (matches) {
                keySlotQueue = emptyList()
                updateConnection(address) { it.copy(keySlot = entry.slot) }
                updateVehicle(bleName) { it.copy(keySlot = entry.slot) }
                log("${name()}: key slot ${entry.slot}")
            } else {
                requestNextKeySlot()
            }
        }

        fun onMessage(message: ByteArray) {
            if (handleSessionInfo(message)) return
            if (handleEncryptedResponse(message)) return

            val pairing = runCatching { TeslaPairing.parseAddKeyResponse(message) }.getOrNull()
            if (pairing != null) {
                handler.removeCallbacks(pairingTimeout)
                val phase =
                    when (pairing) {
                        TeslaPairing.Result.OK -> PairingPhase.OK
                        TeslaPairing.Result.WAITING_FOR_CARD -> PairingPhase.WAITING_FOR_CARD
                        TeslaPairing.Result.ERROR -> PairingPhase.ERROR
                    }
                pairingPhase = phase
                updateConnection(address) { it.copy(pairing = phase, pairingKeyId = pairingKeyId) }
                log("${name()}: pairing ${pairing.name.lowercase()}")
                return
            }

            val whitelist = runCatching { TeslaVcsec.parseWhitelistInfoResponse(message) }.getOrNull()
            if (whitelist != null) {
                handleWhitelistInfo(whitelist)
                return
            }

            val entry = runCatching { TeslaVcsec.parseWhitelistEntryResponse(message) }.getOrNull()
            if (entry != null) {
                handleWhitelistEntry(entry)
                return
            }

            val status = runCatching { TeslaVcsec.parseStatusResponse(message) }.getOrNull()
            if (status != null) {
                if (!status.asleep) {
                    handler.removeCallbacks(wakeRefresh)
                }
                val previous = _state.value.connections[address]?.status
                updateConnection(address) { it.copy(status = status) }
                if (previous?.locked != status.locked ||
                    previous.asleep != status.asleep ||
                    previous.userPresent != status.userPresent
                ) {
                    log(
                        "${name()}: VCSEC status locked=${status.locked} " +
                            "asleep=${status.asleep} userPresent=${status.userPresent}",
                    )
                }
                if (!status.asleep && !chargeAfterSession) {
                    // Track SOC for every awake car that can answer, not just
                    // the selected one; skip cars with no VIN (no session).
                    val canRead =
                        sessions.containsKey(Domain.DOMAIN_INFOTAINMENT) ||
                            vin().length == Vehicle.VIN_LENGTH
                    if (canRead) requestChargeState()
                }
            } else {
                log("${name()}: RX ${message.size} bytes ${message.toHex()}")
            }
        }

        private fun listener() =
            object : TeslaTransport.Listener {
                override fun onPhase(phase: ConnectionPhase) {
                    if (phase == ConnectionPhase.FAILED || phase == ConnectionPhase.DISCONNECTED) {
                        transport?.close()
                        transport = null
                        handler.removeCallbacks(poll)
                        handler.removeCallbacks(rssiPoll)
                        val hadData =
                            _state.value.connections[address]?.let {
                                it.sessions.isNotEmpty() || it.status != null || it.charge != null
                            } == true
                        val display = if (hadData) ConnectionPhase.DISCONNECTED else ConnectionPhase.FAILED
                        this@VehicleLink.phase = display
                        updateConnection(address) { it.copy(phase = display) }
                        scheduleReconnect()
                        return
                    }
                    this@VehicleLink.phase = phase
                    updateConnection(address) { it.copy(phase = phase) }
                    if (phase == ConnectionPhase.READY) {
                        reconnectAttempts = 0
                        nextRetryAtMs = 0
                        // Additive scans stay under the user's control; the selected
                        // car connecting is the one signal that means "found it".
                        if (_state.value.explicitScan && selectedBleName == bleName) stopScan()
                        if (selectedBleName == bleName) startRssiPoll()
                        requestStatus()
                        requestKeySlot()
                        startSession()
                        postPoll()
                        maybeStopDiscovery()
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

                override fun onRssi(rssi: Int) {
                    updateConnection(address) { it.copy(rssi = rssi) }
                }

                override fun onMessage(message: ByteArray) {
                    this@VehicleLink.onMessage(message)
                }

                override fun onLog(message: String) {
                    log("${name()}: $message")
                }
            }
    }

    private fun occupiedSlots(slotMask: Int): List<Int> = (0 until Int.SIZE_BITS).filter { (slotMask ushr it) and 1 == 1 }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val LOG_MAX_LINES = 200
        const val PAIRING_TIMEOUT_MS = 5000L
        const val WHITELIST_POLL_MS = 2000L
        const val WHITELIST_MAX_ATTEMPTS = 30
        const val COMMAND_EXPIRES_SECONDS = 5
        const val SESSION_RETRY_MS = 3000L
        const val SESSION_MAX_ATTEMPTS = 20
        const val POLL_MS = 10_000L
        const val RSSI_POLL_MS = 500L
        const val DEMO_SCAN_MS = 2_000L
        const val REHANDSHAKE_COOLDOWN_MS = 60_000L
        const val RECONNECT_DELAY_MS = 5000L
        const val RECONNECT_MAX_DELAY_MS = 60_000L
        const val DISCOVERY_AFTER_ATTEMPTS = 3
        const val DISCOVERY_TIMEOUT_MS = 30_000L
        const val WAKE_REFRESH_MS = 5000L
        const val WAKE_REFRESH_MAX_ATTEMPTS = 6
        const val MAX_ACTIVE_LINKS = 3
        const val PREFS = "teslable"
        const val KEY_TRACKING_ENABLED = "tracking_enabled"
        const val KEY_SELECTED_VEHICLE = "selected_vehicle"
        val ACTIVE_PHASES =
            setOf(
                ConnectionPhase.CONNECTING,
                ConnectionPhase.CONNECTED,
                ConnectionPhase.DISCOVERING,
                ConnectionPhase.READY,
            )
        val SESSION_DOMAINS =
            listOf(
                Domain.DOMAIN_VEHICLE_SECURITY,
                Domain.DOMAIN_INFOTAINMENT,
            )
    }
}
