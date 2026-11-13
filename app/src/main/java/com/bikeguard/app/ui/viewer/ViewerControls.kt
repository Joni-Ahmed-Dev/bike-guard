package com.bikeguard.app.ui.viewer

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.ui.theme.Palette

/** Remote control + tools for the selected bike, shown inside the Viewer's bottom sheet. */
@Composable
fun ViewerControls(vm: ViewerViewModel) {
    var showShare by remember { mutableStateOf(false) }
    var showGeo by remember { mutableStateOf(false) }
    var showLimit by remember { mutableStateOf(false) }
    val armed = vm.selectedDevice?.armed == true

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton(Icons.Default.MyLocation, R.string.ctl_locate, Modifier.weight(1f)) { vm.send("locate") }
            ControlButton(Icons.Default.Bolt, R.string.ctl_live, Modifier.weight(1f)) { vm.send("live") }
            ControlButton(
                Icons.Default.NotificationsActive, if (vm.sirenOn) R.string.ctl_siren_stop else R.string.ctl_siren,
                Modifier.weight(1f), tint = Palette.Danger,
            ) { vm.send(if (vm.sirenOn) "siren_off" else "siren_on") }
            ControlButton(
                if (armed) Icons.Default.LockOpen else Icons.Default.Lock, if (armed) R.string.ctl_disarm else R.string.ctl_arm,
                Modifier.weight(1f), tint = if (armed) Palette.Signal else Palette.Ember,
            ) { vm.send(if (armed) "disarm" else "arm") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton(Icons.Default.Share, R.string.ctl_share, Modifier.weight(1f)) { showShare = true }
            ControlButton(Icons.Default.Place, R.string.ctl_geofence, Modifier.weight(1f)) { showGeo = true }
            ControlButton(Icons.Default.Speed, R.string.ctl_limit, Modifier.weight(1f)) { showLimit = true }
            ControlButton(Icons.Default.Build, R.string.ctl_service, Modifier.weight(1f)) { vm.send("mark_serviced") }
        }
        vm.commandNote?.let { Text(stringResource(it), color = Palette.Muted, style = MaterialTheme.typography.labelMedium) }
    }

    if (showShare) ShareDialog(vm) { showShare = false }
    if (showGeo) GeofenceDialog(vm) { showGeo = false }
    if (showLimit) LimitDialog(vm) { showLimit = false }
}

@Composable
private fun ControlButton(icon: ImageVector, label: Int, modifier: Modifier, tint: Color = Palette.Ember, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape, color = Palette.Card, border = BorderStroke(1.dp, Palette.Stroke),
        modifier = modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ShareDialog(vm: ViewerViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Surface,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            if (!vm.canShare) {
                Text(stringResource(R.string.share_no_base), color = Palette.Muted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 6, 24).forEach { h ->
                            OutlinedButton(onClick = {
                                vm.createShare(h) { url ->
                                    if (url == null) {
                                        Toast.makeText(ctx, R.string.share_failed, Toast.LENGTH_SHORT).show()
                                    } else {
                                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                            .putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.share_message, url))
                                        ctx.startActivity(Intent.createChooser(send, null))
                                    }
                                    onClose()
                                }
                            }) { Text(stringResource(R.string.share_h, h)) }
                        }
                    }
                    TextButton(onClick = { vm.stopSharing(); onClose() }) {
                        Text(stringResource(R.string.share_stop), color = Palette.Danger)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun LimitDialog(vm: ViewerViewModel, onClose: () -> Unit) {
    val current = vm.selectedDevice?.speedLimitKmh ?: 0
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Surface,
        title = { Text(stringResource(R.string.limit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 60, 80, 100, 120).forEach { v ->
                    FilterChip(
                        selected = v == current,
                        onClick = { vm.send("set_speed_limit", v.toString()); onClose() },
                        label = { Text(if (v == 0) stringResource(R.string.limit_off) else stringResource(R.string.limit_kmh, v)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Ember.copy(alpha = 0.2f), selectedLabelColor = Palette.Ember,
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun GeofenceDialog(vm: ViewerViewModel, onClose: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGeofences() }
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableIntStateOf(200) }
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.Surface,
        title = { Text(stringResource(R.string.geo_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.geofences.isEmpty()) Text(stringResource(R.string.geo_empty), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                vm.geofences.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.geo_radius, g.radiusM), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { g.id?.let(vm::deleteGeofence) }) { Icon(Icons.Default.Delete, null, tint = Palette.Danger) }
                    }
                }
                HorizontalDivider(color = Palette.Stroke)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.geo_name)) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100, 200, 500).forEach { r ->
                        FilterChip(
                            selected = radius == r, onClick = { radius = r }, label = { Text(stringResource(R.string.geo_radius, r)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.Ember.copy(alpha = 0.2f), selectedLabelColor = Palette.Ember,
                            ),
                        )
                    }
                }
                Button(
                    onClick = { if (vm.addGeofence(name, radius)) name = "" },
                    enabled = vm.latest != null && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.geo_add_here)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}
