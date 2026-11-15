package com.bikeguard.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.MODE_MANUAL
import com.bikeguard.app.data.Perms
import com.bikeguard.app.data.isoToMs
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

/** Split out of SettingsScreen.kt (v1.2.20): the Remote-only editable sections. Each keeps its own server-keyed mirrors, exactly as before, so behavior is unchanged. */

// ---- sync status: did the bike phone apply the settings? ----
@Composable
fun SyncStatusPanel(vm: AppViewModel, now: Long) {
    val ctx = LocalContext.current
    val watched = vm.watchedDevice
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader(
            Icons.Default.Sync,
            stringResource(R.string.settings_target, watched?.name ?: vm.settingsTargetId.take(8)),
        )
        Spacer(Modifier.height(12.dp))
        // Which device row is being watched: model + short id + last seen,
        // so a re-registered tracker is visible here instead of a
        // silent stale target.
        watched?.let { d ->
            val seenMs = d.lastSeen?.let { isoToMs(it) } ?: 0L
            Text(
                (d.id?.take(8) ?: "") +
                    if (seenMs > 0) " · " + stringResource(R.string.stat_updated) + " " + agoText(ctx, seenMs, now) else "",
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            when (vm.settingsApplied) {
                true -> stringResource(R.string.sync_applied)
                false -> stringResource(R.string.sync_pending)
                null -> stringResource(R.string.none)
            },
            color = if (vm.settingsApplied == true) Palette.Signal else Palette.Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        vm.settingsNote?.let {
            Text(it, color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        LeafOutlinedButton(onClick = vm::loadRemoteSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sync_refresh))
        }
    }
}

// ---- tracking mode ----
@Composable
fun ModePanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    var autoDenied by remember { mutableStateOf(false) }
    val segColors = rememberSegColors()
    val activityPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        autoDenied = !ok
        if (ok) vm.updateMode(MODE_AUTO)
    }
    fun pickMode(m: String) {
        if (m == MODE_AUTO && !AutoMode.hasPermission(ctx)) activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        else { autoDenied = false; vm.updateMode(m) }
    }
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader(Icons.Default.Tune, stringResource(R.string.set_mode))
        Spacer(Modifier.height(12.dp))
        val modes = listOf(MODE_MANUAL to R.string.mode_manual, MODE_AUTO to R.string.mode_auto, MODE_ALWAYS to R.string.mode_always)
        // 48dp rows (v1.2.20): the Material3 default segmented row is 40dp.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(48.dp)) {
            modes.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = vm.mode == value, onClick = { pickMode(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size), colors = segColors,
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.mode_hint), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        if (autoDenied) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.auto_perm_needed), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- interval ----
@Composable
fun IntervalPanel(vm: AppViewModel) {
    val segColors = rememberSegColors()
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader(Icons.Default.Timer, stringResource(R.string.set_interval))
        Spacer(Modifier.height(12.dp))
        val options = listOf(5, 7, 10)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(48.dp)) {
            options.forEachIndexed { i, sec ->
                SegmentedButton(
                    selected = vm.intervalSec == sec, onClick = { vm.setInterval(sec) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size), colors = segColors,
                ) { Text(stringResource(R.string.interval_s, sec)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.interval_hint), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- anti-theft ----
@Composable
fun GuardPanel(vm: AppViewModel) {
    val prefs = LocalContext.current.bikeApp.prefs
    var autoArm by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.autoArm ?: prefs.autoArm) }
    var siren by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.sirenOnAlert ?: prefs.sirenOnAlert) }
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader(Icons.Default.Lock, stringResource(R.string.set_guard))
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.guard_auto), null, autoArm, onChange = { autoArm = it; prefs.autoArm = it; vm.pushSettingsPatch(autoArm = it) }, icon = Icons.Default.Shield)
        SettingsDivider()
        SwitchRow(stringResource(R.string.guard_siren), null, siren, onChange = { siren = it; prefs.sirenOnAlert = it; vm.pushSettingsPatch(sirenOnAlert = it) }, icon = Icons.Default.NotificationsActive)
    }
}

