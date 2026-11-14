package com.bikeguard.app.ui.tracker

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.Perms
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.tracker.GuardControl
import com.bikeguard.app.tracker.TrackerState
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.fmtHourMin
import com.bikeguard.app.ui.openBatterySettings
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TrackerScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    // Re-check permissions every time the user comes back from system settings.
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    // Session-only: skipping the activity step never blocks the tracker UI, and the
    // step is offered again on the next launch. Auto start just stays off until granted.
    var activitySkipped by remember { mutableStateOf(false) }
    val permsOk = remember(tick, vm.mode, activitySkipped) {
        Perms.hasFine(ctx) && Perms.hasBackground(ctx) && Perms.hasNotifications(ctx) &&
            (vm.mode != MODE_AUTO || AutoMode.hasPermission(ctx) || activitySkipped)
    }
    val batteryOk = remember(tick) { Perms.ignoringBatteryOptimizations(ctx) }
    // Re-grant path for the non-blocking panel below (the step card is gone once skipped).
    val activityPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val running by TrackerState.running.collectAsStateWithLifecycle()
    val online by TrackerState.online.collectAsStateWithLifecycle()
    val fix by TrackerState.lastFix.collectAsStateWithLifecycle()
    val fixStatus by TrackerState.fixStatus.collectAsStateWithLifecycle()
    val hasFix by TrackerState.hasFix.collectAsStateWithLifecycle()
    val parked by TrackerState.parked.collectAsStateWithLifecycle()
    val armed by TrackerState.armed.collectAsStateWithLifecycle()
    val queued by ctx.bikeApp.db.pending().count().collectAsStateWithLifecycle(0)
    val now = rememberNow()
    val always = vm.mode == MODE_ALWAYS
    val prefs = ctx.bikeApp.prefs
    // Last upload failure, if any: refreshed by rememberNow() ticking every second.
    val lastSyncError = prefs.lastSyncError
    val lastSyncAttemptMs = prefs.lastSyncAttemptMs

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.align(Alignment.Start))
        val (stateText, stateColor) = when {
            !running && vm.mode == MODE_AUTO -> R.string.state_auto_waiting to Palette.Muted
            !running -> R.string.state_stopped to Palette.Muted
            running && fixStatus == "gps_off" -> R.string.fix_gps_off to Palette.Ember
            running && fixStatus == "no_fix" -> R.string.fix_no_fix to Palette.Ember
            running && fixStatus == "last_known" -> R.string.fix_last_known to Palette.Ember
            running && online && parked && hasFix -> R.string.state_parked to Palette.Signal
            online && hasFix -> R.string.state_live to Palette.Signal
            running && !hasFix -> R.string.fix_no_fix to Palette.Ember
            else -> R.string.state_offline to Palette.Ember
        }
        Row(Modifier.align(Alignment.Start).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(stateColor, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(stateText), color = stateColor, style = MaterialTheme.typography.bodyMedium)
        }
        if (running && (fixStatus == "no_fix" || fixStatus == "gps_off")) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(if (fixStatus == "gps_off") R.string.fix_gps_off_hint else R.string.fix_no_fix_hint),
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Start),
            )
        }
        if (ctx.bikeApp.prefs.isTracker) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.tracker_view_only), color = Palette.Muted,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Start),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (!permsOk) {
            PermissionStepCard(
                tick = tick,
                onChanged = { tick++ },
                autoMode = vm.mode == MODE_AUTO,
                onSkipActivity = { activitySkipped = true; tick++ },
            )
        } else {
            val haptic = LocalHapticFeedback.current
            StatusRing(
                running = running, offline = running && !online, always = always,
                idleLabel = if (vm.mode == MODE_AUTO) R.string.ring_auto_wait else R.string.ring_start,
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.setTracking(!running) },
            )
            if (running && prefs.trackingStartedAtMs > 0) {
                Spacer(Modifier.height(10.dp))
                TrackingTimer(startedAtMs = prefs.trackingStartedAtMs)
            }
            if (always) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.always_hint), color = Palette.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            // Non-blocking: Auto mode runs, but only a manual tap can start tracking
            // until Physical activity is granted. Shown after the step was skipped.
            if (vm.mode == MODE_AUTO && !AutoMode.hasPermission(ctx)) {
                Spacer(Modifier.height(12.dp))
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.auto_perm_needed), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.perm_activity_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION) }) { Text(stringResource(R.string.grant)) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.guard_parking), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.guard_parking_desc), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = armed, onCheckedChange = { GuardControl.setArmed(ctx, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ember, checkedThumbColor = Palette.Ink),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.stat_last_fix), fix?.let { agoText(ctx, it.timeMs, now) } ?: stringResource(R.string.none), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_speed), fix?.let { stringResource(R.string.unit_kmh, it.speedKmh.toInt()) } ?: stringResource(R.string.none), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(stringResource(R.string.stat_accuracy), fix?.let { stringResource(R.string.unit_m, it.accuracy.toInt()) } ?: stringResource(R.string.none), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_queued), stringResource(R.string.unit_points, queued), Modifier.weight(1f), highlight = queued > 5)
            }
            if (lastSyncError.isNotBlank() && lastSyncAttemptMs > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.sync_last_try, fmtHourMin(lastSyncAttemptMs), syncReasonText(lastSyncError)),
                    color = Palette.Ember, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(16.dp))
            SosButton { GuardControl.sos(ctx) }
            if (!batteryOk) {
                Spacer(Modifier.height(16.dp))
                Panel(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.battery_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { openBatterySettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
                }
            }
        }
    }
}

