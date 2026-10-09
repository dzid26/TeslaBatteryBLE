// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.core.TeslaNames
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.ConnectionEvent
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.protocol.AntiReplayWindow
import com.dzid26.teslable.core.protocol.CommandRecords
import com.dzid26.teslable.core.protocol.InfotainmentPollPolicy
import com.dzid26.teslable.core.protocol.InfotainmentSessionGate
import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.TeslaCrypto
import com.dzid26.teslable.core.protocol.TeslaKeyPair
import com.dzid26.teslable.core.protocol.TeslaPairing
import com.dzid26.teslable.core.protocol.TeslaSession
import com.dzid26.teslable.core.protocol.TeslaSessionRequests
import com.dzid26.teslable.core.protocol.TeslaVcsec
import com.dzid26.teslable.core.protocol.asleep
import com.dzid26.teslable.core.protocol.chargingStateKind
import com.dzid26.teslable.core.protocol.isRefusal
import com.dzid26.teslable.core.protocol.locked
import com.dzid26.teslable.core.protocol.userPresent
import com.dzid26.teslable.history.HistoryStore
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * Owns every vehicle the app knows and one [VehicleLink] per car. Scanning,
 * discovery and selection live here; connecting, sessions, pairing, polling and
 * commands live in the link, so one car failing never touches another.
 */