// ---- safety ----
@Composable
fun SafetyPanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    var crash by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.crashEnabled ?: prefs.crashDetection) }
    var sms by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.sosSms ?: prefs.sosSms) }
    var number by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.sosNumber ?: prefs.emergencyNumber) }
    var limit by remember(vm.remoteSettings) { mutableIntStateOf(vm.remoteSettings?.speedLimitKmh ?: prefs.speedLimitKmh) }
    // Live SEND_SMS state (v1.2.19): the toggle used to check the permission
    // only at send time, so a revoked permission was discovered in an
    // emergency. Checked on entry and after every permission result, so the
    // panel below can warn before one.
    var smsPermOk by remember { mutableStateOf(Perms.hasSms(ctx)) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        smsPermOk = ok
        if (ok) { sms = true; prefs.sosSms = true; vm.pushSettingsPatch(sosSms = true) }
    }
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader(Icons.Default.Security, stringResource(R.string.set_safety))
        Spacer(Modifier.height(8.dp))
        SwitchRow(stringResource(R.string.safety_crash), null, crash, onChange = { crash = it; prefs.crashDetection = it; vm.pushSettingsPatch(crashEnabled = it) }, icon = Icons.Default.CarCrash)
        SettingsDivider()
        SwitchRow(stringResource(R.string.safety_sms), stringResource(R.string.safety_sms_hint), sms, onChange = { on ->
            // Only the OFF branch pushes here: turning ON pushes from the
            // permission callback once granted, so the server never briefly
            // sees the still-false local value (v1.2.17).
            if (!on) { sms = false; prefs.sosSms = false; vm.pushSettingsPatch(sosSms = false) }
            else smsPerm.launch(Manifest.permission.SEND_SMS)
        }, icon = Icons.Default.Message)
        // What actually happens (v1.2.19): sosSms defaults to false, so
        // crash detection alone used to look like it sends an SMS when it
        // does not. Say it plainly for every state, and offer the
        // permission grant right here when it is the missing piece.
        Spacer(Modifier.height(6.dp))
        when {
            !sms -> Text(stringResource(R.string.safety_sms_off), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            number.isBlank() -> Text(stringResource(R.string.safety_sms_no_number), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            !smsPermOk -> {
                Text(stringResource(R.string.sms_perm_needed), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                LeafOutlinedButton(onClick = { smsPerm.launch(Manifest.permission.SEND_SMS) }) { Text(stringResource(R.string.grant)) }
            }
            else -> Text(stringResource(R.string.safety_sms_on, number.trim()), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = number, onValueChange = { number = it; prefs.emergencyNumber = it.trim() }, singleLine = true,
            label = { Text(stringResource(R.string.safety_number)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card,
                focusedBorderColor = Palette.Leaf, unfocusedBorderColor = Palette.Stroke,
                cursorColor = Palette.Text,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                .onFocusChanged { if (!it.isFocused) vm.pushSettingsPatch(sosNumber = prefs.emergencyNumber) },
        )
        Spacer(Modifier.height(14.dp))
        SettingsDivider()
        Text(stringResource(R.string.limit_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ChipRow(
            listOf(0, 60, 80, 100, 120), limit,
            { if (it == 0) ctx.getString(R.string.limit_off) else ctx.getString(R.string.limit_kmh, it) },
        ) { limit = it; prefs.speedLimitKmh = it; prefs.stateDirty = true; vm.pushSettingsPatch(speedLimitKmh = it) }
    }
}

// ---- service reminder ----
@Composable
fun ServicePanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    var serviceEvery by remember(vm.remoteSettings) { mutableIntStateOf(vm.remoteSettings?.serviceKm ?: prefs.serviceIntervalKm) }
    Panel(Modifier.fillMaxWidth()) {
        PanelHeader(Icons.Default.Build, stringResource(R.string.set_service))
        Spacer(Modifier.height(8.dp))
        ChipRow(
            listOf(0, 1000, 2000, 3000, 5000), serviceEvery,
            { if (it == 0) ctx.getString(R.string.service_off) else ctx.getString(R.string.service_every, it) },
        ) {
            serviceEvery = it; prefs.scheduleService(it)
            vm.pushSettingsPatch(serviceKm = it)
        }
        if (serviceEvery > 0) {
            Spacer(Modifier.height(8.dp))
            LeafOutlinedButton(onClick = {
                // On the Remote phone this must reach the bike as a command; only changing
                // this phone's prefs would silently do nothing.
                vm.sendRemoteCommand("mark_serviced")
            }) {
                Text(stringResource(R.string.service_mark))
            }
        }
    }
}
