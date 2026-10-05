// SPDX-License-Identifier: AGPL-3.0-only
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dzid26.teslable.MainActivity
import com.dzid26.teslable.R
import com.dzid26.teslable.core.history.BatterySample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Tesla BLE connections alive in the
 * background.
 *
 * The GATT connections and polling live in [TeslaBleController] (shared through
 * [BleControllerHolder]); this service promotes the process to foreground so
 * Android does not kill it while the screen is off, and mirrors each tracked
 * car into its own ongoing notification. With one car the foreground
 * notification is that car's; with several it becomes a summary and each car
 * gets its own grouped notification with its own Wake action. ADR-0001
 * milestone 5.
 */
class BleTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundStarted = false
    private var lastSummary: NotificationModel? = null
    private val lastCarModels = mutableMapOf<String, NotificationModel>()
    private val postedCarIds = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        val controller = BleControllerHolder.get(this)
        scope.launch {
            combine(controller.state, controller.batteryHistory) { state, history ->
                state to history
            }.collect { (state, history) ->
                publish(modelsFor(state, history))
            }
        }
        // The charge reading ages between state changes; re-evaluate every
        // minute so the percentage can turn gray at the staleness boundary.
        scope.launch {
            while (true) {
                delay(STALENESS_TICK_MS)
                publish(modelsFor(controller.state.value, controller.batteryHistory.value))
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val controller = BleControllerHolder.get(this)
        if (intent?.action == ACTION_WAKE) {
            controller.wakeVehicle(intent.getStringExtra(EXTRA_BLE_NAME))
        }
        publish(modelsFor(controller.state.value, controller.batteryHistory.value))
        // START_STICKY may restart us after a process kill; find the cars again.
        controller.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** One model per tracked car, in the controller's (last-seen) order. */
    private fun modelsFor(
        state: BleUiState,
        history: List<BatterySample>,
    ): List<NotificationModel> =
        state.vehicles.mapNotNull { vehicle ->
            val connection = state.connections[vehicle.address] ?: return@mapNotNull null
            if (connection.phase == ConnectionPhase.IDLE) return@mapNotNull null
            val advert = state.devices.firstOrNull { it.address == vehicle.address }
            val display = connectionDisplay(connection, advert, vehicle = vehicle)
            val lastKnown = history.lastOrNull { it.vehicleId == vehicle.bleName }
            val reading = batteryPercent(connection, lastKnown, System.currentTimeMillis())
            NotificationModel(
                bleName = vehicle.bleName,
                title = display.title,
                status = display.status,
                stateText = display.stateText,
                rssi = display.rssi,
                percent = reading?.value,
                percentStale = reading?.stale == true,
                showWake =
                    connection.status?.asleep == true &&
                        connection.sessions.contains("DOMAIN_VEHICLE_SECURITY"),
            )
        }

    private fun publish(models: List<NotificationModel>) {
        val summary = summaryModel(models)
        if (!foregroundStarted) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(summary, groupSummary = models.size > 1),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                },
            )
            foregroundStarted = true
            lastSummary = summary
        } else if (shouldPost(lastSummary, summary)) {
            lastSummary = summary
            post(NOTIFICATION_ID, buildNotification(summary, groupSummary = models.size > 1))
        }
        syncCarNotifications(models)
    }

    /**
     * With one car the foreground notification is that car's; with several it
     * is a summary and every car gets a grouped notification of its own.
     */
    private fun summaryModel(models: List<NotificationModel>): NotificationModel =
        when {
            models.size == 1 -> models.first()

            models.isEmpty() ->
                NotificationModel(
                    bleName = null,
                    title = getString(R.string.app_name),
                    status = "No car connected",
                    stateText = "No car connected",
                    rssi = null,
                    percent = null,
                    percentStale = false,
                    showWake = false,
                )

            else ->
                NotificationModel(
                    bleName = null,
                    title = getString(R.string.app_name),
                    status = "Tracking ${models.size} cars",
                    stateText = "Tracking ${models.size} cars",
                    rssi = null,
                    percent = null,
                    percentStale = false,
                    showWake = false,
                )
        }

    private fun syncCarNotifications(models: List<NotificationModel>) {
        val wanted: Map<String, NotificationModel> =
            if (models.size > 1) {
                models.mapNotNull { model -> model.bleName?.let { it to model } }.toMap()
            } else {
                emptyMap()
            }
        // Cancel cars that went away (or collapsed back into the summary).
        for ((bleName, id) in postedCarIds.toMap()) {
            if (!wanted.containsKey(bleName)) {
                NotificationManagerCompat.from(this).cancel(id)
                postedCarIds.remove(bleName)
                lastCarModels.remove(bleName)
            }
        }
        for ((bleName, model) in wanted) {
            if (!shouldPost(lastCarModels[bleName], model)) continue
            lastCarModels[bleName] = model
            val id = postedCarIds.getOrPut(bleName) { carNotificationId(bleName) }
            post(id, buildNotification(model, groupSummary = false))
        }
    }

    private fun post(
        id: Int,
        notification: Notification,
    ) {
        val manager = NotificationManagerCompat.from(this)
        if (manager.areNotificationsEnabled()) {
            manager.notify(id, notification)
        }
    }

    /**
     * The UI polls RSSI fast; a notification only re-posts when the state
     * changes or the signal moves enough to matter.
     */
    private fun shouldPost(
        previous: NotificationModel?,
        current: NotificationModel,
    ): Boolean {
        if (previous == null) return true
        if (previous.title != current.title || previous.stateText != current.stateText) return true
        if (previous.percent != current.percent || previous.percentStale != current.percentStale) {
            return true
        }
        if (previous.showWake != current.showWake) return true
        val oldRssi = previous.rssi
        val newRssi = current.rssi
        if (oldRssi == null || newRssi == null) return oldRssi != newRssi
        return kotlin.math.abs(newRssi - oldRssi) >= RSSI_NOTIFICATION_STEP
    }

    private fun buildNotification(
        model: NotificationModel,
        groupSummary: Boolean,
    ): Notification {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tracking)
                .setContentTitle(model.title)
                .setContentText(notificationText(model))
                .setContentIntent(openAppIntent(model.bleName))
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setGroup(GROUP_KEY)
        if (groupSummary) {
            builder.setGroupSummary(true)
        }
        if (model.showWake && model.bleName != null) {
            builder.addAction(
                R.drawable.ic_stat_tracking,
                getString(R.string.tracking_wake),
                wakeIntent(model.bleName),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /**
     * The notification line: the state plus the last known percentage, with the
     * percentage in gray once its reading is older than [STALE_READING_MS].
     * Notifications accept spans, so no rich-text layout is needed.
     */
    private fun notificationText(model: NotificationModel): CharSequence {
        val percent = model.percent ?: return model.status
        val percentText = "$percent%"
        val text =
            if (model.status.contains(percentText)) {
                model.status
            } else {
                "${model.status} · $percentText"
            }
        if (!model.percentStale) return text
        val start = text.indexOf(percentText)
        if (start < 0) return text
        return SpannableString(text).apply {
            setSpan(
                ForegroundColorSpan(STALE_TEXT_COLOR),
                start,
                start + percentText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Tapping a car's notification opens that car; the summary opens the app. */
    private fun openAppIntent(bleName: String?): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (bleName != null) {
            intent.putExtra(EXTRA_BLE_NAME, bleName)
        }
        return PendingIntent.getActivity(
            this,
            bleName?.hashCode() ?: REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun wakeIntent(bleName: String): PendingIntent {
        val intent =
            Intent(this, BleTrackingService::class.java)
                .setAction(ACTION_WAKE)
                .putExtra(EXTRA_BLE_NAME, bleName)
        return PendingIntent.getService(
            this,
            bleName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(
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
        val bleName: String?,
        val title: String,
        val status: String,
        val stateText: String,
        val rssi: Int?,
        val percent: Int?,
        val percentStale: Boolean,
        val showWake: Boolean,
    )

    private fun carNotificationId(bleName: String): Int = CAR_NOTIFICATION_BASE + (bleName.hashCode() and 0xFFFF)

    companion object {
        private const val ACTION_START = "com.dzid26.teslable.action.START_TRACKING"
        private const val ACTION_WAKE = "com.dzid26.teslable.action.WAKE_VEHICLE"

        /** Extra carrying a car's advertised name for notification taps/wakes. */
        const val EXTRA_BLE_NAME = "com.dzid26.teslable.extra.BLE_NAME"
        private const val CHANNEL_ID = "tracking"
        private const val GROUP_KEY = "com.dzid26.teslable.cars"
        private const val NOTIFICATION_ID = 1
        private const val CAR_NOTIFICATION_BASE = 1000
        private const val REQUEST_OPEN_APP = 0
        private const val RSSI_NOTIFICATION_STEP = 5

        /** How often notification text is re-evaluated as readings age. */
        private const val STALENESS_TICK_MS = 60_000L
        private const val STALE_TEXT_COLOR = 0xFF9E9E9E.toInt()

        /** True while the foreground service is running (same process). */
        @Volatile
        var isRunning: Boolean = false

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BleTrackingService::class.java).setAction(ACTION_START),
                )
            } catch (_: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+): Android
                // forbids starting from the background. The UI retries on resume.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BleTrackingService::class.java))
        }
    }
}
