// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.BatterySample
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingAgeTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    @Test
    fun `a reading under a minute old reads now`() {
        assertEquals("now", readingAgeText(0))
        assertEquals("now", readingAgeText(minute - 1))
    }

    @Test
    fun `a clock that ran backwards reads now`() {
        assertEquals("now", readingAgeText(-90_000))
    }

    @Test
    fun `minutes read whole from the first minute`() {
        assertEquals("1m ago", readingAgeText(minute))
        assertEquals("3m ago", readingAgeText(3 * minute))
        assertEquals("5m ago", readingAgeText(6 * minute - 1))
        assertEquals("12m ago", readingAgeText(12 * minute))
        assertEquals("59m ago", readingAgeText(hour - 1))
    }

    @Test
    fun `hours round down`() {
        assertEquals("1h ago", readingAgeText(hour))
        assertEquals("1h ago", readingAgeText(2 * hour - 1))
        assertEquals("23h ago", readingAgeText(day - 1))
    }

    @Test
    fun `days round down`() {
        assertEquals("1d ago", readingAgeText(day))
        assertEquals("1d ago", readingAgeText(2 * day - 1))
        assertEquals("3d ago", readingAgeText(3 * day))
        assertEquals("40d ago", readingAgeText(40 * day))
    }

    private fun liveConnection(
        readAt: Long?,
        phase: ConnectionPhase = ConnectionPhase.READY,
        status: VehicleStatus? = awake,
    ) = TeslaConnection(
        address = "18:04:ED:84:79:80",
        name = "Se1f0941734830fe7C",
        phase = phase,
        status = status,
        charge = ChargeState(battery_level = 62, charge_limit_soc = 80),
        chargeAtMillis = readAt,
    )

    private val awake =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    private val asleep = awake.copy(vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP)

    @Test
    fun `an awake connected reading is fresh until it is five minutes old`() {
        val readAt = 1_000_000L
        val connection = liveConnection(readAt)
        assertEquals(false, batteryPercent(connection, null, readAt + 4 * minute)?.stale)
        assertEquals(false, batteryPercent(connection, null, readAt + FRESH_READING_MS - 1)?.stale)
        assertEquals(true, batteryPercent(connection, null, readAt + FRESH_READING_MS)?.stale)
        assertEquals(true, batteryPercent(connection, null, readAt + 6 * minute)?.stale)
    }

    @Test
    fun `the same reading with an asleep status is stale and says how old it is`() {
        val readAt = 1_000_000L
        val now = readAt + 4 * minute
        val reading = batteryPercent(liveConnection(readAt, status = asleep), null, now)
        assertEquals(true, reading?.stale)
        assertEquals("4m ago", reading?.ageLabel(now))
    }

    @Test
    fun `a reading greyed by sleep soon after it was read is not called just read`() {
        val readAt = 1_000_000L
        val now = readAt + 3 * minute
        assertEquals("3m ago", batteryPercent(liveConnection(readAt, status = asleep), null, now)?.ageLabel(now))
    }

    @Test
    fun `a reading on a connection that is not ready is stale`() {
        val readAt = 1_000_000L
        for (phase in listOf(ConnectionPhase.DISCONNECTED, ConnectionPhase.CONNECTING, ConnectionPhase.FAILED)) {
            assertEquals(true, batteryPercent(liveConnection(readAt, phase = phase), null, readAt + minute)?.stale)
        }
    }

    @Test
    fun `a reading with no status yet is not stale because of sleep`() {
        val readAt = 1_000_000L
        assertEquals(false, batteryPercent(liveConnection(readAt, status = null), null, readAt + minute)?.stale)
    }

    @Test
    fun `a live charge with no read time is stale`() {
        assertEquals(true, batteryPercent(liveConnection(null), null, 5_000)?.stale)
    }

    @Test
    fun `the clock ticks at the moment a fresh reading turns stale, then every minute`() {
        val readAt = 1_000_000L
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(readAt, readAt))
        assertEquals(10_001L, stalenessTickDelayMs(readAt, readAt + FRESH_READING_MS - 10_000))
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(readAt, readAt + FRESH_READING_MS))
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(readAt, readAt + hour))
        assertEquals(STALENESS_TICK_MS, stalenessTickDelayMs(null, readAt))
    }

    private fun storedSample(
        carTimestamp: Long,
        readAt: Long?,
    ) = BatterySample(
        timestampMillis = carTimestamp,
        batteryLevel = 78,
        chargingState = null,
        chargeLimit = null,
        readAtMillis = readAt,
    )

    @Test
    fun `a stored sample's age is the phone read time, not the car's timestamp`() {
        val now = 100 * hour
        // The car stamped the reply "now" but the phone read it 12 minutes ago.
        val reading = batteryPercent(null, storedSample(carTimestamp = now, readAt = now - 12 * minute), now)
        assertEquals(true, reading?.stale)
        assertEquals("12m ago", reading?.ageLabel(now))
    }

    @Test
    fun `a stored sample with no phone read time shows no age`() {
        val now = 100 * hour
        val reading = batteryPercent(null, storedSample(carTimestamp = now, readAt = null), now)
        assertEquals(true, reading?.stale)
        assertNull(reading?.ageLabel(now))
    }

    @Test
    fun `only a stale reading shows its age`() {
        val readAt = 1_000_000L
        val connection = liveConnection(readAt)
        assertNull(batteryPercent(connection, null, readAt + 4 * minute)?.ageLabel(readAt + 4 * minute))
        assertEquals("5m ago", batteryPercent(connection, null, readAt + FRESH_READING_MS)?.ageLabel(readAt + FRESH_READING_MS))
        assertEquals("2h ago", batteryPercent(connection, null, readAt + 2 * hour)?.ageLabel(readAt + 2 * hour))
    }
}
