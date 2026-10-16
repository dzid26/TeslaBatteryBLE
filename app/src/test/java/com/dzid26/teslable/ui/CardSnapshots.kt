// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.android.resources.Density
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.PairingKeyStore
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.protocol.ChargingStateKind
import com.dzid26.teslable.protocol.ShiftStateKind
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.Rule
import org.junit.Test

/**
 * Fast JVM snapshots of the two battery cards, light AND dark: the cars-list
 * card ([VehicleCard]) and the car-view card ([HeroCard]), one image per
 * state (fresh, stale, asleep, disconnected, reading spinner, no reading) per
 * theme. 24 PNGs total, named `<card>_<state>_light.png` /
 * `<card>_<state>_dark.png` (theme is always the last segment).
 *
 * There are no committed goldens: `recordPaparazziDebug` renders the PNGs
 * and the PR workflow diffs them before/after the pushed commit, posting
 * light by default and dark only when it differs too. Fixtures mirror the
 * unit-test patterns (a fixed NOW, the same [TeslaConnection]/[ChargeState]/
 * [VehicleStatus] shapes), and the cards get a fixed clock plus a forced
 * spinner so the images are deterministic.
 *
 * The three full screens ([ConnectionsScreen], [CarScreen], [SettingsScreen])
 * ride the same flow with one representative state each (a connected car with
 * a fresh reading and seeded history, like the old emulator set); state
 * coverage still comes from the card matrix. The screens get the same fixed
 * clock (threaded through to the cards and the history chart); everything
 * else on them is inert under Paparazzi (no-op callbacks, no back presses,
 * no store writes).
 */