@Composable
private fun StatusRing(running: Boolean, offline: Boolean, always: Boolean, idleLabel: Int, onClick: () -> Unit) {
    val color by animateColorAsState(
        when { !running -> Palette.Muted; offline -> Palette.Ember; else -> Palette.Signal }, label = "ringColor"
    )
    val inf = rememberInfiniteTransition(label = "ring")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "rot")
    val pulse by inf.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")

    Box(
        Modifier.size(120.dp).clickable(
            enabled = !always, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { if (running) { scaleX = pulse; scaleY = pulse } }) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            drawCircle(color.copy(alpha = 0.07f), radius = size.minDimension / 2)
            drawCircle(color.copy(alpha = 0.22f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (running) {
                rotate(rot) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, color), center = center),
                        startAngle = 0f, sweepAngle = 280f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(when { always -> R.string.ring_always; running -> R.string.ring_tracking; else -> idleLabel }),
                color = if (running) Palette.Text else Palette.Muted, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Live "tracking on for HH:MM:SS" timer, shown only while tracking runs. */
@Composable
private fun TrackingTimer(startedAtMs: Long) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) { delay(1000); nowMs = System.currentTimeMillis() }
    }
    val elapsed = (nowMs - startedAtMs).coerceAtLeast(0)
    val hours = elapsed / 3_600_000
    val minutes = (elapsed / 60_000) % 60
    val seconds = (elapsed / 1000) % 60
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tracking_timer_label), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds),
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun syncReasonText(reason: String): String = when {
    reason == "no internet" -> stringResource(R.string.sync_err_no_internet)
    reason == "timed out" -> stringResource(R.string.sync_err_timeout)
    reason == "signed out" -> stringResource(R.string.sync_err_signed_out)
    reason.contains("server ") -> stringResource(
        R.string.sync_err_server,
        reason.substringAfter("server ").substringBefore(":").substringBefore(" ").trim(),
    )
    else -> reason
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Panel(modifier) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (highlight) Palette.Ember else Palette.Text, style = MaterialTheme.typography.titleLarge)
    }
}

private enum class Step { NOTIF, LOCATION, BACKGROUND, ACTIVITY }

@Composable
private fun PermissionStepCard(tick: Int, onChanged: () -> Unit, autoMode: Boolean, onSkipActivity: () -> Unit) {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    val steps = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Step.NOTIF)
        add(Step.LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Step.BACKGROUND)
        // Physical activity is only needed in Auto mode: it powers the
        // in-vehicle detection that starts tracking when the bike moves.
        if (autoMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Step.ACTIVITY)
    }
    val done = remember(tick, autoMode) {
        steps.filter { s ->
            when (s) {
                Step.NOTIF -> Perms.hasNotifications(ctx)
                Step.LOCATION -> Perms.hasFine(ctx)
                Step.BACKGROUND -> Perms.hasBackground(ctx)
                Step.ACTIVITY -> AutoMode.hasPermission(ctx)
            }
        }.toSet()
    }
    val current = steps.firstOrNull { it !in done } ?: return
    val (title, body) = when (current) {
        Step.NOTIF -> R.string.perm_notif_title to R.string.perm_notif_body
        Step.LOCATION -> R.string.perm_loc_title to R.string.perm_loc_body
        Step.BACKGROUND -> R.string.perm_bg_title to R.string.perm_bg_body
        Step.ACTIVITY -> R.string.perm_activity_title to R.string.perm_activity_body
    }

    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.perm_step, steps.indexOf(current) + 1, steps.size), color = Palette.Ember, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { done.size / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Palette.Ember, trackColor = Palette.Stroke,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (current) {
                    Step.NOTIF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Step.LOCATION -> loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    Step.BACKGROUND -> bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    Step.ACTIVITY -> activity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.grant)) }
        // Denying activity must never trap the user on this card: Auto start just
        // stays off, tracking can still be started by tapping the button.
        if (current == Step.ACTIVITY) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkipActivity, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.perm_skip)) }
        }
    }
}

/** Long-press on purpose: a stray tap must never send an SOS. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SosButton(onTrigger: () -> Unit) {
    var sent by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape, color = Palette.Danger.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Palette.Danger.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
            onClick = {},
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onTrigger(); sent = true },
        ),
    ) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (sent) R.string.sos_sent else R.string.sos_hold),
                color = Palette.Danger, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
