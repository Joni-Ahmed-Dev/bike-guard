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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.data.isoToMs
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.fmtHourMin
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Palette
import java.time.Instant

/** Remote control + tools for the selected bike, shown inside the Viewer's bottom sheet. */
@Composable
fun ViewerControls(vm: ViewerViewModel) {
    var showShare by remember { mutableStateOf(false) }
    var showGeo by remember { mutableStateOf(false) }
    var showLimit by remember { mutableStateOf(false) }
    var showSos by remember { mutableStateOf(false) }
    val armed = vm.selectedDevice?.armed == true
    val ctx = LocalContext.current
    val now = rememberNow()
    // Last seen before any command (v1.2.20): the owner must know whether the
    // bike's position is fresh or hours old before sending a command that acts
    // on it. Falls back to the device row's last_seen when no fix is loaded.
    val lastSeenMs = vm.latest?.recordedAtMs
        ?: vm.selectedDevice?.lastSeen?.let { isoToMs(it) } ?: 0L
    val lastSeenText = if (lastSeenMs > 0) stringResource(R.string.ctl_last_seen, agoText(ctx, lastSeenMs, now))
    else stringResource(R.string.ctl_last_seen_none)

    // Last seen now lives in the sheet's odometer row (screenshot 2, v1.2.24); the
    // SOS dialog below still shows it, so the variable stays.
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
                Modifier.weight(1f), tint = if (armed) Palette.Signal else Palette.Leaf,
            ) { vm.send(if (armed) "disarm" else "arm") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton(Icons.Default.Share, R.string.ctl_share, Modifier.weight(1f)) { showShare = true }
            ControlButton(Icons.Default.Place, R.string.ctl_geofence, Modifier.weight(1f)) { showGeo = true }
            ControlButton(Icons.Default.Speed, R.string.ctl_limit, Modifier.weight(1f)) { showLimit = true }
            ControlButton(Icons.Default.Build, R.string.ctl_service, Modifier.weight(1f)) { vm.send("mark_serviced") }
        }
        // Remote SOS (v1.2.19): the hold button used to exist only on the
        // Tracker tab. This sends an "sos" command the bike executes at once
        // (same immediate SOS as the hold). A confirm dialog, not a bare tap:
        // a stray tap must never text the emergency contact. It sits below the
        // 8 action buttons (screenshot 2 shows only those 8); reachable by
        // scrolling once the sheet is fully expanded.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ControlButton(Icons.Default.Warning, R.string.ctl_sos, Modifier.weight(1f), tint = Palette.Danger) { showSos = true }
        }
        vm.commandNote?.let { Text(stringResource(it), color = Palette.Muted, style = MaterialTheme.typography.labelMedium) }
    }

    if (showSos) AlertDialog(
        onDismissRequest = { showSos = false },
        containerColor = Palette.Surface,
        title = { Text(stringResource(R.string.sos_remote_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.sos_remote_text), color = Palette.Muted)
                // The SOS goes to the bike's last known position: say how old it is.
                Text(lastSeenText, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = {
            Button(onClick = { showSos = false; vm.send("sos") }) { Text(stringResource(R.string.sos_remote_send)) }
        },
        dismissButton = {
            TextButton(onClick = { showSos = false }) { Text(stringResource(R.string.cancel)) }
        },
    )

    if (showShare) ShareDialog(vm) { showShare = false }
    if (showGeo) GeofenceDialog(vm) { showGeo = false }
    if (showLimit) LimitDialog(vm) { showLimit = false }
}

@Composable
private fun ControlButton(icon: ImageVector, label: Int, modifier: Modifier, tint: Color = Palette.Leaf, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape, color = Palette.Card, border = BorderStroke(1.dp, Palette.Stroke),
        // Merged descendants + Button role (v1.2.20): TalkBack used to land on the
        // bare Surface with no label; now it announces the button's label.
        modifier = modifier.clip(shape).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 4.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ShareDialog(vm: ViewerViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadShareLinks() }
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
                    // One row per active link, each revocable alone: revoking one must
                    // never kill the others (review item #16).
                    if (vm.shareLinks.isNotEmpty()) {
                        Text(stringResource(R.string.share_active), style = MaterialTheme.typography.titleSmall)
                        vm.shareLinks.forEach { link ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.share_expires, shareExpiryText(link.expiresAt)),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { vm.revokeShareLink(link.token) }) {
                                    Text(stringResource(R.string.share_revoke), color = Palette.Danger)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close)) } },
    )
}

/** "HH:mm" expiry for a share link, or the raw value if it cannot be parsed. */
private fun shareExpiryText(expiresAt: String): String =
    runCatching { fmtHourMin(Instant.parse(expiresAt).toEpochMilli()) }.getOrNull() ?: expiresAt

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
                        modifier = Modifier.height(48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Ember.copy(alpha = 0.25f), selectedLabelColor = Palette.Leaf,
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
                            modifier = Modifier.height(48.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.Ember.copy(alpha = 0.25f), selectedLabelColor = Palette.Leaf,
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
