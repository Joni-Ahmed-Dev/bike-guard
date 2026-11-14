package com.bikeguard.app.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.ui.viewer.OfflineMap
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.MODE_MANUAL
import com.bikeguard.app.data.Perms
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.tracker.Notifier
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.openBatterySettings
import com.bikeguard.app.ui.diagnostics.DiagnosticsScreen
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    val segColors = SegmentedButtonDefaults.colors(
        activeContainerColor = Palette.Ember.copy(alpha = 0.18f), activeContentColor = Palette.Ember,
        activeBorderColor = Palette.Ember, inactiveContainerColor = Color.Transparent,
        inactiveContentColor = Palette.Muted, inactiveBorderColor = Palette.Stroke,
    )

    // Local mirrors of the stored settings so the switches react instantly.
    // Keyed on the server row: when it loads, the screen shows the server's values.
    var autoArm by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.autoArm ?: prefs.autoArm) }
    var siren by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.sirenOnAlert ?: prefs.sirenOnAlert) }
    var crash by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.crashEnabled ?: prefs.crashDetection) }
    var sms by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.sosSms ?: prefs.sosSms) }
    var number by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.sosNumber ?: prefs.emergencyNumber) }
    var limit by remember(vm.remoteSettings) { mutableIntStateOf(vm.remoteSettings?.speedLimitKmh ?: prefs.speedLimitKmh) }
    var serviceEvery by remember(vm.remoteSettings) { mutableIntStateOf(vm.remoteSettings?.serviceKm ?: prefs.serviceIntervalKm) }
    var odo by remember { mutableIntStateOf(prefs.odometerKm.toInt()) }
    var next by remember { mutableIntStateOf(prefs.nextServiceKm.toInt()) }
    var lock by remember { mutableStateOf(prefs.appLock) }
    var autoDenied by remember { mutableStateOf(false) }
    var smsDenied by remember { mutableStateOf(false) }
    var lockMsg by remember { mutableStateOf(false) }

    val isTracker = prefs.isTracker
    val isRemote = prefs.isRemote
    var lowData by remember { mutableStateOf(prefs.lowDataMode) }
    var showRoute by remember { mutableStateOf(prefs.showRouteLine) }
    var dlResult by remember { mutableStateOf<Int?>(null) }
    var schedStart by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.guardScheduleStart?.toString() ?: prefs.guardScheduleStart.takeIf { it in 0..23 }?.toString() ?: "") }
    var schedEnd by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.guardScheduleEnd?.toString() ?: prefs.guardScheduleEnd.takeIf { it in 0..23 }?.toString() ?: "") }
    var familyCode by remember { mutableStateOf<String?>(null) }
    var inviteInput by remember { mutableStateOf(prefs.viewerInviteCode.orEmpty()) }
    var inviteSaved by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinWrong by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { if (isRemote) vm.loadRemoteSettings() }

    val activityPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        autoDenied = !ok
        if (ok) vm.updateMode(MODE_AUTO)
    }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        smsDenied = !ok
        if (ok) { sms = true; prefs.sosSms = true; vm.pushSettings() }
    }
    // ---- notification sounds (issue #8): one ringtone per channel ----
    var alertSound by remember { mutableStateOf(prefs.alertRingtoneUri) }
    var alertSoundName by remember { mutableStateOf(Notifier.ringtoneName(ctx, prefs.alertRingtoneUri)) }
    var generalSound by remember { mutableStateOf(prefs.generalRingtoneUri) }
    var generalSoundName by remember { mutableStateOf(Notifier.ringtoneName(ctx, prefs.generalRingtoneUri)) }
    var notifOk by remember { mutableStateOf(Perms.hasNotifications(ctx)) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notifOk = ok
    }
    val alertSoundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Notifier.pickedRingtoneUri(result.data)?.let { uri ->
            alertSound = uri.toString()
            Notifier.setAlertRingtone(ctx, uri.toString())
            alertSoundName = Notifier.ringtoneName(ctx, uri.toString())
        }
    }
    val generalSoundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        Notifier.pickedRingtoneUri(result.data)?.let { uri ->
            generalSound = uri.toString()
            Notifier.setGeneralRingtone(ctx, uri.toString())
            generalSoundName = Notifier.ringtoneName(ctx, uri.toString())
        }
    }
    fun pickMode(m: String) {
        if (m == MODE_AUTO && !AutoMode.hasPermission(ctx)) activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        else { autoDenied = false; vm.updateMode(m) }
    }

    // Diagnostics is a full screen of its own: it must NOT be nested inside the
    // scrollable settings Column, or Compose measures its own vertical scroll
    // with infinite height and crashes (tap Diagnostics -> app exits).
    if (showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false }, Modifier.fillMaxSize())
        return
    }
    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

        // ---- diagnostics: what is happening inside the app, and a report to Muse ----
        Panel(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showDiagnostics = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diag_title)) }
        }

        // ---- sync status (Remote only): did the bike phone apply the settings? ----
        if (isRemote) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_target, vm.settingsTargetId.take(8)),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(2.dp))
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
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = vm::loadRemoteSettings) { Text(stringResource(R.string.sync_refresh)) }
                }
            }
        }

        // ---- tracker role (and invite mode): settings are view-only ----
        if (isTracker || vm.inviteCode != null) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tracker_view_only), color = Palette.Ember, style = MaterialTheme.typography.bodyMedium)
            }
            Panel(Modifier.fillMaxWidth()) {
                ReadOnlyRow(stringResource(R.string.set_mode), stringResource(when (vm.mode) {
                    MODE_MANUAL -> R.string.mode_manual
                    MODE_AUTO -> R.string.mode_auto
                    else -> R.string.mode_always
                }))
                ReadOnlyRow(stringResource(R.string.set_interval), stringResource(R.string.interval_s, vm.intervalSec))
                ReadOnlyRow(stringResource(R.string.limit_title), if (limit == 0) stringResource(R.string.limit_off) else stringResource(R.string.limit_kmh, limit))
                ReadOnlyRow(stringResource(R.string.guard_parking), if (prefs.parkingArmed) stringResource(R.string.ctl_arm) else stringResource(R.string.ctl_disarm))
                ReadOnlyRow(stringResource(R.string.guard_auto), if (prefs.autoArm) stringResource(R.string.ctl_arm) else stringResource(R.string.ctl_disarm))
                ReadOnlyRow(stringResource(R.string.safety_crash), if (crash) stringResource(R.string.ctl_arm) else stringResource(R.string.ctl_disarm))
                ReadOnlyRow(stringResource(R.string.safety_number), number.ifBlank { stringResource(R.string.none) })
                ReadOnlyRow(stringResource(R.string.set_service), if (serviceEvery == 0) stringResource(R.string.service_off) else stringResource(R.string.service_every, serviceEvery))
            }
        }
        if (isTracker) {
            Panel(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (testing) return@Button
                        testing = true; testResult = null
                        scope.launch {
                            val ok = ctx.bikeApp.sync.flush()
                            val err = prefs.lastSyncError
                            testOk = ok
                            testResult = if (ok) ctx.getString(R.string.sync_test_ok)
                            else when {
                                err == "no internet" -> ctx.getString(R.string.sync_err_no_internet)
                                err == "timed out" -> ctx.getString(R.string.sync_err_timeout)
                                err == "signed out" -> ctx.getString(R.string.sync_err_signed_out)
                                err.contains("server ") -> ctx.getString(
                                    R.string.sync_err_server,
                                    err.substringAfter("server ").substringBefore(":").substringBefore(" ").trim(),
                                )
                                else -> ctx.getString(R.string.sync_err_no_internet)
                            }
                            testing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (testing) R.string.sync_test_checking else R.string.sync_test)) }
                testResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = if (testOk) Palette.Signal else Palette.Danger, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                // The Supabase project this build talks to, so it can be compared with the dashboard.
                Text(
                    stringResource(
                        R.string.sync_test_project,
                        runCatching { java.net.URI(BuildConfig.SUPABASE_URL).host }.getOrNull()
                            .orEmpty().ifBlank { stringResource(R.string.none) },
                    ),
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // ---- editable settings: Remote only, and never while an invite code is set ----
        if (isRemote && vm.inviteCode == null) {
        // ---- tracking mode ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_mode), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val modes = listOf(MODE_MANUAL to R.string.mode_manual, MODE_AUTO to R.string.mode_auto, MODE_ALWAYS to R.string.mode_always)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
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

        // ---- interval ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_interval), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val options = listOf(5, 7, 10)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
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

        // ---- anti-theft ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_guard), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.guard_auto), null, autoArm) { autoArm = it; prefs.autoArm = it; if (isRemote) vm.pushSettings() }
            SwitchRow(stringResource(R.string.guard_siren), null, siren) { siren = it; prefs.sirenOnAlert = it; vm.pushSettings() }
        }

        // ---- safety ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_safety), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.safety_crash), null, crash) { crash = it; prefs.crashDetection = it; vm.pushSettings() }
            SwitchRow(stringResource(R.string.safety_sms), stringResource(R.string.safety_sms_hint), sms) { on ->
                if (!on) { sms = false; prefs.sosSms = false }
                else smsPerm.launch(Manifest.permission.SEND_SMS)
                vm.pushSettings()
            }
            if (smsDenied) Text(stringResource(R.string.sms_perm_needed), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = number, onValueChange = { number = it; prefs.emergencyNumber = it.trim() }, singleLine = true,
                label = { Text(stringResource(R.string.safety_number)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    .onFocusChanged { if (!it.isFocused) vm.pushSettings() },
            )
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.limit_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChipRow(
                listOf(0, 60, 80, 100, 120), limit,
                { if (it == 0) ctx.getString(R.string.limit_off) else ctx.getString(R.string.limit_kmh, it) },
            ) { limit = it; prefs.speedLimitKmh = it; prefs.stateDirty = true; vm.pushSettings() }
        }

        // ---- service reminder ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_service), style = MaterialTheme.typography.titleMedium)
            if (!isRemote) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.service_odo, odo), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                if (next > 0) Text(stringResource(R.string.service_next, next), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            ChipRow(
                listOf(0, 1000, 2000, 3000, 5000), serviceEvery,
                { if (it == 0) ctx.getString(R.string.service_off) else ctx.getString(R.string.service_every, it) },
            ) {
                serviceEvery = it; prefs.scheduleService(it)
                odo = prefs.odometerKm.toInt(); next = prefs.nextServiceKm.toInt()
                vm.pushSettings()
            }
            if (serviceEvery > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    // On the Remote phone this must reach the bike as a command; only changing
                    // this phone's prefs would silently do nothing.
                    if (isRemote) vm.sendRemoteCommand("mark_serviced")
                    else { prefs.markServiced(); next = prefs.nextServiceKm.toInt(); vm.pushSettings() }
                }) {
                    Text(stringResource(R.string.service_mark))
                }
            }
        }

        // ---- app lock ----
        Panel(Modifier.fillMaxWidth()) {
            SwitchRow(stringResource(R.string.set_lock), stringResource(R.string.lock_desc), lock) { on ->
                val ok = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
                if (on && !ok) lockMsg = true else { lockMsg = false; lock = on; prefs.appLock = on }
            }
            if (lockMsg) Text(stringResource(R.string.lock_unavailable), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
        }

        // ---- guard schedule (Remote only) ----
        if (isRemote) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.schedule_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.schedule_desc), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = schedStart, onValueChange = { schedStart = it.filter { c -> c.isDigit() }.take(2) },
                        singleLine = true, label = { Text(stringResource(R.string.schedule_start)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).onFocusChanged {
                            if (!it.isFocused) {
                                prefs.guardScheduleStart = schedStart.toIntOrNull()?.takeIf { h -> h in 0..23 } ?: -1
                                vm.pushSettings()
                            }
                        },
                    )
                    OutlinedTextField(
                        value = schedEnd, onValueChange = { schedEnd = it.filter { c -> c.isDigit() }.take(2) },
                        singleLine = true, label = { Text(stringResource(R.string.schedule_end)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).onFocusChanged {
                            if (!it.isFocused) {
                                prefs.guardScheduleEnd = schedEnd.toIntOrNull()?.takeIf { h -> h in 0..23 } ?: -1
                                vm.pushSettings()
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    schedStart = ""; schedEnd = ""
                    prefs.guardScheduleStart = -1; prefs.guardScheduleEnd = -1
                    vm.pushSettings()
                }) { Text(stringResource(R.string.schedule_off)) }
            }

            // ---- low-data mode (this phone's Viewer) ----
            Panel(Modifier.fillMaxWidth()) {
                SwitchRow(stringResource(R.string.lowdata_title), stringResource(R.string.lowdata_desc), lowData) {
                    lowData = it; prefs.lowDataMode = it
                }
            }

            // ---- route line (this phone's Viewer only, never synced) ----
            Panel(Modifier.fillMaxWidth()) {
                SwitchRow(stringResource(R.string.route_line_title), stringResource(R.string.route_line_desc), showRoute) {
                    showRoute = it; prefs.showRouteLine = it
                }
            }

            // ---- offline map download (this phone's Viewer only) ----
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.offline_dl_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                val hasPos = prefs.lastKnownLat != 0.0 || prefs.lastKnownLng != 0.0
                Text(
                    stringResource(if (hasPos) R.string.offline_dl_desc else R.string.offline_dl_no_pos),
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        dlResult = null
                        OfflineMap.downloadAround(ctx, prefs.lastKnownLat, prefs.lastKnownLng) { dlResult = it }
                    },
                    enabled = hasPos,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.offline_dl_title)) }
                dlResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(it), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }

        }
        } // end of editable settings (tracker role sees them read-only above)

        // ---- family view-only invite: always shown while an invite code is set, even
        // when no role is picked, so the code (and Remove invite) is never hidden ----
        if (isRemote || vm.inviteCode != null) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.family_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.family_desc), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                if (isRemote) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.createFamilyInvite { familyCode = it } }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.family_generate))
                    }
                    familyCode?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.family_code, it), style = MaterialTheme.typography.titleMedium, color = Palette.Ember)
                    }
                }
                if (vm.inviteCode == null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inviteInput, onValueChange = { inviteInput = it.trim(); inviteSaved = false }, singleLine = true,
                        label = { Text(stringResource(R.string.family_use)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        vm.updateInviteCode(inviteInput); inviteSaved = inviteInput.isNotBlank()
                    }) { Text(stringResource(R.string.add)) }
                } else {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.family_code, vm.inviteCode.orEmpty()), style = MaterialTheme.typography.titleMedium, color = Palette.Ember)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.clearInviteCode(); inviteInput = ""; inviteSaved = false }) {
                        Text(stringResource(R.string.invite_clear))
                    }
                }
                if (inviteSaved) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.family_saved), color = Palette.Signal, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ---- battery ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.battery_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { openBatterySettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
        }

        // ---- notification sounds (issue #8): one ringtone per channel ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.notif_sound_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            RingtoneRow(stringResource(R.string.ringtone_alerts), alertSoundName) {
                alertSoundPicker.launch(Notifier.ringtonePickerIntent(ctx, alertSound, ctx.getString(R.string.ringtone_pick_title)))
            }
            RingtoneRow(stringResource(R.string.ringtone_general), generalSoundName) {
                generalSoundPicker.launch(Notifier.ringtonePickerIntent(ctx, generalSound, ctx.getString(R.string.ringtone_pick_title)))
            }
            // Issue #6: the Remote phone never asked for POST_NOTIFICATIONS (only the
            // tracker setup flow did), so alerts could never appear there. Denial is
            // fine: alerts still land in the Alerts tab, just without a push.
            if (!notifOk) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.notif_perm_needed), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    Perms.notificationsPermission()?.let { notifPerm.launch(it) } ?: run { notifOk = true }
                }) { Text(stringResource(R.string.grant)) }
            }
        }

        // ---- phone role ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_role), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.role_current,
                    stringResource(
                        if (isTracker) R.string.role_tracker
                        else if (vm.role.isEmpty()) R.string.invite_mode
                        else R.string.role_remote
                    ),
                ),
                color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { pinInput = ""; pinWrong = false; showPinDialog = true }) {
                Text(stringResource(R.string.role_change))
            }
        }

        if (showPinDialog) {
            AlertDialog(
                onDismissRequest = { showPinDialog = false },
                containerColor = Palette.Surface,
                title = { Text(stringResource(R.string.role_change_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { pinInput = it.filter { c -> c.isDigit() }.take(4); pinWrong = false },
                            singleLine = true, label = { Text(stringResource(R.string.pin_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (pinWrong) Text(stringResource(R.string.role_pin_wrong), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (vm.clearRoleAfterPin(pinInput)) showPinDialog = false
                        else pinWrong = true
                    }) { Text(stringResource(R.string.continue_label)) }
                },
                dismissButton = {
                    TextButton(onClick = { showPinDialog = false }) { Text(stringResource(R.string.close)) }
                },
            )
        }

        // ---- account ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_account), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(vm.email, color = Palette.Muted)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.set_device), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(vm.deviceId.take(8), color = Palette.Muted)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = vm::signOut,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Danger),
            ) { Text(stringResource(R.string.sign_out)) }
        }

        Text(
            stringResource(R.string.version, BuildConfig.VERSION_NAME),
            color = Palette.Muted, style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun RingtoneRow(title: String, current: String, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(current, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onPick) { Text(stringResource(R.string.ringtone_change)) }
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) Text(subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Ember, checkedThumbColor = Palette.Ink),
        )
    }
}

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Palette.Ember.copy(alpha = 0.2f), selectedLabelColor = Palette.Ember,
                ),
            )
        }
    }
}
