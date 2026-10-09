// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.StatusDurations
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleStatusTextTest {
    private val minute = 60_000L
    private val hour = 60 * minute

    private val asleepLocked =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    @Test
    fun `without durations the line is the plain labels`() {
        assertEquals("Locked · Asleep · User away", vehicleStatusText(asleepLocked))
    }

    @Test
    fun `each flag shows how long it has held`() {
        val durations =
            StatusDurations(
                asleep = true,
                locked = true,
                userPresent = false,
                asleepForMillis = hour + 12 * minute,
                lockedForMillis = 3 * hour,
                userPresentForMillis = 20 * minute,
            )
        assertEquals("Locked 3h · Asleep 1h 12m · User away 20m", vehicleStatusText(asleepLocked, durations))
    }

    @Test
    fun `unknown durations leave the label alone`() {
        val durations =
            StatusDurations(
                asleep = true,
                locked = true,
                userPresent = false,
                asleepForMillis = 5 * minute,
                lockedForMillis = null,
                userPresentForMillis = null,
            )
        assertEquals("Locked · Asleep 5m · User away", vehicleStatusText(asleepLocked, durations))
    }

    @Test
    fun `a duration for a different value than the live status is not shown`() {
        val durations =
            StatusDurations(
                asleep = false,
                locked = false,
                userPresent = true,
                asleepForMillis = hour,
                lockedForMillis = hour,
                userPresentForMillis = hour,
            )
        assertEquals("Locked · Asleep · User away", vehicleStatusText(asleepLocked, durations))
        assertNull(asleepForMillis(asleepLocked, durations))
    }
}
