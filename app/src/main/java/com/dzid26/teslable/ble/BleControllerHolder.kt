package com.dzid26.teslable.ble

import android.content.Context

/**
 * Process-wide [TeslaBleController] shared by the UI and [BleTrackingService].
 *
 * A single instance means the GATT connections survive Activity destruction;
 * the foreground service is what keeps the process alive so the connections
 * and polling keep working with the screen off.
 */
object BleControllerHolder {

    @Volatile
    private var instance: TeslaBleController? = null

    fun get(context: Context): TeslaBleController {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: TeslaBleController(context.applicationContext).also { instance = it }
        }
    }
}
