// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartTest {
    private fun decide(
        trackingEnabled: Boolean = true,
        hasPairedCar: Boolean = true,
        blePermissionsGranted: Boolean = true,
        notificationsGranted: Boolean = true,
    ) = shouldAutoStartTracking(trackingEnabled, hasPairedCar, blePermissionsGranted, notificationsGranted)

    @Test
    fun `starts when tracking is on, a car is paired and permissions are granted`() {
        assertTrue(decide())
    }

    @Test
    fun `does nothing when tracking was switched off`() {
        assertFalse(decide(trackingEnabled = false))
    }

    @Test
    fun `does nothing without a paired car`() {
        assertFalse(decide(hasPairedCar = false))
    }

    @Test
    fun `does nothing when a Bluetooth permission was revoked`() {
        assertFalse(decide(blePermissionsGranted = false))
    }

    @Test
    fun `does nothing when the notification permission was revoked`() {
        assertFalse(decide(notificationsGranted = false))
    }
}
