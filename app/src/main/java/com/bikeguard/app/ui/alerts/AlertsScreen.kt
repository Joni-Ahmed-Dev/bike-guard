package com.bikeguard.app.ui.alerts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bikeguard.app.R
import com.bikeguard.app.data.EventDto
import com.bikeguard.app.data.eventTitle
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.launch

@Composable
fun AlertsScreen(modifier: Modifier, vm: AlertsViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val now = rememberNow()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { if (vm.refresh()) vm.markSeen() }
    LaunchedEffect(Unit) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.live() } }

    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text(
            stringResource(R.string.tab_alerts), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        if (vm.error) {
            // The load failed: say so and offer a retry, instead of pretending all is quiet.
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alerts_error_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.alerts_error_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { scope.launch { vm.refresh() } }) { Text(stringResource(R.string.retry)) }
            }
        } else if (vm.loaded && vm.events.isEmpty()) {
            Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.alerts_empty_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.alerts_empty_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(vm.events, key = { it.id ?: it.createdAt.hashCode().toLong() }) { e ->
                    AlertRow(ctx, e, vm.deviceNames[e.deviceId].orEmpty(), now)
                }
            }
        }
    }
}

@Composable
private fun AlertRow(ctx: Context, e: EventDto, deviceName: String, now: Long) {
    val color = when (e.severity) { "critical" -> Palette.Danger; "warning" -> Palette.Ember; else -> Palette.Signal }
    val hasPlace = e.lat != null && e.lng != null
    Panel(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(enabled = hasPlace) {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${e.lat},${e.lng}(${Uri.encode(deviceName.ifBlank { "BikeGuard" })})"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(iconFor(e.type), null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(eventTitle(ctx, e.type, e.data?.toString()), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(deviceName, agoText(ctx, e.createdMs, now)).filter { it.isNotBlank() }.joinToString(" · "),
                    color = Palette.Muted, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private fun iconFor(type: String): ImageVector = when (type) {
    "motion" -> Icons.Default.Vibration
    "moved", "geofence_exit", "geofence_enter" -> Icons.Default.Place
    "tamper_gps_off" -> Icons.Default.GpsOff
    "tamper_airplane" -> Icons.Default.AirplanemodeActive
    "charger_unplugged" -> Icons.Default.PowerOff
    "battery_low" -> Icons.Default.BatteryAlert
    "device_rebooted", "tracker_restarted" -> Icons.Default.RestartAlt
    "speed_limit" -> Icons.Default.Speed
    "crash", "sos", "device_stale" -> Icons.Default.Warning
    "service_due" -> Icons.Default.Build
    else -> Icons.Default.Notifications
}