class TeslaBleController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(BleUiState())
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private val keyStore = PairingKeyStore(appContext)
    private val vehicleStore = VehicleStore(appContext)
    private val vehicles = mutableMapOf<String, Vehicle>()
    private val historyStore: HistoryStore

    /** Battery readings recorded from every charge response, oldest first. */
    val batteryHistory: StateFlow<List<BatterySample>> get() = historyStore.samples

    /** VCSEC status readings logged for every car, oldest first. */
    val statusHistory: StateFlow<List<StatusSample>> get() = historyStore.statusSamples

    /** Drive readings recorded from every DriveState response, oldest first. */
    val driveHistory: StateFlow<List<DriveSample>> get() = historyStore.driveSamples

    private val links = mutableMapOf<String, VehicleLink>()
    private val handler = Handler(Looper.getMainLooper())
    private val clientAddress = TeslaCrypto.randomBytes(16)
    private var selectedBleName: String? = null
    private var uiVisible = false
    private var lastVisibilityEvent: AppState.Event? = null

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

    /**
     * Ends a scan once no new car has appeared for [SCAN_QUIET_MS]: the list
     * has settled, so scanning on would only spin the radio.
     */
    private val stopQuietScan =
        Runnable {
            if (_state.value.scanning) {
                log("scan: no new cars for ${SCAN_QUIET_MS / 1000}s; stopping")
                stopScan()
            }
        }

    init {
        vehicleStore.load().forEach { vehicles[it.bleName] = it }
        // A restored backup can carry cached key slots for a key this device
        // does not have (device-only keys never leave the phone). Enrollment is
        // re-verified on connect, so drop the stale cache.
        val staleSlots = vehicles.values.filter { it.keySlot != null && !keyStore.hasKey(it.bleName) }
        if (staleSlots.isNotEmpty()) {
            staleSlots.forEach { vehicles[it.bleName] = it.copy(keySlot = null) }
            vehicleStore.save(vehicles.values)
        }
        historyStore = HistoryStore(appContext)
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

    /** Saves a VIN for one car after the cars-list editor validated it. */
    fun saveVin(
        bleName: String,
        vin: String,
    ) {
        val normalized = Vehicle.normalizeVin(vin)
        val vehicle = vehicles[bleName] ?: return
        if (!vehicle.acceptsVin(normalized)) {
            log("${vehicle.title}: VIN does not match this car's advertised name")
            return
        }
        updateVehicle(bleName) { it.copy(vin = normalized) }
        log("${vehicle.title}: VIN saved")
        // A VIN unlocks the sessions; retry the handshake now.
        links[bleName]?.takeIf { it.phase == ConnectionPhase.READY }?.startSession()
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
        handler.removeCallbacks(stopQuietScan)
        handler.postDelayed(stopQuietScan, SCAN_QUIET_MS)
    }

    fun stopScan() {
        handler.removeCallbacks(stopDiscovery)
        handler.removeCallbacks(stopQuietScan)
        stopScanner()
        _state.update { it.copy(scanning = false, discovering = false) }
    }

    /** The UI opened a car by its current address. */
    fun openVehicle(address: String) {
        val name =
            _state.value.connections[address]?.name
                ?: _state.value.devices
                    .firstOrNull { it.address == address }
                    ?.name
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

    /**
     * Forgets the cached pairing state (key slots and sessions) so the pairing
     * flow can be exercised again. The stored key is kept, and the car still
     * has it in its whitelist, so the next whitelist check may mark the car
     * paired again.
     */
    fun clearPairingCache() {
        vehicles.values.forEach { vehicle ->
            if (vehicle.keySlot != null) {
                vehicles[vehicle.bleName] = vehicle.copy(keySlot = null)
            }
        }
        vehicleStore.save(vehicles.values)
        publishVehicles()
        links.values.forEach { it.clearCachedPairing() }
        log("Pairing cache cleared; the stored key is kept")
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

    /** True when an enrolled car is connected, so the tracking service should run. */
    fun shouldTrack(): Boolean = _state.value.shouldTrack()

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
                log = emptyList(),
            )
        }
        log("Background tracking disabled")
    }

    fun wakeVehicle(bleName: String? = null) {
        val target = bleName ?: selectedBleName
        val link = target?.let(links::get)
        if (link == null) {
            log("No connected car to wake")
            return
        }
        link.wake()
    }

    fun requestChargeState(bleName: String? = null) {
        val link = (bleName ?: selectedBleName)?.let(links::get) ?: return
        link.requestChargeState(Command.Reason.USER_REFRESH)
    }

    /**
     * Logs a change in the app's own state (ADR-0008); [startReason] goes with a tracking start. A
     * foreground or background event that repeats the last one (a rotation restarts the activity) is dropped.
     */
    fun recordAppState(
        event: AppState.Event,
        startReason: AppState.StartReason? = null,
    ) {
        if (event == AppState.Event.APP_FOREGROUND || event == AppState.Event.APP_BACKGROUND) {
            if (event == lastVisibilityEvent) return
            lastVisibilityEvent = event
        }
        historyStore.recordAppState(
            AppState(event = event, start_reason = startReason),
            Instant.ofEpochMilli(System.currentTimeMillis()),
        )
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
        // A new car resets the quiet timer that ends the scan.
        val previousNames =
            _state.value.devices
                .map { it.name }
                .toSet()
        if (_state.value.scanning && devices.any { it.name !in previousNames }) {
            handler.removeCallbacks(stopQuietScan)
            handler.postDelayed(stopQuietScan, SCAN_QUIET_MS)
        }
        val strongestByName =
            devices
                .groupBy { it.name }
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
     * at most one unknown one. Unpaired cars only stay while they advertise.
     */
    private fun visibleDevices(
        found: List<TeslaAdvert>,
        connections: Map<String, TeslaConnection>,
        explicitScan: Boolean,
    ): List<TeslaAdvert> {
        val visible = mutableListOf<TeslaAdvert>()
        connections.values
            .filter { it.phase in ACTIVE_PHASES }
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
            )
        }
    }

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
            ?: _state.value.devices
                .firstOrNull { it.address == address }
                ?.name
            ?: address
    }

    /** The phone's latest RSSI for the car at [address] in dBm, or null before the first reading; every history record carries it. */
    private fun latestRssi(address: String): Int? = _state.value.connections[address]?.rssi

    /**
     * Logs a change in the connection to [link]'s car: [ConnectionEvent.State.CONNECTED] right after it became ready,
     * [ConnectionEvent.State.DISCONNECTED] right before a connection that had become ready ends. Both run while the phase is READY,
     * so a connection attempt that never got there is not logged.
     */
    private fun recordConnection(
        link: VehicleLink,
        state: ConnectionEvent.State,
    ) {
        if (link.phase != ConnectionPhase.READY) return
        val deviceTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
        historyStore.recordConnection(link.bleName, state, deviceTimestamp, latestRssi(link.address))
        // Whatever the car has not answered by now, it will not answer.
        if (state == ConnectionEvent.State.DISCONNECTED) link.commands.logUnanswered(linkEnded = true)
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

    private data class PendingSession(
        val domain: Domain,
    )

    private data class PendingCommand(
        val domain: Domain,
        val requestId: ByteArray,
        val kind: CommandKind,
        val reason: Command.Reason,
        val window: AntiReplayWindow = AntiReplayWindow(),
    )

    private enum class CommandKind { WAKE, CHARGE, DRIVE, CLOSURES, CLIMATE }

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
        private var wakeAfterSession = false
        private var sessionRetryAttempts = 0
        var reconnectAttempts = 0
        private var wakeRefreshAttempts = 0
        private var decryptFailures = 0
        private var lastRehandshakeMs = 0L
        private var keySlotQueue: List<Int> = emptyList()
        private var whitelistPollAttempts = 0
        private var pairingPhase = PairingPhase.IDLE
        private var pairingKeyId: String? = null
        private var pendingPairCheck = false

        /** The next VCSEC status is the first since the link became ready; the status log always keeps it. */
        private var firstStatusAfterConnect = true

        /** Decides when an Infotainment read is due (ADR-0009). */
        private val infotainmentPolicy = InfotainmentPollPolicy()

        /** Whether a due read is waiting for the Infotainment handshake; never left set once it is over. */
        private val infotainmentGate = InfotainmentSessionGate()

        /** Keeps the command log: every request goes out through [transmit], which logs it with its reason (ADR-0008). */
        val commands = CommandRecorder(bleName, historyStore, rssi = { latestRssi(address) })

        /** The reason of the read that is waiting for the Infotainment handshake. */
        private var waitingReadReason = Command.Reason.REASON_UNSPECIFIED

        /** Logs and feeds the policy with the drive, closures and climate replies (ADR-0008, ADR-0009). */
        private val stateReplies =
            InfotainmentStateReplies(
                vehicleId = bleName,
                historyStore = historyStore,
                policy = infotainmentPolicy,
                rssi = { latestRssi(address) },
                log = { log("${name()}: $it") },
            )

        val poll =
            object : Runnable {
                override fun run() {
                    if (phase != ConnectionPhase.READY) return
                    transport?.readRssi()
                    requestStatus()
                    commands.logUnanswered()
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
                    if (_state.value.connections[address]
                            ?.status
                            ?.asleep == false
                    ) {
                        return
                    }
                    if (wakeRefreshAttempts++ >= WAKE_REFRESH_MAX_ATTEMPTS) {
                        log("${name()}: wake not confirmed; the car may be out of range")
                        return
                    }
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
                        infotainmentGate.onGaveUp()
                        dropPendingInfotainment()
                        return
                    }
                    sendSessionRequests(needed)
                    handler.postDelayed(this, SESSION_RETRY_MS)
                }
            }

        val pairingTimeout =
            Runnable {
                when (pairingPhase) {
                    PairingPhase.SENDING -> {
                        pairingPhase = PairingPhase.WAITING_FOR_CARD
                        updateConnection(address) {
                            it.copy(pairing = PairingPhase.WAITING_FOR_CARD, pairingKeyId = pairingKeyId)
                        }
                        log("${name()}: no pairing response yet; waiting for the card")
                    }

                    PairingPhase.CHECKING -> {
                        pendingPairCheck = false
                        setPairing(PairingPhase.ERROR)
                        log("${name()}: no response while checking the key list")
                    }

                    else -> Unit
                }
            }

        val whitelistPoll =
            object : Runnable {
                override fun run() {
                    if (pairingPhase == PairingPhase.OK || pairingPhase == PairingPhase.ERROR) return
                    if (whitelistPollAttempts++ >= WHITELIST_MAX_ATTEMPTS) {
                        setPairing(PairingPhase.ERROR)
                        log("${name()}: the car did not confirm the key; pair again when ready")
                        return
                    }
                    transmitVcsec(TeslaVcsec.buildWhitelistInfoRequest(), Command.Reason.PAIRING)
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
            if (!hasBlePermissions(appContext)) {
                // Android 12+ throws a SecurityException from connectGatt() when
                // BLUETOOTH_CONNECT is missing; fail quietly instead.
                log("${name()}: Bluetooth permissions are missing; grant them to connect")
                return
            }
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
            recordConnection(this, ConnectionEvent.State.DISCONNECTED)
            cancelCallbacks()
            transport?.close()
            transport = null
            phase = ConnectionPhase.IDLE
            sessions.clear()
            pendingSessions.clear()
            pendingCommands.clear()
            keySlotQueue = emptyList()
            infotainmentGate.onLinkDropped()
            wakeAfterSession = false
            pendingPairCheck = false
            updateConnection(address) {
                it.copy(phase = ConnectionPhase.IDLE, sessions = emptyList())
            }
        }

        /** Drops the cached key slot and sessions after the pairing cache was cleared. */
        fun clearCachedPairing() {
            sessions.clear()
            pendingSessions.clear()
            pendingCommands.clear()
            keySlotQueue = emptyList()
            pendingPairCheck = false
            wakeAfterSession = false
            pairingPhase = PairingPhase.IDLE
            pairingKeyId = null
            updateConnection(address) {
                it.copy(
                    keySlot = null,
                    sessions = emptyList(),
                    pairing = PairingPhase.IDLE,
                    pairingKeyId = null,
                )
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

        /** The routine VCSEC status poll: the one request the command log leaves out, the status records imply it. */
        fun requestStatus() {
            if (!transmit(TeslaVcsec.buildStatusRequest(), command = null)) {
                log("${name()}: failed to send VCSEC status request")
            }
        }

        /**
         * The one place every request to the car goes out. [command] is what the command log keeps, with
         * its reason; null only for the routine status poll. It is logged once the transport took it.
         */
        private fun transmit(
            bytes: ByteArray,
            command: Command?,
            awaitingKey: String? = null,
        ): Boolean = (transport?.send(bytes) == true).also { if (it && command != null) commands.sent(command, awaitingKey) }

        /** A VCSEC information request (whitelist or key slot), [bytes] as `TeslaVcsec` builds it. */
        private fun transmitVcsec(
            bytes: ByteArray,
            reason: Command.Reason,
        ) = transmit(bytes, CommandRecords.vcsecRoutable(reason, bytes))

        private fun isAwake(): Boolean =
            _state.value.connections[address]
                ?.status
                ?.asleep == false

        /**
         * VCSEC is always needed. Infotainment only while an awake car has a read waiting for it
         * (a sleeping car cannot answer), never on connect.
         */
        private fun neededSessions(): List<Domain> =
            SESSION_DOMAINS.filter { domain ->
                sessions.containsKey(domain).not() &&
                    (domain != Domain.DOMAIN_INFOTAINMENT || infotainmentGate.mayRequestSession(isAwake()))
            }

        /** Forgets unanswered Infotainment requests, so none is mistaken for a handshake still in flight. */
        private fun dropPendingInfotainment() {
            pendingSessions.values.removeAll { it.domain == Domain.DOMAIN_INFOTAINMENT }
        }

        /** True when session requests went out. */
        fun startSession(): Boolean {
            if (!keyStore.hasKey(bleName)) {
                log("${name()}: no pairing key yet")
                return false
            }
            val vin = vin()
            if (vin.length != Vehicle.VIN_LENGTH) {
                log("${name()}: enter your VIN to establish a session")
                return false
            }
            val needed = neededSessions()
            if (needed.isEmpty()) return false
            sessionRetryAttempts = 0
            sendSessionRequests(needed)
            handler.removeCallbacks(sessionRetry)
            handler.postDelayed(sessionRetry, SESSION_RETRY_MS)
            return true
        }

        private fun sendSessionRequests(domains: List<Domain>) {
            val publicKeyRaw = keyStore.publicKeyRaw(bleName) ?: return
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
                        publicKeyRaw = publicKeyRaw,
                        routingAddress = routing,
                        uuid = uuid,
                    )
                log("${name()}: session request ${domain.name}")
                if (!transmit(request, CommandRecords.sessionInfo(Command.Reason.SESSION_HANDSHAKE, request))) {
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
            val tag =
                message.signature_data
                    ?.session_info_tag
                    ?.tag
                    ?.toByteArray()
            if (tag == null) {
                log("${name()}: session info missing tag")
                return true
            }
            val keyPair = keyStore.load(bleName)
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
                if (keyPair == null && !keyStore.hasKey(bleName)) {
                    // The store dropped a key this phone can never decrypt (for
                    // example a device-only key restored from another phone).
                    forgetEnrollment("the stored key can't be used on this phone")
                }
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
            if (pending.domain == Domain.DOMAIN_INFOTAINMENT && infotainmentGate.onSessionEstablished()) {
                requestChargeState(waitingReadReason)
            }
            if (pending.domain == Domain.DOMAIN_VEHICLE_SECURITY && wakeAfterSession) {
                wakeAfterSession = false
                sendWake()
            }
            return true
        }

        fun wake() {
            if (_state.value.connections[address]
                    ?.status
                    ?.asleep == false
            ) {
                log("${name()}: car is already awake")
                return
            }
            wakeRefreshAttempts = 0
            handler.removeCallbacks(wakeRefresh)
            handler.postDelayed(wakeRefresh, WAKE_REFRESH_MS)
            // A long-sleeping car rotates its session; a command encrypted with
            // the old one is dropped without a reply, so handshake again first.
            if (sessions.containsKey(Domain.DOMAIN_VEHICLE_SECURITY)) {
                sessions.remove(Domain.DOMAIN_VEHICLE_SECURITY)
                updateConnection(address) {
                    it.copy(sessions = sessions.keys.map { domain -> domain.name }.sorted())
                }
            }
            wakeAfterSession = true
            log("${name()}: waking with a fresh session")
            startSession()
        }

        private fun sendWake() {
            if (!sessions.containsKey(Domain.DOMAIN_VEHICLE_SECURITY)) {
                log("${name()}: no VCSEC session to wake with")
                return
            }
            val request = TeslaCommands.buildWakeRequest()
            val sent = sendAuthenticated(Domain.DOMAIN_VEHICLE_SECURITY, request, CommandKind.WAKE, Command.Reason.USER_WAKE)
            log("${name()}: " + if (sent) "wake requested" else "failed to send wake request")
        }

        /**
         * Reads the charge state, the first of a read's four requests. [reason] says why; the refresh
         * button ([Command.Reason.USER_REFRESH]) is sent at once and counted by the policy.
         */
        fun requestChargeState(reason: Command.Reason = CommandRecords.reasonOf(infotainmentPolicy.lastReadReason)) {
            if (reason == Command.Reason.USER_REFRESH) infotainmentPolicy.onUserRequest(System.currentTimeMillis())
            when (infotainmentGate.onReadDue(isAwake(), sessions.containsKey(Domain.DOMAIN_INFOTAINMENT))) {
                InfotainmentSessionGate.Action.SKIP -> log("${name()}: car is asleep; wake it first")

                InfotainmentSessionGate.Action.SEND_READ ->
                    sendAuthenticated(
                        domain = Domain.DOMAIN_INFOTAINMENT,
                        payload = TeslaCommands.buildChargeStateRequest(),
                        kind = CommandKind.CHARGE,
                        reason = reason,
                    )

                // The retry loop is already running; the read goes out when the session arrives.
                InfotainmentSessionGate.Action.WAIT_FOR_HANDSHAKE -> waitingReadReason = reason

                InfotainmentSessionGate.Action.START_HANDSHAKE -> {
                    waitingReadReason = reason
                    log("${name()}: requesting Infotainment session for SOC")
                    if (!startSession()) infotainmentGate.onNotStartable()
                }
            }
        }

        /**
         * Asks for the next state category after the previous reply (drive
         * after charge, closures after drive, climate after closures), only
         * while the car is awake and an Infotainment session already exists:
         * it never starts a session or wakes the car.
         */
        private fun requestFollowUp(
            payload: ByteArray,
            kind: CommandKind,
        ) {
            if (!isAwake() || !sessions.containsKey(Domain.DOMAIN_INFOTAINMENT)) return
            sendAuthenticated(domain = Domain.DOMAIN_INFOTAINMENT, payload = payload, kind = kind)
        }

        private fun sendAuthenticated(
            domain: Domain,
            payload: ByteArray,
            kind: CommandKind,
            reason: Command.Reason = Command.Reason.FOLLOW_UP,
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
            pendingCommands[uuid.toHex()] = PendingCommand(domain, requestId, kind, reason)
            val sent = transmit(encrypted.encode(), CommandRecords.authenticated(reason, domain, payload), uuid.toHex())
            if (!sent) log("${name()}: failed to send ${kind.name.lowercase()} request")
            return sent
        }

        private fun handleEncryptedResponse(
            bytes: ByteArray,
            deviceTimestamp: Instant,
        ): Boolean {
            val message =
                runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
                    ?: return false
            // A rejected message may carry a fault and no payload at all (busy, bad signature, ...).
            val fault = message.signedMessageStatus?.takeIf { it.isRefusal }
            val encrypted = message.signature_data?.AES_GCM_Response_data != null
            if (!encrypted && fault == null) return false
            val key = message.request_uuid.toByteArray().toHex()
            val pending = pendingCommands.remove(key) ?: return false
            commands.replied(key, fault, deviceTimestamp)
            if (!encrypted) {
                log("${name()}: ${pending.kind.name.lowercase()} rejected (${fault?.signed_message_fault?.name})")
                return true
            }
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
            CommandRecords.refusalIn(pending.reason, pending.domain, plaintext)?.let { commands.result(it, deviceTimestamp) }
            when (pending.kind) {
                CommandKind.WAKE -> {
                    val status = runCatching { TeslaVcsec.parseCommandStatus(plaintext) }.getOrNull()
                    log("${name()}: wake ${status?.name ?: "response received"}")
                }

                CommandKind.CHARGE -> handleChargeResponse(plaintext, deviceTimestamp)

                CommandKind.DRIVE -> {
                    stateReplies.onDrive(plaintext, deviceTimestamp)
                    requestFollowUp(TeslaCommands.buildClosuresStateRequest(), CommandKind.CLOSURES)
                }

                CommandKind.CLOSURES -> {
                    stateReplies.onClosures(plaintext, deviceTimestamp)
                    requestFollowUp(TeslaCommands.buildClimateStateRequest(), CommandKind.CLIMATE)
                }

                CommandKind.CLIMATE -> stateReplies.onClimate(plaintext, deviceTimestamp)
            }
            return true
        }

        private fun handleChargeResponse(
            plaintext: ByteArray,
            deviceTimestamp: Instant,
        ) {
            val charge = runCatching { TeslaCommands.parseChargeState(plaintext) }.getOrNull()
            if (charge == null) {
                val status = runCatching { TeslaCommands.parseActionStatus(plaintext) }.getOrNull()
                log("${name()}: charge response missing data" + (status?.let { " ($it)" } ?: ""))
                return
            }
            val previous = _state.value.connections[address]?.charge
            updateConnection(address) {
                it.copy(charge = charge, chargeAtMillis = deviceTimestamp.toEpochMilli())
            }
            historyStore.record(bleName, charge, deviceTimestamp, latestRssi(address))
            infotainmentPolicy.onChargeReading(charge.chargingStateKind)
            if (previous?.battery_level != charge.battery_level ||
                previous?.chargingStateKind != charge.chargingStateKind
            ) {
                log("${name()}: SOC ${charge.battery_level}% (${charge.chargingStateKind?.let(::chargingStateText) ?: "unknown"})")
            }
            requestFollowUp(TeslaCommands.buildDriveStateRequest(), CommandKind.DRIVE)
        }

        fun pairKey() {
            if (phase != ConnectionPhase.READY) {
                log("No selected car ready for pairing")
                return
            }
            val storedKeyId = keyStore.keyId(bleName)
            if (storedKeyId == null) {
                // First pairing for this car: generate its key now.
                pairWithFreshKey()
                return
            }
            // The car may already have this key. Adding it again would ask for
            // the card and can fail, so check the whitelist first.
            pendingPairCheck = true
            pairingKeyId = storedKeyId.toHex()
            setPairing(PairingPhase.CHECKING)
            log("${name()}: checking whether the key is already enrolled")
            transmitVcsec(TeslaVcsec.buildWhitelistInfoRequest(), Command.Reason.PAIRING)
            handler.removeCallbacks(pairingTimeout)
            handler.postDelayed(pairingTimeout, PAIRING_TIMEOUT_MS)
        }

        /**
         * Starts the add-key flow with a freshly generated key for this car.
         * A key the car no longer recognizes is never re-enrolled: it may have
         * been removed after a phone theft, and re-enrolling it would re-arm
         * the stolen copy.
         */
        private fun pairWithFreshKey() {
            val keyPair = keyStore.generate(bleName)
            if (keyPair == null) {
                // Enrolling a key the app may not have kept would orphan the
                // car's key slot, so pairing stops here.
                setPairing(PairingPhase.ERROR)
                log("${name()}: could not save a new key; pairing not started")
                return
            }
            // The previous key is gone; its cached slot is stale.
            updateVehicle(bleName) { it.copy(keySlot = null) }
            updateConnection(address) { it.copy(keySlot = null) }
            sendAddKey(keyPair)
        }

        private fun sendAddKey(keyPair: TeslaKeyPair) {
            val keyId = keyPair.keyId.toHex()
            setPairing(PairingPhase.SENDING, keyId)
            log("${name()}: pairing key $keyId")
            val request = TeslaPairing.buildAddKeyRequest(keyPair.publicKeyRaw)
            if (!transmit(request, CommandRecords.addKey(Command.Reason.PAIRING, request))) {
                setPairing(PairingPhase.ERROR)
                log("${name()}: failed to send pairing request")
            } else {
                handler.removeCallbacks(pairingTimeout)
                handler.postDelayed(pairingTimeout, PAIRING_TIMEOUT_MS)
                whitelistPollAttempts = 0
                handler.removeCallbacks(whitelistPoll)
                handler.postDelayed(whitelistPoll, WHITELIST_POLL_MS)
            }
        }

        private fun setPairing(
            phase: PairingPhase,
            keyId: String? = pairingKeyId,
        ) {
            pairingPhase = phase
            pairingKeyId = keyId
            updateConnection(address) { it.copy(pairing = phase, pairingKeyId = keyId) }
        }

        /**
         * Once this car's key is known to be unusable, drops what still shows
         * the car as paired (cached slot, a stale OK) so it offers pairing again.
         */
        private fun forgetEnrollment(reason: String) {
            val shownPaired =
                vehicles[bleName]?.keySlot != null ||
                    _state.value.connections[address]?.keySlot != null ||
                    pairingPhase == PairingPhase.OK
            if (!shownPaired) return
            updateVehicle(bleName) { it.copy(keySlot = null) }
            updateConnection(address) { it.copy(keySlot = null) }
            if (pairingPhase == PairingPhase.OK) setPairing(PairingPhase.IDLE, null)
            log("${name()}: $reason; pair again")
        }

        fun requestKeySlot() {
            if (_state.value.connections[address]?.keySlot != null) return
            if (!keyStore.hasKey(bleName)) return
            transmitVcsec(TeslaVcsec.buildWhitelistInfoRequest(), Command.Reason.KEY_LOOKUP)
        }

        private fun requestNextKeySlot() {
            val slot = keySlotQueue.firstOrNull()
            if (slot == null) {
                log("${name()}: could not locate our key slot")
                return
            }
            keySlotQueue = keySlotQueue.drop(1)
            transmitVcsec(TeslaVcsec.buildWhitelistEntryRequest(slot), Command.Reason.KEY_LOOKUP)
        }

        private fun handlePairingResponse(message: ByteArray): Boolean {
            val pairing =
                runCatching { TeslaPairing.parseAddKeyResponse(message) }.getOrNull()
                    ?: return false
            handler.removeCallbacks(pairingTimeout)
            val phase =
                when (pairing) {
                    TeslaPairing.Result.OK -> PairingPhase.OK
                    TeslaPairing.Result.WAITING_FOR_CARD -> PairingPhase.WAITING_FOR_CARD
                    TeslaPairing.Result.ERROR -> PairingPhase.ERROR
                }
            pairingPhase = phase
            updateConnection(address) { it.copy(pairing = phase, pairingKeyId = pairingKeyId) }
            when (phase) {
                PairingPhase.OK -> {
                    // The car confirmed the key: save the vehicle, open a
                    // session, and pull the whitelist so the slot resolves.
                    rememberVehicle(address)
                    startSession()
                    requestKeySlot()
                }

                PairingPhase.ERROR -> {
                    // The car may already have the key; a whitelist check
                    // clears the failure if it is enrolled.
                    requestKeySlot()
                }

                else -> Unit
            }
            log("${name()}: pairing ${pairing.name.lowercase()}")
            return true
        }

        private fun handleWhitelistInfo(whitelist: WhitelistInfo) {
            val storedKeyId = keyStore.keyId(bleName)
            val keyId = storedKeyId?.toHex()
            val enrolled =
                storedKeyId != null &&
                    whitelist.whitelistEntries.any {
                        it.publicKeySHA1
                            .toByteArray()
                            .copyOf(storedKeyId.size)
                            .contentEquals(storedKeyId)
                    }
            if (storedKeyId != null && enrolled && keyId != null) {
                handler.removeCallbacks(whitelistPoll)
                pendingPairCheck = false
                when (pairingPhase) {
                    PairingPhase.SENDING, PairingPhase.WAITING_FOR_CARD, PairingPhase.CHECKING -> {
                        setPairing(PairingPhase.OK, keyId)
                        infotainmentPolicy.onFreshStart(System.currentTimeMillis())
                        log("${name()}: key enrolled (${whitelist.numberOfEntries} keys)")
                    }

                    PairingPhase.ERROR -> {
                        // The car has the key after all; drop the stale failure.
                        setPairing(PairingPhase.IDLE, null)
                        log("${name()}: key is enrolled; pairing state recovered")
                    }

                    else -> Unit
                }
                rememberVehicle(address)
                // A freshly enrolled key can open sessions now.
                startSession()
                resolveKeySlots(whitelist, storedKeyId)
                return
            }
            if (pendingPairCheck) {
                // Never re-enroll a key the car no longer has: it may have been
                // removed after a theft, so pairing enrolls a fresh key.
                pendingPairCheck = false
                log("${name()}: key not enrolled; generating a new key for pairing")
                pairWithFreshKey()
                return
            }
            if (storedKeyId != null && !enrolled && whitelist.isComplete(storedKeyId.size)) {
                // Only a whole listing proves the car no longer has the key
                // (removed in the car, or restored here from another phone).
                forgetEnrollment("the car no longer lists this phone's key")
            }
            if (whitelistPollAttempts == 1) {
                log("${name()}: whitelist has ${whitelist.numberOfEntries} keys")
            }
        }

        private fun resolveKeySlots(
            whitelist: WhitelistInfo,
            storedKeyId: ByteArray,
        ) {
            val index =
                whitelist.whitelistEntries.indexOfFirst {
                    it.publicKeySHA1
                        .toByteArray()
                        .copyOf(storedKeyId.size)
                        .contentEquals(storedKeyId)
                }
            val slots = occupiedSlots(whitelist.slotMask)
            keySlotQueue =
                if (index in slots.indices) {
                    listOf(slots[index]) + slots.filterIndexed { i, _ -> i != index }
                } else {
                    slots
                }
            requestNextKeySlot()
        }

        private fun handleWhitelistEntry(entry: WhitelistEntryInfo) {
            val storedPublicKey = keyStore.publicKeyRaw(bleName)
            val storedKeyId = keyStore.keyId(bleName)
            val matches =
                storedPublicKey != null &&
                    storedKeyId != null &&
                    (
                        entry.publicKey
                            ?.PublicKeyRaw
                            ?.toByteArray()
                            ?.contentEquals(storedPublicKey) == true ||
                            entry.keyId
                                ?.publicKeySHA1
                                ?.toByteArray()
                                ?.copyOf(storedKeyId.size)
                                ?.contentEquals(storedKeyId) == true
                    )
            if (matches) {
                keySlotQueue = emptyList()
                updateConnection(address) { it.copy(keySlot = entry.slot) }
                updateVehicle(bleName) { it.copy(keySlot = entry.slot) }
                if (pairingPhase == PairingPhase.ERROR) {
                    // The slot proves enrollment; clear the stale failure.
                    setPairing(PairingPhase.IDLE, null)
                }
                log("${name()}: key slot ${entry.slot}")
            } else {
                requestNextKeySlot()
            }
        }

        fun onMessage(message: ByteArray) {
            // The phone's clock when this reply arrived, read once: both BLE
            // logs keep it beside the car's reply (ADR-0008). Whole milliseconds,
            // as stored before; Instant.now() can carry finer digits.
            val deviceTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
            if (handleSessionInfo(message)) return
            if (handleEncryptedResponse(message, deviceTimestamp)) return
            if (handlePairingResponse(message)) return

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
                // VCSEC replies carry no time: the phone's clock at receipt is
                // the status log's timeline (ADR-0008).
                val rssi = latestRssi(address)
                historyStore.recordStatus(bleName, status, deviceTimestamp, rssi, firstAfterConnect = firstStatusAfterConnect)
                firstStatusAfterConnect = false
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
                // Track SOC for every awake car that can answer, not just the
                // selected one; skip cars with no VIN (no session). The policy
                // decides when a read is due so the car can fall asleep (ADR-0009).
                // A read can be attempted when a session exists or can be started on demand.
                val canRead = sessions.containsKey(Domain.DOMAIN_INFOTAINMENT) || vin().length == Vehicle.VIN_LENGTH
                if (status.asleep) {
                    infotainmentGate.onAsleep()
                    dropPendingInfotainment()
                }
                if (infotainmentPolicy.onStatus(status, canRead, System.currentTimeMillis())) requestChargeState()
            } else {
                log("${name()}: RX ${message.size} bytes ${message.toHex()}")
            }
        }

        private fun listener() =
            object : TeslaTransport.Listener {
                override fun onPhase(phase: ConnectionPhase) {
                    if (phase == ConnectionPhase.FAILED || phase == ConnectionPhase.DISCONNECTED) {
                        recordConnection(this@VehicleLink, ConnectionEvent.State.DISCONNECTED)
                        transport?.close()
                        transport = null
                        handler.removeCallbacks(poll)
                        handler.removeCallbacks(rssiPoll)
                        infotainmentGate.onLinkDropped()
                        pendingSessions.clear()
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
                        firstStatusAfterConnect = true
                        infotainmentPolicy.onLinkReady(System.currentTimeMillis())
                        recordConnection(this@VehicleLink, ConnectionEvent.State.CONNECTED)
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

    /**
     * True when the listing is whole: the car reported keys, listed an ID for
     * each occupied slot, and every ID is long enough to compare with ours.
     */
    private fun WhitelistInfo.isComplete(keyIdSize: Int): Boolean =
        numberOfEntries > 0 &&
            whitelistEntries.size == numberOfEntries &&
            slotMask.countOneBits() == numberOfEntries &&
            whitelistEntries.all { it.publicKeySHA1.size >= keyIdSize }

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

        /** A scan stops once no new car has appeared for this long. */
        const val SCAN_QUIET_MS = 30_000L
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
