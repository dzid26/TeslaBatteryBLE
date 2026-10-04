package com.dzid26.teslable.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Tesla BLE connection alive in the background.
 *
 * The GATT connections and polling live in [TeslaBleController] (shared through
 * [BleControllerHolder]); this service promotes the process to foreground so
 * Android does not kill it while the screen is off, and mirrors the controller
 * state into an ongoing notification. ADR-0001 milestone 5.
 */
class BleTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false
    private var lastModel: NotificationModel? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        val controller = BleControllerHolder.get(this)
        scope.launch {
            controller.state.collect { state ->
                val model = modelFor(state)
                if (!foregroundStarted || !shouldPost(lastModel, model)) return@collect
                lastModel = model
                postNotification(model)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = BleControllerHolder.get(this)
        if (intent?.action == ACTION_WAKE) {
            controller.wakeVehicle()
        }
        val model = modelFor(controller.state.value)
        lastModel = model
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(model),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        foregroundStarted = true
        // START_STICKY may restart us after a process kill; find the paired car again.
        controller.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun postNotification(model: NotificationModel) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, buildNotification(model))
        }
    }

    private fun modelFor(state: BleUiState): NotificationModel {
        val connection = state.selectedAddress?.let { state.connections[it] }
            ?: state.connections.values.firstOrNull { it.phase == ConnectionPhase.READY }
            ?: state.connections.values.firstOrNull()
        val advert = state.devices.firstOrNull { it.address == connection?.address }
        val display = connectionDisplay(connection, advert)
        return NotificationModel(
            title = display.title,
            status = display.status,
            stateText = display.stateText,
            rssi = display.rssi,
            showWake = connection?.status?.asleep == true &&
                connection.sessions.contains("DOMAIN_VEHICLE_SECURITY"),
        )
    }

    /**
     * The UI polls RSSI fast; the notification only re-posts when the state
     * changes or the signal moves enough to matter.
     */
    private fun shouldPost(previous: NotificationModel?, current: NotificationModel): Boolean {
        if (previous == null) return true
        if (previous.title != current.title ||
            previous.stateText != current.stateText ||
            previous.showWake != current.showWake
        ) {
            return true
        }
        val oldRssi = previous.rssi
        val newRssi = current.rssi
        if (oldRssi == null || newRssi == null) return oldRssi != newRssi
        return kotlin.math.abs(newRssi - oldRssi) >= RSSI_NOTIFICATION_STEP
    }

    private fun buildNotification(model: NotificationModel): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(model.title)
            .setContentText(model.status)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (model.showWake) {
            builder.addAction(
                R.drawable.ic_stat_tracking,
                getString(R.string.tracking_wake),
                wakeIntent(),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun wakeIntent(): PendingIntent {
        val intent = Intent(this, BleTrackingService::class.java).setAction(ACTION_WAKE)
        return PendingIntent.getService(
            this,
            REQUEST_WAKE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.tracking_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private data class NotificationModel(
        val title: String,
        val status: String,
        val stateText: String,
        val rssi: Int?,
        val showWake: Boolean,
    )

    companion object {
        private const val ACTION_START = "com.dzid26.teslable.action.START_TRACKING"
        private const val ACTION_WAKE = "com.dzid26.teslable.action.WAKE_VEHICLE"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_WAKE = 1
        private const val RSSI_NOTIFICATION_STEP = 5

        /** True while the foreground service is running (same process). */
        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BleTrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleTrackingService::class.java))
        }
    }
}
