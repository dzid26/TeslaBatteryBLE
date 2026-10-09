// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.dzid26.teslable.ble.BatteryPercent
import com.dzid26.teslable.ble.BleUiState
import com.dzid26.teslable.ble.ConnectionPhase
import com.dzid26.teslable.ble.PairingPhase
import com.dzid26.teslable.ble.TeslaAdvert
import com.dzid26.teslable.ble.TeslaConnection
import com.dzid26.teslable.ble.Vehicle
import com.dzid26.teslable.ble.batteryPercent
import com.dzid26.teslable.ble.chargingStateText
import com.dzid26.teslable.ble.connectionDisplay
import com.dzid26.teslable.ble.readingAgeText
import com.dzid26.teslable.ble.vehicleStatusText
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.ChargeProjection
import com.dzid26.teslable.core.history.DischargeProjection
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.HistoryRange
import com.dzid26.teslable.core.history.chargeProjection
import com.dzid26.teslable.core.history.chargeStats
import com.dzid26.teslable.core.history.dischargeProjection
import com.dzid26.teslable.core.history.within
import com.dzid26.teslable.core.protocol.asleep
import com.dzid26.teslable.core.protocol.chargingStateKind
import com.dzid26.teslable.health.healthSummary
import com.tesla.generated.carserver.vehicle.ChargeState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// -------------------------------------------------------------------- car view

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarScreen(
    state: BleUiState,
    vehicle: Vehicle?,
    advert: TeslaAdvert?,
    address: String,
    history: List<BatterySample>,
    driveHistory: List<DriveSample>,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTracking: (Boolean) -> Unit,
) {
    BackHandler { onBack() }
    val connection = state.connections[address]
    val vehicleHistory =
        if (vehicle == null) {
            emptyList()
        } else {
            history.filter { it.vehicleId == vehicle.bleName }
        }
    val vehicleDrives =
        if (vehicle == null) {
            emptyList()
        } else {
            driveHistory.filter { it.vehicleId == vehicle.bleName }
        }
    val paired = isPaired(connection, vehicle)
    val health = remember(vehicleHistory) { healthSummary(vehicleHistory) }
    // What a pull on this screen will do, and the feedback while it runs.
    val pullLabel =
        when {
            connection?.phase != ConnectionPhase.READY -> "Reconnect"
            connection.status?.asleep != false -> "Wake car"
            else -> "Read battery"
        }
    val refreshingLabel =
        when {
            connection?.phase != ConnectionPhase.READY -> "Reconnecting…"
            connection.status?.asleep != false -> "Waking…"
            else -> "Reading…"
        }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_FEEDBACK_MS)
            refreshing = false
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "All cars",
                        )
                    }
                },
                title = {
                    Text(vehicle?.title ?: connection?.gattDeviceName ?: advert?.name ?: "Car")
                },
                actions = {
                    TrackingToggle(
                        checked = state.trackingEnabled,
                        onCheckedChange = onToggleTracking,
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                onRefresh()
            },
            state = pullState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            indicator = {
                RefreshPill(
                    state = pullState,
                    isRefreshing = refreshing,
                    pullLabel = pullLabel,
                    refreshingLabel = refreshingLabel,
                )
            },
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                HeroCard(
                    connection = connection,
                    advert = advert,
                    vehicle = vehicle,
                    history = vehicleHistory,
                )
                // Once the app key is enrolled the hero carries its status; the
                // card only exists for pairing, so it disappears when there is
                // nothing to do.
                if (!paired) {
                    KeyCard(connection, vehicle, onPair)
                }
                BatteryHistoryCard(vehicleHistory, vehicleDrives)
                BatteryHealthCard(health)
            }
        }
    }
}

