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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
            combine(controller.state, batteryPercent) { state, battery ->
                modelFor(state, battery)
            }.collect { model ->
                if (!foregroundStarted || model == lastModel) return@collect
                lastModel = model
                postNotification(model)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val controller = BleControllerHolder.get(this)
        val model = modelFor(controller.state.value, batteryPercent.value)
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

    private fun modelFor(state: BleUiState, battery: Int?): NotificationModel {
        val connection = state.selectedAddress?.let { state.connections[it] }
            ?: state.connections.values.firstOrNull { it.phase == ConnectionPhase.READY }
            ?: state.connections.values.firstOrNull()
        return NotificationModel(
            carName = connection?.gattDeviceName ?: connection?.name,
            connected = connection?.phase == ConnectionPhase.READY,
            batteryPercent = battery,
            locked = connection?.status?.locked,
            asleep = connection?.status?.asleep,
        )
    }

    private fun buildNotification(model: NotificationModel): Notification {
        val battery = model.batteryPercent?.let { "$it%" }
            ?: getString(R.string.tracking_battery_placeholder)
        val connectionState = getString(
            if (model.connected) {
                R.string.tracking_state_connected
            } else {
                R.string.tracking_state_disconnected
            },
        )
        val lines = buildList {
            add(getString(R.string.tracking_battery, battery))
            add(connectionState)
            model.locked?.let {
                add(getString(if (it) R.string.tracking_locked else R.string.tracking_unlocked))
            }
            model.asleep?.let {
                add(getString(if (it) R.string.tracking_asleep else R.string.tracking_awake))
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setContentTitle(model.carName ?: getString(R.string.app_name))
            .setContentText(lines.joinToString(" \u00b7 "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(openAppIntent())
            .addAction(
                R.drawable.ic_stat_tracking,
                getString(R.string.tracking_stop),
                stopIntent(),
            )
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
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

    private fun stopIntent(): PendingIntent {
        val intent = Intent(this, BleTrackingService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            REQUEST_STOP,
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
        val carName: String?,
        val connected: Boolean,
        val batteryPercent: Int?,
        val locked: Boolean?,
        val asleep: Boolean?,
    )

    companion object {
        private const val ACTION_START = "com.dzid26.teslable.action.START_TRACKING"
        private const val ACTION_STOP = "com.dzid26.teslable.action.STOP_TRACKING"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_STOP = 1

        private val batteryPercentFlow = MutableStateFlow<Int?>(null)

        /** Latest battery percentage pushed by the BLE layer, or null while unknown. */
        val batteryPercent: StateFlow<Int?> = batteryPercentFlow.asStateFlow()

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

        /**
         * Placeholder hook: call this with the real SOC once the BLE layer reads
         * `BodyControllerState` / `GetState(Charge)` (ADR-0001 milestone 4).
         * Until then the notification shows [R.string.tracking_battery_placeholder].
         */
        fun updateBatteryPercent(percent: Int?) {
            batteryPercentFlow.value = percent?.coerceIn(0, 100)
        }
    }
}
