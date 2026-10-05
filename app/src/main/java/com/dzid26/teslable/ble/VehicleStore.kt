// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists known vehicles (identity, current address, per-vehicle VIN and key
 * slot).
 */
class VehicleStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Vehicle> {
        val raw = prefs.getString(KEY_VEHICLES, null) ?: return emptyList()
        return parse(raw)
    }

    fun save(vehicles: Collection<Vehicle>) {
        val array = JSONArray()
        for (vehicle in vehicles.sortedBy { it.bleName }) {
            array.put(
                JSONObject().apply {
                    put(KEY_BLE_NAME, vehicle.bleName)
                    put(KEY_ADDRESS, vehicle.address)
                    vehicle.gattName?.let { put(KEY_GATT_NAME, it) }
                    vehicle.displayName?.let { put(KEY_DISPLAY_NAME, it) }
                    vehicle.vin?.let { put(KEY_VIN, it) }
                    vehicle.keySlot?.let { put(KEY_KEY_SLOT, it) }
                    if (vehicle.lastSeenMillis > 0) put(KEY_LAST_SEEN, vehicle.lastSeenMillis)
                },
            )
        }
        prefs.edit().putString(KEY_VEHICLES, array.toString()).apply()
    }

    private fun parse(raw: String): List<Vehicle> =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val bleName = item.optString(KEY_BLE_NAME).takeIf { it.isNotEmpty() } ?: continue
                    val address = item.optString(KEY_ADDRESS).takeIf { it.isNotEmpty() } ?: continue
                    add(
                        Vehicle(
                            bleName = bleName,
                            address = address,
                            gattName = item.optString(KEY_GATT_NAME).takeIf { it.isNotEmpty() },
                            displayName = item.optString(KEY_DISPLAY_NAME).takeIf { it.isNotEmpty() },
                            vin = item.optString(KEY_VIN).takeIf { it.isNotEmpty() },
                            keySlot = item.optInt(KEY_KEY_SLOT, -1).takeIf { it >= 0 },
                            lastSeenMillis = item.optLong(KEY_LAST_SEEN, 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

    private companion object {
        const val PREFS = "vehicles"
        const val KEY_VEHICLES = "vehicles"
        const val KEY_BLE_NAME = "bleName"
        const val KEY_ADDRESS = "address"
        const val KEY_GATT_NAME = "gattName"
        const val KEY_DISPLAY_NAME = "displayName"
        const val KEY_VIN = "vin"
        const val KEY_KEY_SLOT = "keySlot"
        const val KEY_LAST_SEEN = "lastSeen"
    }
}
