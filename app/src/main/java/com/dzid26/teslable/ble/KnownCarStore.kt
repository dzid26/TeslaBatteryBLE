// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists cars that were paired and connected, so the app can reconnect later. */
class KnownCarStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<KnownCar> {
        val raw = prefs.getString(KEY_CARS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val address = item.optString(KEY_ADDRESS).takeIf { it.isNotEmpty() } ?: continue
                    val name = item.optString(KEY_NAME).takeIf { it.isNotEmpty() } ?: continue
                    add(
                        KnownCar(
                            address = address,
                            name = name,
                            gattName = item.optString(KEY_GATT_NAME).takeIf { it.isNotEmpty() },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(cars: List<KnownCar>) {
        val array = JSONArray()
        for (car in cars) {
            array.put(
                JSONObject().apply {
                    put(KEY_ADDRESS, car.address)
                    put(KEY_NAME, car.name)
                    car.gattName?.let { put(KEY_GATT_NAME, it) }
                }
            )
        }
        prefs.edit().putString(KEY_CARS, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "known_cars"
        const val KEY_CARS = "cars"
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_GATT_NAME = "gattName"
    }
}
