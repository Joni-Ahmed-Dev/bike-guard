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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.MODE_MANUAL
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.openBatterySettings
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

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
    var autoArm by remember { mutableStateOf(prefs.autoArm) }
    var siren by remember { mutableStateOf(prefs.sirenOnAlert) }
    var crash by remember { mutableStateOf(prefs.crashDetection) }
    var sms by remember { mutableStateOf(prefs.sosSms) }
    var number by remember { mutableStateOf(prefs.emergencyNumber) }
    var limit by remember { mutableIntStateOf(prefs.speedLimitKmh) }
    var serviceEvery by remember { mutableIntStateOf(prefs.serviceIntervalKm) }
    var odo by remember { mutableIntStateOf(prefs.odometerKm.toInt()) }
    var next by remember { mutableIntStateOf(prefs.nextServiceKm.toInt()) }
    var lock by remember { mutableStateOf(prefs.appLock) }
    var autoDenied by remember { mutableStateOf(false) }
    var smsDenied by remember { mutableStateOf(false) }
    var lockMsg by remember { mutableStateOf(false) }

    val activityPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        autoDenied = !ok
        if (ok) vm.updateMode(MODE_AUTO)
    }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        smsDenied = !ok
        if (ok) { sms = true; prefs.sosSms = true }
    }
    fun pickMode(m: String) {
        if (m == MODE_AUTO && !AutoMode.hasPermission(ctx)) activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        else { autoDenied = false; vm.updateMode(m) }
    }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

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
            SwitchRow(stringResource(R.string.guard_auto), null, autoArm) { autoArm = it; prefs.autoArm = it }
            SwitchRow(stringResource(R.string.guard_siren), null, siren) { siren = it; prefs.sirenOnAlert = it }
        }

        // ---- safety ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_safety), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.safety_crash), null, crash) { crash = it; prefs.crashDetection = it }
            SwitchRow(stringResource(R.string.safety_sms), stringResource(R.string.safety_sms_hint), sms) { on ->
                if (!on) { sms = false; prefs.sosSms = false }
                else smsPerm.launch(Manifest.permission.SEND_SMS)
            }
            if (smsDenied) Text(stringResource(R.string.sms_perm_needed), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = number, onValueChange = { number = it; prefs.emergencyNumber = it.trim() }, singleLine = true,
                label = { Text(stringResource(R.string.safety_number)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.limit_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ChipRow(
                listOf(0, 60, 80, 100, 120), limit,
                { if (it == 0) ctx.getString(R.string.limit_off) else ctx.getString(R.string.limit_kmh, it) },
            ) { limit = it; prefs.speedLimitKmh = it; prefs.stateDirty = true }
        }

        // ---- service reminder ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_service), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.service_odo, odo), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            if (next > 0) Text(stringResource(R.string.service_next, next), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            ChipRow(
                listOf(0, 1000, 2000, 3000, 5000), serviceEvery,
                { if (it == 0) ctx.getString(R.string.service_off) else ctx.getString(R.string.service_every, it) },
            ) {
                serviceEvery = it; prefs.scheduleService(it)
                odo = prefs.odometerKm.toInt(); next = prefs.nextServiceKm.toInt()
            }
            if (serviceEvery > 0) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { prefs.markServiced(); next = prefs.nextServiceKm.toInt() }) {
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

        // ---- battery ----
        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.battery_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { openBatterySettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
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
