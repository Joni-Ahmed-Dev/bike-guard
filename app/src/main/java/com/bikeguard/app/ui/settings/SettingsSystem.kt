package com.bikeguard.app.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.LANG_SYSTEM
import com.bikeguard.app.data.Perms
import com.bikeguard.app.data.THEME_DARK
import com.bikeguard.app.data.THEME_LIGHT
import com.bikeguard.app.data.THEME_SYSTEM
import com.bikeguard.app.tracker.Notifier
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.LocaleHelper
import com.bikeguard.app.ui.openBatterySettings
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.viewer.OfflineMap

/** Split out of SettingsScreen.kt (v1.2.20): the role-independent sections. */

// ---- language & theme (v1.2.20) ----
@Composable
fun AppearancePanel() {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    val activity = ctx as? Activity
    var language by remember { mutableStateOf(prefs.language) }
    var theme by remember { mutableStateOf(prefs.themeMode) }
    val segColors = rememberSegColors()
    ExpandablePanel(Icons.Default.Settings, stringResource(R.string.appearance_title)) {
        Text(stringResource(R.string.lang_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val langs = listOf(LANG_SYSTEM to R.string.lang_system, "en" to R.string.lang_english, "bn" to R.string.lang_bengali)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(48.dp)) {
            langs.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = language == value,
                    onClick = { if (value != language) { language = value; activity?.let { LocaleHelper.setLanguage(it, value) } } },
                    shape = SegmentedButtonDefaults.itemShape(i, langs.size), colors = segColors,
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val themes = listOf(THEME_DARK to R.string.theme_dark, THEME_LIGHT to R.string.theme_light, THEME_SYSTEM to R.string.theme_system)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(48.dp)) {
            themes.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = theme == value,
                    onClick = { if (value != theme) { theme = value; activity?.let { LocaleHelper.setThemeMode(it, value) } } },
                    shape = SegmentedButtonDefaults.itemShape(i, themes.size), colors = segColors,
                ) { Text(stringResource(label)) }
            }
        }
    }
}

