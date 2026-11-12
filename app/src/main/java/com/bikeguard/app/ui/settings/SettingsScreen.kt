package com.bikeguard.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bikeguard.app.BuildConfig
import com.bikeguard.app.R
import com.bikeguard.app.data.MODE_ALWAYS
import com.bikeguard.app.data.MODE_MANUAL
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.openBatterySettings
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val segColors = SegmentedButtonDefaults.colors(
        activeContainerColor = Palette.Ember.copy(alpha = 0.18f), activeContentColor = Palette.Ember,
        activeBorderColor = Palette.Ember, inactiveContainerColor = Color.Transparent,
        inactiveContentColor = Palette.Muted, inactiveBorderColor = Palette.Stroke,
    )

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.set_mode), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            val modes = listOf(MODE_MANUAL to R.string.mode_manual, MODE_ALWAYS to R.string.mode_always)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = vm.mode == value, onClick = { vm.updateMode(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size), colors = segColors,
                    ) { Text(stringResource(label)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.mode_hint), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        }

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
        }

        Panel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.battery_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.battery_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { openBatterySettings(ctx) }) { Text(stringResource(R.string.open_settings)) }
        }

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
