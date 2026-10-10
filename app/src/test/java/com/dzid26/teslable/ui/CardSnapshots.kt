// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.Paparazzi
import com.dzid26.teslable.ble.ConnectionPhase
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
 * Fast JVM snapshots of the two battery cards, light theme only: the cars-list
 * card ([VehicleCard]) and the car-view card ([HeroCard]), one image per
 * state (fresh, stale, asleep, disconnected, reading spinner, no reading).
 *
 * There are no committed goldens: `recordPaparazziDebug` renders the 12 PNGs
 * and the PR workflow diffs them before/after the pushed commit, posting only
 * what changed. Fixtures mirror the unit-test patterns (a fixed NOW, the same
 * [TeslaConnection]/[ChargeState]/[VehicleStatus] shapes), and the cards get a
 * fixed clock plus a forced spinner so the images are deterministic.
 */
class CardSnapshots {
    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun vehicleCardFresh() {
        paparazzi.snapshot("vehicle-card_fresh") {
            CardFrame {
                VehicleCard(
                    row = listRow(liveConnection(readAt = NOW - FRESH_MINUTES * MINUTE)),
                    onOpen = {},
                    onEditVin = {},
                    onWake = {},
                    nowMillis = NOW,
                )
            }
        }
    }

    @Test
    fun vehicleCardStale() {
        paparazzi.snapshot("vehicle-card_stale") {
            CardFrame {
                VehicleCard(
                    row = listRow(liveConnection(readAt = NOW - STALE_MINUTES * MINUTE)),
                    onOpen = {},
                    onEditVin = {},
                    onWake = {},
                    nowMillis = NOW,
                )
            }
        }
    }

    @Test
    fun vehicleCardAsleep() {
        paparazzi.snapshot("vehicle-card_asleep") {
            CardFrame {
                VehicleCard(
                    row = listRow(liveConnection(readAt = NOW - ASLEEP_MINUTES * MINUTE, status = asleep)),
                    onOpen = {},
                    onEditVin = {},
                    onWake = {},
                    nowMillis = NOW,
                )
            }
        }
    }

    @Test
    fun vehicleCardDisconnected() {
        paparazzi.snapshot("vehicle-card_disconnected") {
            CardFrame {
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
    }

    @Test
    fun vehicleCardSpinner() {
        paparazzi.snapshot("vehicle-card_spinner") {
            CardFrame {
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
    }

    @Test
    fun vehicleCardNoReading() {
        paparazzi.snapshot("vehicle-card_no-reading") {
            CardFrame {
                VehicleCard(
                    row = listRow(liveConnection(charge = null)),
                    onOpen = {},
                    onEditVin = {},
                    onWake = {},
                    nowMillis = NOW,
                )
            }
        }
    }

    @Test
    fun heroCardFresh() {
        paparazzi.snapshot("hero-card_fresh") {
            CardFrame {
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
    }

    @Test
    fun heroCardStale() {
        paparazzi.snapshot("hero-card_stale") {
            CardFrame {
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
    }

    @Test
    fun heroCardAsleep() {
        paparazzi.snapshot("hero-card_asleep") {
            CardFrame {
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
    }

    @Test
    fun heroCardDisconnected() {
        paparazzi.snapshot("hero-card_disconnected") {
            CardFrame {
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
    }

    @Test
    fun heroCardSpinner() {
        paparazzi.snapshot("hero-card_spinner") {
            CardFrame {
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
    }

    @Test
    fun heroCardNoReading() {
        paparazzi.snapshot("hero-card_no-reading") {
            CardFrame {
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
    }

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

    companion object {
        private const val NOW = 1_760_000_000_000L
        private const val MINUTE = 60_000L
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
    }
}

/**
 * The snapshot frame: a fixed light scheme (never the dynamic color, which
 * Paparazzi has no real context for) plus a surface, so the cards render
 * exactly as they would on a light phone screen.
 */
@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme()) {
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
