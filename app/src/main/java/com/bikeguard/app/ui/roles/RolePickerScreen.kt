package com.bikeguard.app.ui.roles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.ROLE_REMOTE
import com.bikeguard.app.data.ROLE_TRACKER
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

/** Shown once, right after sign-in, when this phone has no role yet. */
@Composable
fun RolePickerScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var picked by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinBad by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.role_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.role_subtitle), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))

        RoleCard(
            titleRes = R.string.role_tracker, descRes = R.string.role_tracker_desc,
            icon = Icons.Default.DirectionsBike, selected = picked == ROLE_TRACKER,
        ) { picked = ROLE_TRACKER }
        RoleCard(
            titleRes = R.string.role_remote, descRes = R.string.role_remote_desc,
            icon = Icons.Default.PhoneAndroid, selected = picked == ROLE_REMOTE,
        ) { picked = ROLE_REMOTE }

        if (picked != null) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.role_pin_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.role_pin_hint), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4); pinBad = false },
                    singleLine = true, label = { Text(stringResource(R.string.pin_label)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (pinBad) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.role_pin_wrong), color = Palette.Danger, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val ok = vm.pickRole(picked!!, pin)
                        if (!ok) pinBad = true
                        else ctx.bikeApp.prefs.remoteTargetDeviceId = ""
                    },
                    enabled = pin.length == 4,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.continue_label)) }
            }
        }
    }
}

@Composable
private fun RoleCard(
    titleRes: Int, descRes: Int, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Surface(
        shape = shape,
        color = if (selected) Palette.Ember.copy(alpha = 0.16f) else Palette.Card,
        border = BorderStroke(1.dp, if (selected) Palette.Ember else Palette.Stroke),
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Palette.Ember, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(descRes), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