class CardSnapshots {
    /**
     * One rule for every snapshot; [snapCard] and [snapScreen] switch its
     * device and rendering mode up front via `unsafeUpdateConfig` (two
     * Paparazzi rules cannot live in one class — layoutlib holds a single
     * scene per thread). Each helper sets both values every time, so the
     * renders never depend on test order.
     */
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun vehicleCardFresh() {
        snapCard("vehicle-card_fresh") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardStale() {
        snapCard("vehicle-card_stale") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - STALE_MINUTES * MINUTE)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardAsleep() {
        snapCard("vehicle-card_asleep") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - ASLEEP_MINUTES * MINUTE, status = asleep)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardDisconnected() {
        snapCard("vehicle-card_disconnected") {
            VehicleCard(
                row =
                    listRow(
                        TeslaConnection(
                            address = ADDRESS,
                            name = BLE_NAME,
                            phase = ConnectionPhase.DISCONNECTED,
                        ),
                        lastKnown = storedSample(STALE_MINUTES),
                    ),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun vehicleCardSpinner() {
        snapCard("vehicle-card_spinner") {
            VehicleCard(
                row = listRow(liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, readInFlight = true)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
                spinnerOverride = true,
            )
        }
    }

    @Test
    fun vehicleCardNoReading() {
        snapCard("vehicle-card_no-reading") {
            VehicleCard(
                row = listRow(liveConnection(charge = null)),
                onOpen = {},
                onEditVin = {},
                onWake = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardFresh() {
        snapCard("hero-card_fresh") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardCharging() {
        val charging =
            liveCharge.copy(
                charging_state = ChargeState.ChargingState(Charging = Void()),
                charger_voltage = 240,
                charger_actual_current = 16,
                charge_current_request = 32,
            )
        snapCard("hero-card_charging") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, charge = charging),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardChargeCost() {
        val charging =
            liveCharge.copy(
                charging_state = ChargeState.ChargingState(Charging = Void()),
                charger_voltage = 240,
                charger_actual_current = 16,
                charge_current_request = 32,
                charge_energy_added = 12.5f,
            )
        // The cost is formatted in the default locale; pin it so the render does not depend on the machine.
        java.util.Locale.setDefault(java.util.Locale.US)
        snapCard("hero-card_charge-cost") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, charge = charging),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
                pricePerKwh = 0.30f,
            )
        }
    }

    @Test
    fun heroCardStale() {
        snapCard("hero-card_stale") {
            HeroCard(
                connection = liveConnection(readAt = NOW - STALE_MINUTES * MINUTE),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardAsleep() {
        snapCard("hero-card_asleep") {
            HeroCard(
                connection = liveConnection(readAt = NOW - ASLEEP_MINUTES * MINUTE, status = asleep),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardDisconnected() {
        snapCard("hero-card_disconnected") {
            HeroCard(
                connection =
                    TeslaConnection(
                        address = ADDRESS,
                        name = BLE_NAME,
                        phase = ConnectionPhase.DISCONNECTED,
                        disconnectedAtMillis = NOW - STALE_MINUTES * MINUTE,
                    ),
                // A disconnected car has no live scan entry (each discovery
                // wipes the list), so no RSSI — just the disconnect age.
                advert = null,
                vehicle = vehicle,
                history = listOf(storedSample(STALE_MINUTES)),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun heroCardSpinner() {
        snapCard("hero-card_spinner") {
            HeroCard(
                connection = liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE, readInFlight = true),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
                spinnerOverride = true,
            )
        }
    }

    @Test
    fun heroCardNoReading() {
        snapCard("hero-card_no-reading") {
            HeroCard(
                connection = liveConnection(charge = null),
                advert = advert,
                vehicle = vehicle,
                history = emptyList(),
                statusHistory = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardCharged() {
        snapCard("history-card_charged") {
            BatteryHistoryCard(
                samples = screenSamples,
                drives = emptyList(),
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardGap() {
        snapCard("history-card_gap") {
            BatteryHistoryCard(
                samples = gapSamples,
                drives = emptyList(),
                nowMillis = NOW,
                initialRange = HistoryRange.ALL,
            )
        }
    }

    @Test
    fun historyCardParkedDrain() {
        // A parked car losing a point every few hours: the "Parked" projection
        // line and its rate, no charge in sight.
        snapCard("history-card_parked-drain") {
            BatteryHistoryCard(
                samples = parkedSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargingProjection() {
        // Charging toward the limit: the green line up, the parked drain muted.
        snapCard("history-card_charging-projection") {
            BatteryHistoryCard(
                samples = chargingSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun historyCardChargingSixHours() {
        // The short range puts the chart's right edge only 3 h ahead, so the beam is
        // cut near the line's start: it must stay a visible sliver, not round away.
        snapCard("history-card_charging-6h") {
            BatteryHistoryCard(
                samples = chargingSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
                initialRange = HistoryRange.SIX_HOURS,
            )
        }
    }

    @Test
    fun historyCardChargingStalledLevel() {
        // The whole-number level has not ticked for a while but the rated range
        // climbs: the charge line still draws, from the miles.
        snapCard("history-card_charging-stalled-level") {
            BatteryHistoryCard(
                samples = stalledSamples,
                drives = parkedSamples.map { parkedDrive(it) },
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun screenCarsList() {
        snapScreen("screen-cars-list") {
            ConnectionsScreen(
                state = screenState,
                history = screenSamples,
                permissionsGranted = true,
                locationServicesEnabled = true,
                onRequestPermissions = {},
                onToggleScan = {},
                onToggleTracking = {},
                onOpen = { _, _ -> },
                onEditVin = {},
                onWake = {},
                onOpenSettings = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun screenCarDetail() {
        snapScreen("screen-car-detail") {
            CarScreen(
                state = screenState,
                vehicle = vehicle,
                advert = advert,
                address = ADDRESS,
                history = screenSamples,
                driveHistory = emptyList(),
                statusHistory = emptyList(),
                onBack = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                nowMillis = NOW,
            )
        }
    }

    @Test
    fun screenSettings() {
        snapScreen("screen-settings") {
            SettingsScreen(
                keyStore = PairingKeyStore(LocalContext.current),
                vehicles = listOf(vehicle),
                onClearPairingCache = {},
                onOpenAbout = {},
                onBack = {},
                versionName = "0.0.0-snapshot",
            )
        }
    }

    /**
     * One state, three images: the card in the fixed light scheme and in the
     * fixed dark scheme, plus a narrow Jelly Star render in light, all
     * SHRINK-wrapped so each bitmap hugs the card (full-width x
     * content-height, no black void). The theme is always the last name
     * segment, so the workflow can group by state.
     */
    private fun snapCard(
        name: String,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            // Narrowest phone Paparazzi ships (360dp): the width under test.
            deviceConfig = DeviceConfig.NEXUS_5,
            renderingMode = SessionParams.RenderingMode.SHRINK,
        )
        paparazzi.snapshot(name + "_light") {
            CardFrame(darkTheme = false, content = content)
        }
        paparazzi.snapshot(name + "_dark") {
            CardFrame(darkTheme = true, content = content)
        }
        // The Jelly Star (3-inch, 480x854 @320dpi → 240dp) is narrower than
        // anything Paparazzi ships, so hand-built via copy(); light only,
        // collapsed in the PR comment next to dark.
        paparazzi.unsafeUpdateConfig(
            deviceConfig = jellyStar,
            renderingMode = SessionParams.RenderingMode.SHRINK,
        )
        paparazzi.snapshot(name + "_jelly_light") {
            CardFrame(darkTheme = false, content = content)
        }
    }

    @Test
    fun screenCarDetailLearnt() {
        // Three charge sessions and a climb through 40-70% read in quarter
        // steps: the health card is past Learning and shows the fitted full
        // range, capacity and the rated constant.
        snapScreen("screen-car-detail-learnt") {
            CarScreen(
                state = learntState,
                vehicle = vehicle,
                advert = advert,
                address = ADDRESS,
                history = learntSamples,
                driveHistory = emptyList(),
                statusHistory = emptyList(),
                onBack = {},
                onPair = {},
                onRefresh = {},
                onOpenSettings = {},
                onToggleTracking = {},
                nowMillis = NOW,
            )
        }
    }

    /**
     * One screen, two images: the full screen in NORMAL mode on a tall
     * S24-Ultra-like phone (Pixel 7 Pro preset: 1440x3120, 19.5:9
     * portrait), so scrolled content gets vertical room. Light and dark.
     */
    private fun snapScreen(
        name: String,
        content: @Composable () -> Unit,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_7_PRO,
            renderingMode = SessionParams.RenderingMode.NORMAL,
        )
        paparazzi.snapshot(name + "_light") {
            ScreenFrame(darkTheme = false, content = content)
        }
        paparazzi.snapshot(name + "_dark") {
            ScreenFrame(darkTheme = true, content = content)
        }
    }

    companion object {
        private const val NOW = 1_760_000_000_000L
        private const val MINUTE = 60_000L
        private const val HOUR = 3_600_000L
        private const val FRESH_MINUTES = 1L
        private const val ASLEEP_MINUTES = 3L
        private const val STALE_MINUTES = 12L
        private const val LIVE_PERCENT = 62
        private const val STORED_PERCENT = 78
        private const val CHARGE_LIMIT = 80
        private const val LIVE_RANGE_MILES = 212.5f
        private const val STORED_RANGE_MILES = 245.5f
        private const val ADDRESS = "AA:BB:CC:DD:EE:01"
        private const val BLE_NAME = "S1f094173C"

        private fun liveConnection(
            readAt: Long? = null,
            status: VehicleStatus? = awake,
            charge: ChargeState? = liveCharge,
            readInFlight: Boolean = false,
        ) = TeslaConnection(
            address = ADDRESS,
            name = BLE_NAME,
            phase = ConnectionPhase.READY,
            status = status,
            charge = charge,
            chargeAtMillis = readAt,
            readInFlight = readInFlight,
        )

        private fun listRow(
            connection: TeslaConnection?,
            lastKnown: BatterySample? = null,
        ) = VehicleRow(
            bleName = BLE_NAME,
            address = ADDRESS,
            title = vehicle.title,
            vehicle = vehicle,
            connection = connection,
            advert = advert,
            lastKnown = lastKnown,
        )

        private fun storedSample(readMinutesAgo: Long) =
            BatterySample(
                timestampMillis = NOW - readMinutesAgo * MINUTE,
                batteryLevel = STORED_PERCENT,
                chargingState = null,
                chargeLimit = null,
                vehicleId = BLE_NAME,
                ratedRangeMiles = STORED_RANGE_MILES,
                readAtMillis = NOW - readMinutesAgo * MINUTE,
            )

        private fun historySample(
            hoursAgo: Long,
            percent: Int,
        ) = BatterySample(
            timestampMillis = NOW - hoursAgo * HOUR,
            batteryLevel = percent,
            chargingState = null,
            chargeLimit = null,
            vehicleId = BLE_NAME,
            readAtMillis = NOW - hoursAgo * HOUR,
        )

        private fun parkedDrive(sample: BatterySample) =
            DriveSample(
                timestampMillis = sample.timestampMillis + MINUTE,
                vehicleId = BLE_NAME,
                shiftState = ShiftStateKind.P,
                speed = 0,
                power = 0,
                odometerInHundredthsOfAMile = ODOMETER,
            )

        private fun projectionSample(
            minutesAgo: Long,
            percent: Int,
            charging: Boolean,
            ratedMiles: Float,
        ) = BatterySample(
            timestampMillis = NOW - minutesAgo * MINUTE,
            batteryLevel = percent,
            chargingState = if (charging) ChargingStateKind.Charging else null,
            chargeLimit = if (charging) CHARGE_LIMIT else null,
            vehicleId = BLE_NAME,
            ratedRangeMiles = ratedMiles,
            readAtMillis = NOW - minutesAgo * MINUTE,
        )

        private const val ODOMETER = 1_234_500

        private val parkedSamples =
            listOf(
                projectionSample(20 * 60L, 60, charging = false, ratedMiles = 180f),
                projectionSample(16 * 60L, 59, charging = false, ratedMiles = 177f),
                projectionSample(12 * 60L, 58, charging = false, ratedMiles = 174f),
                projectionSample(8 * 60L, 57, charging = false, ratedMiles = 171f),
                projectionSample(4 * 60L, 56, charging = false, ratedMiles = 168f),
                projectionSample(0L, 55, charging = false, ratedMiles = 165f),
            )

        // The AC supply sagging and recovering around 240 V: the beam around the green line.
        private val chargingSamples =
            parkedSamples.dropLast(1) +
                listOf(
                    projectionSample(60L, 55, charging = true, ratedMiles = 165f).copy(chargerVoltage = 245),
                    projectionSample(30L, 58, charging = true, ratedMiles = 174f).copy(chargerVoltage = 215),
                    projectionSample(0L, 61, charging = true, ratedMiles = 183f).copy(chargerVoltage = 232),
                )

        private val stalledSamples =
            parkedSamples.dropLast(1) +
                listOf(
                    projectionSample(30L, 55, charging = true, ratedMiles = 165f).copy(chargerVoltage = 215),
                    projectionSample(15L, 55, charging = true, ratedMiles = 166.5f).copy(chargerVoltage = 245),
                    projectionSample(0L, 55, charging = true, ratedMiles = 168f).copy(chargerVoltage = 232),
                )

        private fun chargeSample(
            minutesAgo: Long,
            percent: Int,
            energyAddedKwh: Float?,
            milesAddedRated: Float?,
            chargingState: ChargingStateKind?,
        ) = BatterySample(
            timestampMillis = NOW - minutesAgo * MINUTE,
            batteryLevel = percent,
            chargingState = chargingState,
            chargeLimit = null,
            vehicleId = BLE_NAME,
            chargeEnergyAdded = energyAddedKwh,
            chargeMilesAddedRated = milesAddedRated,
            readAtMillis = NOW - minutesAgo * MINUTE,
        )

        private val liveCharge =
            ChargeState(
                battery_level = LIVE_PERCENT,
                battery_range = LIVE_RANGE_MILES,
                charge_limit_soc = CHARGE_LIMIT,
            )
        private val awake =
            VehicleStatus(
                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
                userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
            )
        private val asleep = awake.copy(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)
        private val vehicle =
            Vehicle(
                bleName = BLE_NAME,
                address = ADDRESS,
                gattName = "Model 3",
                displayName = "Model 3",
                // Enrolled, so the app-key card stays out of the car screen.
                keySlot = 3,
            )
        private val advert = TeslaAdvert(name = BLE_NAME, address = ADDRESS, rssi = -70)

        /**
         * The narrow-width render: Jelly Star dimensions (3-inch, 480x854
         * @320dpi → 240dp), hand-built because no preset goes that narrow.
         * Light only; the PR comment keeps it collapsed next to dark.
         */
        private val jellyStar =
            DeviceConfig.NEXUS_5.copy(
                screenWidth = 480,
                screenHeight = 854,
                xdpi = 327,
                ydpi = 327,
                density = Density.XHIGH,
            )
        private val screenState =
            BleUiState(
                trackingEnabled = true,
                devices = listOf(advert),
                connections = mapOf(ADDRESS to liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE)),
                vehicles = listOf(vehicle),
                log = listOf(LogEntry(vehicleId = BLE_NAME, message = "$BLE_NAME · Connected · 62%")),
            )

        // The live reading agrees with the learnt 270-mile scale: 62.4% is
        // 168.5 rated miles, which the fit shows as 62.4% against the int 62.
        private val learntState =
            screenState.copy(
                connections =
                    mapOf(
                        ADDRESS to
                            liveConnection(
                                readAt = NOW - FRESH_MINUTES * MINUTE,
                                charge =
                                    ChargeState(
                                        battery_level = LIVE_PERCENT,
                                        battery_range = 168.5f,
                                        charge_limit_soc = CHARGE_LIMIT,
                                    ),
                            ),
                    ),
            )

        // Drained to 68%, then one backbone charge (68 → 78%, a 10% swing
        // with energy and rated-miles growth) so the health card renders
        // populated — usable capacity + full range from the session's rated
        // constant — while honestly staying Learning at 1 of 3 sessions.
        // No charge limit, so the chart shows the run with no projection;
        // the Complete + parked tail ends the session for the stats line.
        private val screenSamples =
            listOf(
                historySample(6L, 72),
                historySample(4L, 70),
                historySample(2L, 68),
                chargeSample(50L, 68, 1.0f, 4.0f, ChargingStateKind.Charging),
                chargeSample(35L, 73, 3.2f, 13.0f, ChargingStateKind.Charging),
                chargeSample(20L, 78, 5.5f, 22.0f, ChargingStateKind.Charging),
                chargeSample(15L, 78, null, null, ChargingStateKind.Complete),
                historySample(0L, 77),
            )

        // A rated-range climb on a 270-mile scale (0.25% steps, the level
        // rounds the true SOC) with three charges of 10% each: enough steps
        // for the scale to pin and enough sessions to leave Learning.
        private val learntSamples =
            (0..120)
                .map { step ->
                    val soc = 40.1f + step * 0.25f
                    BatterySample(
                        timestampMillis = NOW - (200L - step) * MINUTE,
                        batteryLevel = Math.round(soc),
                        chargingState = null,
                        chargeLimit = null,
                        vehicleId = BLE_NAME,
                        ratedRangeMiles = 2.7f * soc,
                        readAtMillis = NOW - (200L - step) * MINUTE,
                    )
                }.plus(
                    (0..2)
                        .flatMap { n ->
                            val base = 60L * (n + 2) * 24 // one charge each of the days before
                            listOf(
                                chargeSample(base + 30, 50, 0f, 0f, ChargingStateKind.Charging),
                                chargeSample(base + 15, 55, 3.0f, 13.5f, ChargingStateKind.Charging),
                                chargeSample(base, 60, 6.0f, 27f, ChargingStateKind.Charging),
                                chargeSample(base - 5, 60, null, null, ChargingStateKind.Complete),
                            )
                        },
                ).sortedBy { it.timestampMillis }

        // A charge five days before the newest reading, with nothing recorded
        // in between: the stats must name the charge (47%, five days ago) and
        // the drain since (19%), not the first reading after it. This is the
        // gap case the charge-stats anchor fix covers.
        private val gapSamples =
            listOf(
                historySample(6L * 24, 44),
                chargeSample(5L * 24 * 60 + 2, 46, 30f, 120f, ChargingStateKind.Charging),
                chargeSample(5L * 24 * 60, 47, 32f, 128f, ChargingStateKind.Charging),
                historySample(0L, 28),
            )
    }
}

/**
 * The snapshot frame: a fixed light or dark scheme (never the dynamic color,
 * which Paparazzi has no real context for) plus a surface, so the cards
 * render exactly as they would on a matching phone screen.
 */
@Composable
private fun CardFrame(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(all = 16.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * The full-screen frame: the same fixed schemes, with the screen filling the
 * device frame as it does on the phone.
 */
@Composable
private fun ScreenFrame(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}