// ---- app lock ----
@Composable
fun LockPanel() {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    var lock by remember { mutableStateOf(prefs.appLock) }
    var lockMsg by remember { mutableStateOf(false) }
    ExpandablePanel(Icons.Default.Lock, stringResource(R.string.set_lock)) {
        SwitchRow(stringResource(R.string.set_lock), stringResource(R.string.lock_desc), lock, onChange = { on ->
            val ok = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
            if (on && !ok) lockMsg = true else { lockMsg = false; lock = on; prefs.appLock = on }
        }, icon = Icons.Default.Lock)
        if (lockMsg) Text(stringResource(R.string.lock_unavailable), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- guard schedule (Remote only) ----
@Composable
fun SchedulePanel(vm: AppViewModel) {
    val prefs = LocalContext.current.bikeApp.prefs
    var schedStart by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.guardScheduleStart?.toString() ?: prefs.guardScheduleStart.takeIf { it in 0..23 }?.toString() ?: "") }
    var schedEnd by remember(vm.remoteSettings) { mutableStateOf(vm.remoteSettings?.guardScheduleEnd?.toString() ?: prefs.guardScheduleEnd.takeIf { it in 0..23 }?.toString() ?: "") }
    ExpandablePanel(Icons.Default.Schedule, stringResource(R.string.schedule_title)) {
        Text(stringResource(R.string.schedule_desc), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = schedStart, onValueChange = { schedStart = it.filter { c -> c.isDigit() }.take(2) },
                singleLine = true, label = { Text(stringResource(R.string.schedule_start)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card,
                    focusedBorderColor = Palette.Leaf, unfocusedBorderColor = Palette.Stroke,
                    cursorColor = Palette.Text,
                ),
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (!it.isFocused) {
                        val h = schedStart.toIntOrNull()?.takeIf { h -> h in 0..23 }
                        prefs.guardScheduleStart = h ?: -1
                        if (h != null) vm.pushSettingsPatch(guardScheduleStart = h)
                        else vm.pushSettingsPatch(clearScheduleStart = true)
                    }
                },
            )
            OutlinedTextField(
                value = schedEnd, onValueChange = { schedEnd = it.filter { c -> c.isDigit() }.take(2) },
                singleLine = true, label = { Text(stringResource(R.string.schedule_end)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Palette.Card, unfocusedContainerColor = Palette.Card,
                    focusedBorderColor = Palette.Leaf, unfocusedBorderColor = Palette.Stroke,
                    cursorColor = Palette.Text,
                ),
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (!it.isFocused) {
                        val h = schedEnd.toIntOrNull()?.takeIf { h -> h in 0..23 }
                        prefs.guardScheduleEnd = h ?: -1
                        if (h != null) vm.pushSettingsPatch(guardScheduleEnd = h)
                        else vm.pushSettingsPatch(clearScheduleEnd = true)
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        LeafTextButton(onClick = {
            schedStart = ""; schedEnd = ""
            prefs.guardScheduleStart = -1; prefs.guardScheduleEnd = -1
            vm.pushSettingsPatch(clearScheduleStart = true, clearScheduleEnd = true)
        }) { Text(stringResource(R.string.schedule_off)) }
    }
}

// ---- low-data mode + route line + offline map download (Remote only, this phone's Viewer) ----
@Composable
fun LocalViewerPanel() {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    var lowData by remember { mutableStateOf(prefs.lowDataMode) }
    var dlResult by remember { mutableStateOf<Int?>(null) }
    ExpandablePanel(Icons.Default.DataUsage, stringResource(R.string.lowdata_title)) {
        SwitchRow(stringResource(R.string.lowdata_title), stringResource(R.string.lowdata_desc), lowData, onChange = {
            lowData = it; prefs.lowDataMode = it
        }, icon = Icons.Default.DataUsage)
    }
    ExpandablePanel(Icons.Default.Download, stringResource(R.string.offline_dl_title)) {
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
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text(stringResource(R.string.offline_dl_title)) }
        dlResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(it), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- battery ----
@Composable
fun BatteryPanel() {
    val ctx = LocalContext.current
    ExpandablePanel(Icons.Default.BatteryFull, stringResource(R.string.battery_title)) {
        Text(stringResource(R.string.battery_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        LeafOutlinedButton(onClick = { openBatterySettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
    }
}

// ---- notification sounds (issue #8): one ringtone per channel ----
@Composable
fun SoundsPanel() {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
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
    ExpandablePanel(Icons.Default.VolumeUp, stringResource(R.string.notif_sound_title)) {
        RingtoneRow(stringResource(R.string.ringtone_alerts), alertSoundName, onPick = {
            alertSoundPicker.launch(Notifier.ringtonePickerIntent(ctx, alertSound, ctx.getString(R.string.ringtone_pick_title)))
        }, icon = Icons.Default.NotificationsActive)
        RingtoneRow(stringResource(R.string.ringtone_general), generalSoundName, onPick = {
            generalSoundPicker.launch(Notifier.ringtonePickerIntent(ctx, generalSound, ctx.getString(R.string.ringtone_pick_title)))
        }, icon = Icons.Default.Notifications)
        // Issue #6: the Remote phone never asked for POST_NOTIFICATIONS (only the
        // tracker setup flow did), so alerts could never appear there. Denial is
        // fine: alerts still land in the Alerts tab, just without a push.
        if (!notifOk) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.notif_perm_needed), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            LeafOutlinedButton(onClick = {
                Perms.notificationsPermission()?.let { notifPerm.launch(it) } ?: run { notifOk = true }
            }) { Text(stringResource(R.string.grant)) }
        }
    }
}

// ---- phone role ----
@Composable
fun RolePanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    val isTracker = prefs.isTracker
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinWrong by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var resetPassword by remember { mutableStateOf("") }
    var resetWrong by remember { mutableStateOf(false) }
    var resetBusy by remember { mutableStateOf(false) }
    ExpandablePanel(Icons.Default.Person, stringResource(R.string.set_role)) {
        Text(
            stringResource(
                R.string.role_current,
                stringResource(
                    if (isTracker) R.string.role_tracker
                    else R.string.role_remote
                ),
            ),
            color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        LeafOutlinedButton(onClick = { pinInput = ""; pinWrong = false; showPinDialog = true }) {
            Text(stringResource(R.string.role_change))
        }
        Spacer(Modifier.height(8.dp))
        LeafTextButton(onClick = { resetPassword = ""; resetWrong = false; showResetDialog = true }) {
            Text(stringResource(R.string.pin_forgot))
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            containerColor = Palette.Surface,
            title = { Text(stringResource(R.string.role_change_title)) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
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
                LeafTextButton(onClick = {
                    if (vm.clearRoleAfterPin(pinInput)) showPinDialog = false
                    else pinWrong = true
                }) { Text(stringResource(R.string.continue_label)) }
            },
            dismissButton = {
                LeafTextButton(onClick = { showPinDialog = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    // PIN recovery (v1.2.20): re-authenticate with the account password instead
    // of clearing app data (which also signed the owner out). On success the
    // role picker shows again and asks for a new role and PIN.
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { if (!resetBusy) showResetDialog = false },
            containerColor = Palette.Surface,
            title = { Text(stringResource(R.string.pin_reset_title)) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.pin_reset_hint), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = resetPassword,
                        onValueChange = { resetPassword = it; resetWrong = false },
                        singleLine = true, label = { Text(stringResource(R.string.password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (resetWrong) Text(stringResource(R.string.pin_reset_wrong), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                LeafTextButton(
                    enabled = !resetBusy && resetPassword.isNotBlank(),
                    onClick = {
                        resetBusy = true
                        vm.resetRolePin(resetPassword) { ok ->
                            resetBusy = false
                            if (ok) showResetDialog = false else resetWrong = true
                        }
                    },
                ) { Text(stringResource(R.string.pin_reset)) }
            },
            dismissButton = {
                LeafTextButton(enabled = !resetBusy, onClick = { showResetDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

// ---- app updates (v1.2.21) ----
@Composable
fun UpdatePanel(vm: AppViewModel) {
    val ctx = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    // v1.2.32: when an update is already known (startup check or an
    // earlier tap), the button says so and tapping it downloads.
    val available = vm.updateAvailable
    ExpandablePanel(Icons.Default.Update, stringResource(R.string.update_title)) {
        Text(
            stringResource(R.string.version, BuildConfig.VERSION_NAME),
            color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        LeafOutlinedButton(
            onClick = {
                if (available != null) {
                    vm.startUpdateDownload(ctx)
                } else {
                    checking = true; result = null
                    vm.checkForUpdate { r ->
                        checking = false
                        // An available update shows the AppRoot dialog; here we
                        // only report the other two outcomes.
                        result = when (r) {
                            is com.bikeguard.app.data.UpdateCheckResult.Available -> null
                            com.bikeguard.app.data.UpdateCheckResult.UpToDate -> R.string.update_latest
                            com.bikeguard.app.data.UpdateCheckResult.Failed -> R.string.update_failed
                        }
                    }
                }
            },
            enabled = !checking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(when {
            checking -> R.string.update_checking
            available != null -> R.string.update_available_button
            else -> R.string.update_check
        })) }
        result?.let {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(it), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- account ----
@Composable
fun AccountPanel(vm: AppViewModel) {
    ExpandablePanel(Icons.Default.AccountCircle, stringResource(R.string.set_account)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentCircle(Icons.Default.Email)
            Spacer(Modifier.width(12.dp))
            Text(vm.email, color = Palette.Muted, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentCircle(Icons.Default.Smartphone)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.set_device), style = MaterialTheme.typography.titleMedium)
                Text(vm.deviceId.take(8), color = Palette.Muted)
            }
        }
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
