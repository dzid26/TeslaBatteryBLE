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
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.LogEntry
import com.dzid26.teslable.ble.PairingKeyStore
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.core.history.BatterySample
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
                    ),
                advert = advert,
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
     * One state, two images: the card in the fixed light scheme and in the
     * fixed dark scheme. The theme is always the last name segment, so the
     * workflow can group by state.
     */
    private fun snapCard(
        name: String,
        content: @Composable () -> Unit,
    ) {
        paparazzi.snapshot(name + "_light") {
            CardFrame(darkTheme = false, content = content)
        }
        paparazzi.snapshot(name + "_dark") {
            CardFrame(darkTheme = true, content = content)
        }
    }

    /** One screen, two images: the full screen, light and dark. */
    private fun snapScreen(
        name: String,
        content: @Composable () -> Unit,
    ) {
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
            )
        private val advert = TeslaAdvert(name = BLE_NAME, address = ADDRESS, rssi = -70)
        private val screenState =
            BleUiState(
                trackingEnabled = true,
                devices = listOf(advert),
                connections = mapOf(ADDRESS to liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE)),
                vehicles = listOf(vehicle),
                log = listOf(LogEntry(vehicleId = BLE_NAME, message = "$BLE_NAME · Connected · 62%")),
            )
        private val screenSamples =
            listOf(
                historySample(6L, 72),
                historySample(4L, 70),
                historySample(2L, 68),
                historySample(0L, 66),
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