@Composable
private fun HeroCard(
    connection: TeslaConnection?,
    advert: TeslaAdvert?,
    vehicle: Vehicle?,
    history: List<BatterySample>,
) {
    val context = LocalContext.current
    val display = connectionDisplay(connection, advert, vehicle = vehicle)
    val charge = connection?.charge
    // With no live reading, the newest stored sample still answers "how full
    // is the car?" at a glance; the caption makes its age explicit.
    val lastKnown = history.lastOrNull()
    val nowMillis = rememberNowMillis()
    val reading = batteryPercent(connection, lastKnown, nowMillis)
    val keySlot = connection?.keySlot ?: vehicle?.keySlot
    val pairing = connection?.pairing ?: PairingPhase.IDLE
    Card(
        modifier =
            Modifier
                .fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(display.title, style = MaterialTheme.typography.titleMedium)
                    // The app key state sits above the connection state: it is
                    // what unlocks authenticated reads.
                    if (keySlot != null || pairing == PairingPhase.OK) {
                        Text(
                            text = keySlot?.let { "App key paired · slot $it" } ?: "App key paired",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = display.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pairing == PairingPhase.OK) {
                        // Only shown for the pairing that just succeeded; the
                        // Tesla screen calls the key Phone Key, so this hint does.
                        Text(
                            text = "Rename the Phone Key in Controls > Locks on the Tesla screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                StatusPill(
                    connection = connection,
                    onAsleepClick = {
                        Toast
                            .makeText(context, "Swipe down to wake the car", Toast.LENGTH_SHORT)
                            .show()
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            SocBlock(
                reading = reading,
                live = charge?.battery_level != null,
                lastKnown = lastKnown,
                stateText = display.stateText,
                nowMillis = nowMillis,
            )
            Spacer(Modifier.height(8.dp))
            connection?.status?.let { status ->
                Text(vehicleStatusText(status), style = MaterialTheme.typography.bodySmall)
            }
            ChargeDetails(charge)
        }
    }
}

/** Range · charge limit · charging state, as one quiet line under the hero. */
@Composable
private fun ChargeDetails(charge: ChargeState?) {
    if (charge == null) return
    val details =
        buildList {
            charge.battery_range?.let { add("${formatRangeMiles(it)} mi") }
            charge.charge_limit_soc?.let { add("Charge limit $it%") }
            charge.chargingStateKind?.let { add(chargingStateText(it)) }
        }
    if (details.isNotEmpty()) {
        Text(
            text = details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The big battery reading, colored like the cars list: primary while fresh,
 * outline once stale, with how old it is under the number. With no reading at
 * all, the connection state shows instead.
 */
@Composable
private fun SocBlock(
    reading: BatteryPercent?,
    live: Boolean,
    lastKnown: BatterySample?,
    stateText: String,
    nowMillis: Long,
) {
    if (reading == null) {
        Text(text = stateText, style = MaterialTheme.typography.headlineSmall)
        return
    }
    val color = if (reading.stale) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "${reading.value}",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Text(
            text = "%",
            style = MaterialTheme.typography.headlineSmall,
            color = color,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    val readAtMillis = reading.readAtMillis
    if (reading.stale && readAtMillis != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Last known · ${readingAgeText(nowMillis - readAtMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (live) {
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { reading.value / 100f },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            color = color,
        )
    } else {
        // The stored sample carries its own range; a live one shows it below.
        lastKnown?.ratedRangeMiles?.let { miles ->
            Text(
                text = "${formatRangeMiles(miles)} mi",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** True when the app key is enrolled (or just was): the car can authenticate. */
private fun isPaired(
    connection: TeslaConnection?,
    vehicle: Vehicle?,
): Boolean =
    connection?.keySlot != null ||
        vehicle?.keySlot != null ||
        connection?.pairing == PairingPhase.OK

@Composable
private fun KeyCard(
    connection: TeslaConnection?,
    vehicle: Vehicle?,
    onPair: () -> Unit,
) {
    // The stored slot proves enrollment even before the car answers.
    val keySlot = connection?.keySlot ?: vehicle?.keySlot
    val paired = isPaired(connection, vehicle)
    val pairing = connection?.pairing ?: PairingPhase.IDLE
    val pairingInProgress =
        pairing == PairingPhase.CHECKING ||
            pairing == PairingPhase.SENDING ||
            pairing == PairingPhase.WAITING_FOR_CARD
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App key", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text =
                            when {
                                !paired -> "Not paired"
                                keySlot != null -> "Paired · slot $keySlot"
                                else -> "Paired"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!paired) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text =
                                "Pair so this phone can wake and read the car; keep an " +
                                    "NFC card ready for the car's prompt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!paired) {
                    Button(
                        onClick = onPair,
                        enabled = connection?.phase == ConnectionPhase.READY && !pairingInProgress,
                    ) {
                        Text("Pair key")
                    }
                }
            }
            if (pairing != PairingPhase.IDLE) {
                Spacer(Modifier.height(8.dp))
                PairingStatus(pairing = pairing, keyId = connection?.pairingKeyId)
            }
        }
    }
}

@Composable
private fun PairingStatus(
    pairing: PairingPhase,
    keyId: String?,
) {
    val inProgress =
        pairing == PairingPhase.CHECKING ||
            pairing == PairingPhase.SENDING ||
            pairing == PairingPhase.WAITING_FOR_CARD
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(
                text = pairingStatusText(pairing, keyId),
                style = MaterialTheme.typography.bodySmall,
                color =
                    when (pairing) {
                        PairingPhase.OK -> MaterialTheme.colorScheme.primary
                        PairingPhase.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            pairingDetailText(pairing)?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun pairingStatusText(
    pairing: PairingPhase,
    keyId: String?,
): String =
    when (pairing) {
        PairingPhase.CHECKING -> "Checking whether this phone is already paired…"
        PairingPhase.SENDING -> "Pairing request sent — waiting for the car."
        PairingPhase.WAITING_FOR_CARD ->
            "Tap your NFC card on the center console and confirm on the car screen."

        PairingPhase.OK -> "Key paired: $keyId"
        PairingPhase.ERROR ->
            "Pairing not confirmed. Hold the NFC card on the center console and try again."

        PairingPhase.IDLE -> ""
    }

private fun pairingDetailText(pairing: PairingPhase): String? =
    when (pairing) {
        PairingPhase.SENDING, PairingPhase.WAITING_FOR_CARD -> "Waiting for the car to confirm the key..."
        PairingPhase.OK -> "Rename the Phone Key in Controls > Locks on the Tesla screen."
        else -> null
    }

// ---------------------------------------------------------------- battery card

@Composable
private fun BatteryHistoryCard(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
) {
    var range by rememberSaveable { mutableStateOf(HistoryRange.DAY) }
    val now = System.currentTimeMillis()
    val visible = samples.within(range, now)
    val stats = chargeStats(samples)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Battery history", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRange.entries.forEach { entry ->
                    FilterChip(
                        selected = range == entry,
                        onClick = { range = entry },
                        label = { Text(entry.label()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Text(
                    text = "No samples yet. SOC is recorded while the car is awake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Projections are derived from every sample, not just the
                // visible window, so a run that started before the selected
                // range still projects.
                val baseMillis =
                    range.durationMillis
                        ?: (visible.last().timestampMillis - visible.first().timestampMillis)
                            .coerceAtLeast(1L)
                val charge = chargeProjection(samples)
                val discharge =
                    if (charge == null) {
                        // Parked drain only: the drive samples' odometer marks
                        // the stretches the car moved.
                        dischargeProjection(samples, drives, baseMillis, now)
                    } else {
                        null
                    }
                // The projection gets room on the x-axis: half the range ahead.
                val windowEnd =
                    if (charge != null || discharge != null) {
                        now + baseMillis / 2
                    } else {
                        now
                    }
                BatteryChart(
                    samples = visible,
                    charge = charge,
                    discharge = discharge,
                    windowStart =
                        range.durationMillis?.let { now - it }
                            ?: visible.first().timestampMillis,
                    windowEnd = windowEnd,
                    showDate = range != HistoryRange.SIX_HOURS,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                )
                charge?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text =
                            "Charging · projected ${it.targetPercent}% around " +
                                formatTime(it.completionMillis, showDate = true),
                        style = MaterialTheme.typography.bodySmall,
                        color = chargingChartColor,
                    )
                }
                discharge?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text =
                            "Discharging · projected ${it.projectedPercent}% by " +
                                formatTime(it.projectedAtMillis, showDate = true),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            stats?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text =
                        "Since last charge: ${formatDuration(now - it.sinceMillis)} ago · " +
                            "${formatPercent(it.currentPercent)}% now · ${formatPercent(it.usedPercent)}% used",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "min ${formatPercent(it.minPercent)}% · max ${formatPercent(it.maxPercent)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BatteryChart(
    samples: List<BatterySample>,
    charge: ChargeProjection?,
    discharge: DischargeProjection?,
    windowStart: Long,
    windowEnd: Long,
    showDate: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val chargingColor = chargingChartColor
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle =
        MaterialTheme.typography.labelSmall.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    val textMeasurer = rememberTextMeasurer()

    Canvas(modifier) {
        val left = 36.dp.toPx()
        val bottom = 20.dp.toPx()
        val top = 8.dp.toPx()
        val right = 8.dp.toPx()
        val width = size.width - left - right
        val height = size.height - top - bottom
        if (width <= 0f || height <= 0f) return@Canvas

        val minPercent = minOf(samples.minOf { it.percent }, discharge?.projectedPercent ?: 100)
        val maxPercent = maxOf(samples.maxOf { it.percent }, charge?.targetPercent ?: 0)
        var yMin = ((minPercent - 2).coerceAtLeast(0) / 5) * 5
        var yMax = (((maxPercent + 2).coerceAtMost(100) + 4) / 5) * 5
        if (yMax <= yMin) {
            yMin = (yMin - 5).coerceAtLeast(0)
            yMax = (yMin + 5).coerceAtMost(100)
        }
        val span = (windowEnd - windowStart).coerceAtLeast(1L).toFloat()

        fun x(timestampMillis: Long): Float = left + ((timestampMillis - windowStart).toFloat() / span).coerceIn(0f, 1f) * width

        fun y(percent: Int): Float = top + height - ((percent - yMin).toFloat() / (yMax - yMin)) * height

        listOf(yMin, (yMin + yMax) / 2, yMax).forEach { value ->
            val gridY = y(value)
            drawLine(
                color = gridColor,
                start = Offset(left, gridY),
                end = Offset(left + width, gridY),
                strokeWidth = 1.dp.toPx(),
            )
            val label = textMeasurer.measure("$value%", labelStyle)
            drawText(
                textLayoutResult = label,
                topLeft =
                    Offset(
                        x = left - label.size.width - 6.dp.toPx(),
                        y = gridY - label.size.height / 2f,
                    ),
            )
        }

        // Constant narrow line; the dots below mark where measurements exist.
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            drawLine(
                color = if (current.isCharging) chargingColor else lineColor,
                start = Offset(x(previous.timestampMillis), y(previous.percent)),
                end = Offset(x(current.timestampMillis), y(current.percent)),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        // One dot per measurement, no decimation: dense samples overlap into a
        // thick band, sparse ones read as separate dots.
        samples.forEach { sample ->
            drawCircle(
                color = if (sample.isCharging) chargingColor else lineColor,
                radius = 2.dp.toPx(),
                center = Offset(x(sample.timestampMillis), y(sample.percent)),
            )
        }

        charge?.let { target ->
            val end =
                Offset(
                    x(target.completionMillis),
                    y(target.targetPercent),
                )
            drawPath(
                path =
                    Path().apply {
                        moveTo(x(target.from.timestampMillis), y(target.from.percent))
                        lineTo(end.x, end.y)
                    },
                color = chargingColor.copy(alpha = 0.65f),
                style =
                    Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
            )
        }

        discharge?.let { target ->
            val end =
                Offset(
                    x(target.projectedAtMillis),
                    y(target.projectedPercent),
                )
            drawPath(
                path =
                    Path().apply {
                        moveTo(x(target.from.timestampMillis), y(target.from.percent))
                        lineTo(end.x, end.y)
                    },
                color = lineColor.copy(alpha = 0.65f),
                style =
                    Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                    ),
            )
        }

        val startLabel = textMeasurer.measure(formatTime(windowStart, showDate), labelStyle)
        drawText(
            textLayoutResult = startLabel,
            topLeft = Offset(left, size.height - startLabel.size.height),
        )
        val endLabel = textMeasurer.measure(formatTime(windowEnd, showDate), labelStyle)
        drawText(
            textLayoutResult = endLabel,
            topLeft = Offset(left + width - endLabel.size.width, size.height - endLabel.size.height),
        )
    }
}

private fun HistoryRange.label(): String =
    when (this) {
        HistoryRange.SIX_HOURS -> "6h"
        HistoryRange.DAY -> "24h"
        HistoryRange.WEEK -> "7d"
        HistoryRange.ALL -> "All"
    }

private const val REFRESH_FEEDBACK_MS = 2500L

/** Green shared by charging segments, dots, and the projected line. */
private val chargingChartColor = Color(0xFF43A047)

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")

private fun formatTime(
    millis: Long,
    showDate: Boolean,
): String =
    Instant
        .ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(if (showDate) dateTimeFormatter else timeFormatter)

private fun formatDuration(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    val days = minutes / (24 * 60)
    val hours = minutes % (24 * 60) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

private fun formatRangeMiles(miles: Float): String = String.format(Locale.getDefault(), "%.1f", miles)

/** One decimal only when it adds information: "78%" but "77.6%". */
private fun formatPercent(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded.toFloat() == value) "$rounded" else String.format(Locale.getDefault(), "%.1f", value)
}
