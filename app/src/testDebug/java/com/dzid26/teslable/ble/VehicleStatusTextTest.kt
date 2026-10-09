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

    private fun durations(
        asleepFor: Long? = null,
        lockedFor: Long? = null,
    ) = StatusDurations(asleep = true, locked = true, asleepForMillis = asleepFor, lockedForMillis = lockedFor)

    @Test
    fun `without durations the line is the plain labels, asleep first`() {
        assertEquals("Asleep · Locked · User away", vehicleStatusText(asleepLocked))
    }

    @Test
    fun `asleep and locked each show how long they have held`() {
        val d = durations(asleepFor = hour + 12 * minute, lockedFor = 3 * hour)
        assertEquals("Asleep 1h 12m · Locked 3h · User away", vehicleStatusText(asleepLocked, d))
    }

    @Test
    fun `a duration that is unknown or under a minute is left out`() {
        assertEquals("Asleep 5m · Locked · User away", vehicleStatusText(asleepLocked, durations(asleepFor = 5 * minute)))
        assertEquals("Asleep · Locked 3h · User away", vehicleStatusText(asleepLocked, durations(asleepFor = 30_000, lockedFor = 3 * hour)))
        assertEquals("Asleep · Locked · User away", vehicleStatusText(asleepLocked, durations()))
    }

    @Test
    fun `a duration for a different value than the live status is not shown`() {
        val d = StatusDurations(asleep = false, locked = false, asleepForMillis = hour, lockedForMillis = hour)
        assertEquals("Asleep · Locked · User away", vehicleStatusText(asleepLocked, d))
        assertNull(asleepForMillis(asleepLocked, d))
    }
}
