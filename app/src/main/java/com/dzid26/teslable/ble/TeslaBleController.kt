// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.CommandRecords
import com.dzid26.teslable.core.history.ConnectionEvent
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.infotainment.InfotainmentPollPolicy
import com.dzid26.teslable.core.infotainment.InfotainmentSessionGate
import com.dzid26.teslable.history.HistoryStore
import com.dzid26.teslable.protocol.AntiReplayWindow
import com.dzid26.teslable.protocol.PairingFlow
import com.dzid26.teslable.protocol.PairingHost
import com.dzid26.teslable.protocol.PairingKeys
import com.dzid26.teslable.protocol.PairingPhase
import com.dzid26.teslable.protocol.PairingReason
import com.dzid26.teslable.protocol.PairingTimer
import com.dzid26.teslable.protocol.SessionHandshake
import com.dzid26.teslable.protocol.SessionHost
import com.dzid26.teslable.protocol.SessionKeys
import com.dzid26.teslable.protocol.TeslaCommands
import com.dzid26.teslable.protocol.TeslaCrypto
import com.dzid26.teslable.protocol.TeslaKeyPair
import com.dzid26.teslable.protocol.TeslaNames
import com.dzid26.teslable.protocol.TeslaSessionRequests
import com.dzid26.teslable.protocol.TeslaVcsec
import com.dzid26.teslable.protocol.asleep
import com.dzid26.teslable.protocol.chargingStateKind
import com.dzid26.teslable.protocol.isRefusal
import com.dzid26.teslable.protocol.isStaleSession
import com.dzid26.teslable.protocol.isUnknownKey
import com.dzid26.teslable.protocol.locked
import com.dzid26.teslable.protocol.userPresent
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
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
                link.pairing.requestKeySlot()
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
     *
     * [bluetoothJustOn]: a retry that fired while Bluetooth was off never completed, and the backoff may be a minute
     * long by now, so every car that is not connected tries again at once.
     */
    fun ensureConnected(bluetoothJustOn: Boolean = false) {
        if (!_state.value.trackingEnabled) return
        if (!hasBlePermissions(appContext)) return
        if (bluetoothJustOn) links.values.filter { it.phase != ConnectionPhase.READY }.forEach { it.retryNow() }
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
        link.pairing.pairKey()
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

    private data class PendingCommand(
        val domain: Domain,
        val requestId: ByteArray,
        val kind: CommandKind,
        val reason: Command.Reason,
        val window: AntiReplayWindow = AntiReplayWindow(),
        val sentAtMillis: Long = System.currentTimeMillis(),
    )

    private enum class CommandKind {
        WAKE,
        CHARGE,
        DRIVE,
        CLOSURES,
        CLIMATE,
        ;

        /** Part of an Infotainment read (charge, drive, closures, climate). */
        val isRead: Boolean get() = this != WAKE
    }

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
        private val pendingCommands = mutableMapOf<String, PendingCommand>()
        private var wakeAfterSession = false
        var reconnectAttempts = 0
        private var wakeRefreshAttempts = 0
        private var decryptFailures = 0
        private var lastRehandshakeMs = 0L

        /** The next VCSEC status is the first since the link became ready; the status log always keeps it. */
        private var firstStatusAfterConnect = true

        /** Decides when an Infotainment read is due (ADR-0009). */
        private val infotainmentPolicy = InfotainmentPollPolicy()

        /** Whether a due read is waiting for the Infotainment handshake; never left set once it is over. */
        private val infotainmentGate = InfotainmentSessionGate()

        /** Keeps the command log: every request goes out through [transmit], which logs it with its reason (ADR-0008). */
        val commands = CommandRecorder(bleName, historyStore, rssi = { latestRssi(address) })

        /** Whether an Infotainment read is in flight, for the spinner; every exit clears it. */
        private val readInFlight = ReadInFlight { active -> updateConnection(address) { it.copy(readInFlight = active) } }

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
                    expirePendingCommands(System.currentTimeMillis())
                    readInFlight.expire(System.currentTimeMillis())
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

        private val sessionRetry = Runnable { handshake.onRetryTimer() }

        private val pairingTimers: Map<PairingTimer, Runnable> =
            PairingTimer.entries.associateWith { timer -> Runnable { pairing.onTimer(timer) } }

        private val pairingHost: PairingHost =
            object : PairingHost {
                override val isReady: Boolean get() = phase == ConnectionPhase.READY

                override fun log(message: String): Unit = this@VehicleLink.log("${name()}: $message")

                override fun transmitVcsec(
                    request: ByteArray,
                    reason: PairingReason,
                ) {
                    this@VehicleLink.transmitVcsec(request, CommandRecords.reasonOf(reason))
                }

                override fun transmitAddKey(request: ByteArray): Boolean =
                    transmit(request, CommandRecords.addKey(Command.Reason.PAIRING, request))

                override fun publishPairing(
                    phase: PairingPhase,
                    keyId: String?,
                ) {
                    updateConnection(address) { it.copy(pairing = phase, pairingKeyId = keyId) }
                }

                override fun publishKeySlot(slot: Int?) {
                    updateConnection(address) { it.copy(keySlot = slot) }
                    updateVehicle(bleName) { it.copy(keySlot = slot) }
                }

                override fun hasConnectionKeySlot(): Boolean = _state.value.connections[address]?.keySlot != null

                override fun hasShownKeySlot(): Boolean = hasConnectionKeySlot() || vehicles[bleName]?.keySlot != null

                override fun onKeyConfirmed() {
                    rememberVehicle(address)
                    startSession()
                }

                override fun onFreshlyEnrolled() {
                    infotainmentPolicy.onFreshStart(System.currentTimeMillis())
                }

                override fun schedule(
                    timer: PairingTimer,
                    delayMs: Long,
                ) {
                    handler.removeCallbacks(pairingTimers.getValue(timer))
                    handler.postDelayed(pairingTimers.getValue(timer), delayMs)
                }

                override fun cancel(timer: PairingTimer) {
                    handler.removeCallbacks(pairingTimers.getValue(timer))
                }
            }

        /** Enrolls this phone's key and finds its slot; the state machine lives in core. */
        val pairing: PairingFlow =
            PairingFlow(
                keys =
                    object : PairingKeys {
                        override fun hasKey(): Boolean = keyStore.hasKey(bleName)

                        override fun publicKeyRaw(): ByteArray? = keyStore.publicKeyRaw(bleName)

                        override fun keyId(): ByteArray? = keyStore.keyId(bleName)

                        override fun generate(): TeslaKeyPair? = keyStore.generate(bleName)
                    },
                host = pairingHost,
            )

        private val sessionHost: SessionHost =
            object : SessionHost {
                override val isReady: Boolean get() = phase == ConnectionPhase.READY
                override val isAwake: Boolean get() = this@VehicleLink.isAwake()
                override val vin: String? get() = this@VehicleLink.vin().takeIf { it.length == Vehicle.VIN_LENGTH }

                override fun log(message: String) {
                    this@VehicleLink.log("${name()}: $message")
                }

                override fun transmitSessionRequest(request: ByteArray): Boolean =
                    transmit(request, CommandRecords.sessionInfo(Command.Reason.SESSION_HANDSHAKE, request))

                override fun publishSessions(domains: List<String>) {
                    updateConnection(address) { it.copy(sessions = domains) }
                }

                override fun mayRequestInfotainment(awake: Boolean): Boolean = infotainmentGate.mayRequestSession(awake)

                override fun onEstablished(domain: Domain) {
                    rememberVehicle(address)
                    if (domain == Domain.DOMAIN_INFOTAINMENT && infotainmentGate.onSessionEstablished()) {
                        requestChargeState(waitingReadReason)
                    }
                    if (domain == Domain.DOMAIN_VEHICLE_SECURITY && wakeAfterSession) {
                        wakeAfterSession = false
                        sendWake()
                    }
                }

                override fun onGaveUp() {
                    infotainmentGate.onGaveUp()
                }

                override fun onKeyUnusable() {
                    pairing.forgetEnrollment("the stored key can't be used on this phone")
                }

                override fun scheduleRetry(delayMs: Long) {
                    handler.postDelayed(sessionRetry, delayMs)
                }

                override fun cancelRetry() {
                    handler.removeCallbacks(sessionRetry)
                }
            }

        /** Asks the car for sessions and keeps them; the handshake lives in core. */
        private val handshake: SessionHandshake =
            SessionHandshake(
                keys =
                    object : SessionKeys {
                        override fun hasKey(): Boolean = keyStore.hasKey(bleName)

                        override fun publicKeyRaw(): ByteArray? = keyStore.publicKeyRaw(bleName)

                        override fun load(): TeslaKeyPair? = keyStore.load(bleName)
                    },
                host = sessionHost,
                clientAddress = clientAddress,
            )

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

        /** Connects now with a fresh backoff, dropping a connect attempt that can no longer complete. */
        fun retryNow() {
            reconnectAttempts = 0
            if (phase == ConnectionPhase.CONNECTING) phase = ConnectionPhase.DISCONNECTED
            log("${name()}: Bluetooth is back on, reconnecting")
            connect()
        }

        fun close() {
            recordConnection(this, ConnectionEvent.State.DISCONNECTED)
            cancelCallbacks()
            transport?.close()
            transport = null
            phase = ConnectionPhase.IDLE
            handshake.clear()
            pendingCommands.clear()
            pairing.onLinkClosed()
            infotainmentGate.onLinkDropped()
            readInFlight.onLinkDropped()
            wakeAfterSession = false
            updateConnection(address) {
                it.copy(
                    phase = ConnectionPhase.IDLE,
                    sessions = emptyList(),
                    disconnectedAtMillis = System.currentTimeMillis(),
                )
            }
        }

        /** Drops the cached key slot and sessions after the pairing cache was cleared. */
        fun clearCachedPairing() {
            handshake.clear()
            pendingCommands.clear()
            readInFlight.onLinkDropped()
            pairing.onCacheCleared()
            wakeAfterSession = false
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
            handshake.cancelTimer()
            pairing.cancelTimers()
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

        /** True when session requests went out. */
        fun startSession(): Boolean = handshake.start()

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
            if (handshake.has(Domain.DOMAIN_VEHICLE_SECURITY)) {
                handshake.drop(Domain.DOMAIN_VEHICLE_SECURITY)
                handshake.publish()
            }
            wakeAfterSession = true
            log("${name()}: waking with a fresh session")
            startSession()
        }

        private fun sendWake() {
            if (!handshake.has(Domain.DOMAIN_VEHICLE_SECURITY)) {
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
            when (infotainmentGate.onReadDue(isAwake(), handshake.has(Domain.DOMAIN_INFOTAINMENT))) {
                InfotainmentSessionGate.Action.SKIP -> {
                    log("${name()}: car is asleep; wake it first")
                    readInFlight.onSkipped()
                }

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
            if (!isAwake() || !handshake.has(Domain.DOMAIN_INFOTAINMENT)) return
            sendAuthenticated(domain = Domain.DOMAIN_INFOTAINMENT, payload = payload, kind = kind)
        }

        private fun sendAuthenticated(
            domain: Domain,
            payload: ByteArray,
            kind: CommandKind,
            reason: Command.Reason = Command.Reason.FOLLOW_UP,
        ): Boolean {
            val session =
                handshake[domain] ?: run {
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
            if (sent && kind.isRead) readInFlight.onSent(System.currentTimeMillis())
            return sent
        }

        /** Forgets requests the car never answered, so they do not pile up for the life of the link. */
        private fun expirePendingCommands(now: Long) {
            val cutoff = now - PENDING_COMMAND_TTL_MS
            pendingCommands.values.removeAll { it.sentAtMillis < cutoff }
        }

        /** Drops the [domain] session and handshakes again, at most once a minute so a broken link cannot loop. */
        private fun rehandshake(
            domain: Domain,
            why: String,
        ) {
            val now = System.currentTimeMillis()
            if (now - lastRehandshakeMs <= REHANDSHAKE_COOLDOWN_MS) return
            lastRehandshakeMs = now
            decryptFailures = 0
            log("${name()}: re-handshaking ${domain.name} after $why")
            handshake.drop(domain)
            startSession()
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
            // A reply ends the read unless it sends the next request, which turns it back on.
            if (pending.kind.isRead) readInFlight.onReply()
            if (!encrypted) {
                log("${name()}: ${pending.kind.name.lowercase()} rejected (${fault?.signed_message_fault?.name})")
                // Retrying on the old session fails forever; only a new handshake recovers.
                if (fault?.isStaleSession == true) rehandshake(pending.domain, "stale session")
                // The owner removed this phone's key: no session recovers from that.
                if (fault?.isUnknownKey == true) pairing.forgetEnrollment("the car does not know this phone's key")
                return true
            }
            val session = handshake[pending.domain] ?: return true
            val plaintext = session.decrypt(message, pending.requestId, pending.window)
            if (plaintext == null) {
                decryptFailures++
                log("${name()}: response decryption failed (${pending.kind.name.lowercase()})")
                // The car may have rotated its session (reboot, deep sleep).
                if (decryptFailures >= 2) rehandshake(pending.domain, "repeated decryption failures")
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

        fun onMessage(message: ByteArray) {
            // The phone's clock when this reply arrived, read once: both BLE
            // logs keep it beside the car's reply (ADR-0008). Whole milliseconds,
            // as stored before; Instant.now() can carry finer digits.
            val deviceTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
            if (handshake.onMessage(message)) return
            if (handleEncryptedResponse(message, deviceTimestamp)) return
            if (pairing.onMessage(message)) return

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
                val canRead = handshake.has(Domain.DOMAIN_INFOTAINMENT) || vin().length == Vehicle.VIN_LENGTH
                if (status.asleep) {
                    infotainmentGate.onAsleep()
                    handshake.dropPendingInfotainment()
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
                        readInFlight.onLinkDropped()
                        handshake.clearPending()
                        val hadData =
                            _state.value.connections[address]?.let {
                                it.sessions.isNotEmpty() || it.status != null || it.charge != null
                            } == true
                        // Failed is for faults a retry will not fix; a dropped or weak link just reads as disconnected.
                        val display =
                            if (phase == ConnectionPhase.FAILED && !hadData) ConnectionPhase.FAILED else ConnectionPhase.DISCONNECTED
                        this@VehicleLink.phase = display
                        updateConnection(address) {
                            it.copy(phase = display, disconnectedAtMillis = System.currentTimeMillis())
                        }
                        scheduleReconnect()
                        return
                    }
                    this@VehicleLink.phase = phase
                    updateConnection(address) {
                        it.copy(
                            phase = phase,
                            // A reconnect attempt keeps the original drop time; a fresh READY link clears it.
                            disconnectedAtMillis =
                                if (phase == ConnectionPhase.READY) null else it.disconnectedAtMillis,
                        )
                    }
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
                        pairing.requestKeySlot()
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

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val LOG_MAX_LINES = 200
        const val COMMAND_EXPIRES_SECONDS = 5
        const val POLL_MS = 10_000L

        /** A request the car has not answered within twice its validity window never will be. */
        const val PENDING_COMMAND_TTL_MS = 2 * COMMAND_EXPIRES_SECONDS * 1000L
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
    }
}
