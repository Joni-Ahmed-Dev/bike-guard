package com.bikeguard.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.ParkingSpotDto
import com.bikeguard.app.data.eventTitle
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.alerts.AlertsViewModel
import com.bikeguard.app.ui.fmtDuration
import com.bikeguard.app.ui.fmtKm
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import com.bikeguard.app.ui.trips.TripsViewModel
import com.bikeguard.app.ui.viewer.BikeMap
import com.bikeguard.app.ui.viewer.rememberIsOnline
import com.bikeguard.app.ui.viewer.ViewerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home tab for the Remote role: everything about the bike on one screen.
 * Reuses ViewerViewModel (live position + controls), TripsViewModel (today's ride) and
 * AlertsViewModel (recent alerts + health warnings).
 */
@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    vm: ViewerViewModel = viewModel(),
    tripsVm: TripsViewModel = viewModel(),
    alerts: AlertsViewModel = viewModel(),
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val now = rememberNow()
    val online = rememberIsOnline()
    LaunchedEffect(Unit) { tripsVm.load(); alerts.refresh(); vm.loadGeofences() }
    // The Dashboard is the Remote phone's default tab: start the live poll here too, so it
    // shows live data without opening the Viewer first.
    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
    }

    val latest = vm.latest
    val device = vm.selectedDevice
    val age = latest?.let { now - it.recordedAtMs }
    val statusText = when {
        age == null -> R.string.status_offline
        age < 240_000 -> R.string.status_live
        age < 900_000 -> R.string.status_delayed
        else -> R.string.status_offline
    }
    val statusColor = when {
        age == null -> Palette.Muted
        age < 240_000 -> Palette.Signal
        age < 900_000 -> Palette.Ember
        else -> Palette.Muted
    }

    // Today's ride, from the trips list.
    val dayStart = remember {
        LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val today = remember(tripsVm.trips, dayStart) { tripsVm.trips.orEmpty().filter { it.startedMs >= dayStart } }
    // Jitter-corrected distance when the points have been recomputed, else the server total.
    val todayKm = tripsVm.todayDistanceM ?: today.sumOf { it.distanceM }
    val todayMs = today.sumOf { it.endedMs - it.startedMs }
    val todayMax = today.maxOfOrNull { it.maxSpeedKmh } ?: 0.0

    // Remote tracking switch: the tracker's own mirrored flag, polled while this screen is open.
    // Null = not known yet, so the switch stays disabled until the first read.
    val isRemote = ctx.bikeApp.prefs.isRemote
    var remoteTrackingOn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(vm.selectedId) {
        val id = vm.selectedId ?: return@LaunchedEffect
        remoteTrackingOn = null
        while (true) {
            try {
                ctx.bikeApp.api.trackingExpected(id)?.let {
                    remoteTrackingOn = it
                    if (vm.trackingPendingTo == it) vm.clearTrackingPending()
                }
            } catch (e: IOException) { }
            delay(15_000)
        }
    }

    // Newest health event wins: a health_ok recovery clears a stale warning.
    val healthEvent = remember(alerts.events) { alerts.events.firstOrNull { it.type == "health_warning" || it.type == "health_ok" } }
    val healthBad = healthEvent?.type == "health_warning" && (now - healthEvent.createdMs) < 24 * 3600_000L

    var spot by remember { mutableStateOf<ParkingSpotDto?>(null) }
    var note by remember { mutableStateOf("") }
    var noteSaved by remember { mutableStateOf(false) }
    LaunchedEffect(vm.selectedId) {
        val id = vm.selectedId ?: return@LaunchedEffect
        spot = try { ctx.bikeApp.api.latestParkingSpot(id) } catch (e: IOException) { null }
        note = spot?.note.orEmpty()
    }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.tab_dashboard), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp),
        )

        // ---- status + quick buttons ----
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(stringResource(statusText), color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.stat_updated) + ": " +
                    (latest?.let { agoText(ctx, it.recordedAtMs, now) } ?: stringResource(R.string.none)),
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickButton(R.string.ctl_locate, Icons.Default.MyLocation, Modifier.weight(1f)) { vm.send("locate") }
                QuickButton(R.string.ctl_live, Icons.Default.Bolt, Modifier.weight(1f)) { vm.send("live") }
                QuickButton(
                    if (vm.sirenOn) R.string.ctl_siren_stop else R.string.ctl_siren,
                    Icons.Default.NotificationsActive, Modifier.weight(1f),
                ) { vm.send(if (vm.sirenOn) "siren_off" else "siren_on") }
                val armed = device?.armed == true
                QuickButton(
                    if (armed) R.string.ctl_disarm else R.string.ctl_arm,
                    if (armed) Icons.Default.LockOpen else Icons.Default.Lock, Modifier.weight(1f),
                ) { vm.send(if (armed) "disarm" else "arm") }
            }
            vm.commandNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(it), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }

        // ---- remote tracking on/off (Remote role only; the tracker phone never sees this) ----
        if (isRemote) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ring_tracking), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        val armed = device?.armed == true
                        if (armed) {
                            Text(
                                stringResource(R.string.dash_tracking_guard_note),
                                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = vm.trackingPendingTo ?: (remoteTrackingOn == true),
                        enabled = remoteTrackingOn != null && device != null,
                        onCheckedChange = { on ->
                            remoteTrackingOn = on // optimistic; the pending state holds it until the tracker confirms
                            vm.send(if (on) "tracking_start" else "tracking_stop")
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ember, checkedThumbColor = Palette.Ink),
                    )
                }
                // A queued switch tap, or a tracker that has gone quiet: say so, instead of
                // showing a stale ON while nothing can reach the bike (v1.2.1 issue 1).
                if (vm.trackingPendingTo != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.dash_tracking_waiting),
                        color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (age == null || age >= 900_000L) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.dash_tracking_offline),
                        color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ---- small map ----
        Panel(Modifier.fillMaxWidth()) {
            BikeMap(
                modifier = Modifier.fillMaxWidth().height(208.dp),
                position = latest?.let { LatLng(it.lat, it.lng) },
                bearing = latest?.bearing ?: 0f,
                moving = (latest?.speed ?: 0f) > 1f && latest?.bearing != null,
                trail = vm.trail.map { LatLng(it.lat, it.lng) },
                follow = true,
                attributionBottomDp = 0,
                onUserGesture = {},
                geofences = vm.geofences,
                showRouteLine = ctx.bikeApp.prefs.showRouteLine,
                online = online,
            )
        }

        // ---- phone info ----
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mini(stringResource(R.string.stat_battery), latest?.battery?.let { "$it%" } ?: stringResource(R.string.none), Modifier.weight(1f))
                Mini(stringResource(R.string.stat_accuracy), latest?.accuracy?.let { stringResource(R.string.unit_m, it.toInt()) } ?: stringResource(R.string.none), Modifier.weight(1f))
                Mini(stringResource(R.string.stat_speed), latest?.speed?.let { stringResource(R.string.unit_kmh, (it * 3.6f).toInt()) } ?: stringResource(R.string.none), Modifier.weight(1f))
            }
        }

        // ---- today's ride ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dash_today), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (today.isEmpty()) {
                Text(stringResource(R.string.dash_no_ride), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${fmtKm(ctx, todayKm)}  ·  ${fmtDuration(ctx, todayMs)}  ·  " +
                        stringResource(R.string.trip_max, todayMax.toInt()),
                    style = MaterialTheme.typography.titleLarge, color = Palette.Ember,
                )
            }
        }

        // ---- odometer + service ----
        val odo = device?.odometerKm ?: 0.0
        if (odo > 0) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.odo_line, odo.toInt()), style = MaterialTheme.typography.titleMedium)
                val svc = device?.nextServiceKm?.takeIf { it > 0 }?.let { n ->
                    val left = (n - odo).toInt()
                    if (left >= 0) stringResource(R.string.service_in, left) else stringResource(R.string.service_overdue)
                }
                if (svc != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(svc, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ---- tracker health ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dash_health), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            // No device, or no location ever received: the health is unknown, never "healthy".
            val hasData = device != null && latest != null
            if (!hasData) {
                Text(stringResource(R.string.dash_health_unknown), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            } else if (healthBad && healthEvent != null) {
                Text(eventTitle(ctx, healthEvent.type, healthEvent.data?.toString()), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
                Text(agoText(ctx, healthEvent.createdMs, now), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            } else {
                Text(stringResource(R.string.dash_health_ok), color = Palette.Signal, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- where I parked ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dash_park_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (spot == null) {
                Text(stringResource(R.string.dash_park_none), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "${spot!!.lat}, ${spot!!.lng}",
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it; noteSaved = false }, singleLine = true,
                    label = { Text(stringResource(R.string.dash_park_note)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val id = vm.selectedId ?: return@OutlinedButton
                        scope.launch {
                            if (ctx.bikeApp.api.updateLatestParkingNote(id, note.trim())) noteSaved = true
                        }
                    }) { Text(stringResource(R.string.dash_park_save)) }
                    if (noteSaved) {
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.dash_park_saved), color = Palette.Signal, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ---- recent alerts ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dash_alerts), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val recent = alerts.events.take(3)
            if (recent.isEmpty()) {
                Text(stringResource(R.string.alerts_empty_title), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                recent.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(eventTitle(ctx, e.type, e.data?.toString()), style = MaterialTheme.typography.bodyMedium)
                            Text(agoText(ctx, e.createdMs, now), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

@Composable
private fun QuickButton(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
