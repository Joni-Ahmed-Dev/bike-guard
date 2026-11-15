package com.bikeguard.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CarCrash
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.MODE_MANUAL
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.launch

/** Split out of SettingsScreen.kt (v1.2.20): the Tracker-role read-only sections. */

// ---- tracker role: settings are view-only ----
@Composable
fun TrackerNoticePanel() {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentCircle(Icons.Default.Info)
            Spacer(Modifier.width(12.dp))
            // Leaf, not Ember: the primary is mint in light mode and mint text
            // on white is unreadable.
            Text(stringResource(R.string.tracker_view_only), color = Palette.Leaf, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun TrackerReadOnlyPanel(vm: AppViewModel) {
    val prefs = LocalContext.current.bikeApp.prefs
    val autoArm = remember(vm.remoteSettings) { vm.remoteSettings?.autoArm ?: prefs.autoArm }
    val crash = remember(vm.remoteSettings) { vm.remoteSettings?.crashEnabled ?: prefs.crashDetection }
    val number = remember(vm.remoteSettings) { vm.remoteSettings?.sosNumber ?: prefs.emergencyNumber }
    val limit = remember(vm.remoteSettings) { vm.remoteSettings?.speedLimitKmh ?: prefs.speedLimitKmh }
    val serviceEvery = remember(vm.remoteSettings) { vm.remoteSettings?.serviceKm ?: prefs.serviceIntervalKm }
    ExpandablePanel(Icons.Default.Tune, stringResource(R.string.current_settings)) {
        ReadOnlyRow(stringResource(R.string.set_mode), stringResource(when (vm.mode) {
            MODE_MANUAL -> R.string.mode_manual
            MODE_AUTO -> R.string.mode_auto
            else -> R.string.mode_always
        }), icon = Icons.Default.Tune)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.set_interval), stringResource(R.string.interval_s, vm.intervalSec), icon = Icons.Default.Timer)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.limit_title), if (limit == 0) stringResource(R.string.limit_off) else stringResource(R.string.limit_kmh, limit), icon = Icons.Default.Speed)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.guard_parking), if (prefs.parkingArmed) stringResource(R.string.ctl_arm) else stringResource(R.string.ctl_disarm), icon = Icons.Default.LocalParking)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.guard_auto), if (autoArm) stringResource(R.string.ctl_arm) else stringResource(R.string.ctl_disarm), icon = Icons.Default.Shield)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.safety_crash), if (crash) stringResource(R.string.ctl_arm) else stringResource(R.string.ctl_disarm), icon = Icons.Default.CarCrash)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.safety_number), number.ifBlank { stringResource(R.string.none) }, icon = Icons.Default.Phone)
        SettingsDivider()
        ReadOnlyRow(stringResource(R.string.set_service), if (serviceEvery == 0) stringResource(R.string.service_off) else stringResource(R.string.service_every, serviceEvery), icon = Icons.Default.Build)
    }
}

// ---- tracker sync test: does this phone reach the server? ----
@Composable
fun TrackerSyncTestPanel() {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ExpandablePanel(Icons.Default.Sync, stringResource(R.string.sync_test)) {
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
            modifier = Modifier.fillMaxWidth().height(48.dp),
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
