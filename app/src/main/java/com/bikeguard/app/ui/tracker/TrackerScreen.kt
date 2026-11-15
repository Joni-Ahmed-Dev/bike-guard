package com.bikeguard.app.ui.tracker

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Tracker tab, split in v1.2.20 (the single file was ~21 KB): StatusRing,
 * TrackingTimer, PermissionStepCard, SosButton, StatCard and syncReasonText
 * now live in TrackerWidgets.kt in this package. This file only holds the screen.
 */
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
