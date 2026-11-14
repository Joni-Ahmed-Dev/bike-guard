package com.bikeguard.app.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.data.LocationDto
import com.bikeguard.app.ui.agoText
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

private val PEEK = 168.dp

private enum class Status(val label: Int, val color: Color) {
    LIVE(R.string.status_live, Palette.Signal),
    DELAYED(R.string.status_delayed, Palette.Ember),
    OFFLINE(R.string.status_offline, Palette.Muted),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(modifier: Modifier = Modifier, vm: ViewerViewModel = viewModel(), readOnly: Boolean = false) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.pollLoop() }
    }
    LaunchedEffect(Unit) { vm.loadGeofences() }

    val now = rememberNow()
    val latest = vm.latest
    // Marker position after the viewer-side jitter hold (issue #4), not the raw fix.
    val position = vm.displayPosition
    val trail = remember(vm.trail) { vm.trail.map { LatLng(it.lat, it.lng) } }
    var follow by remember { mutableStateOf(true) }
    var offlineMsg by remember { mutableStateOf<Int?>(null) }
    // Live connectivity (issue #5): the offline-map chip/message only exist while offline.
    val online = rememberIsOnline()

    // Sheet state, hoisted so the header can toggle expand/collapse (issue #2).
    val scaffoldState = rememberBottomSheetScaffoldState()
    val sheetExpanded = scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded
    val sheetScope = rememberCoroutineScope()

    val age = latest?.let { now - it.recordedAtMs }
    val status = when {
        age == null -> Status.OFFLINE
        age < 240_000 -> Status.LIVE
        age < 900_000 -> Status.DELAYED
        else -> Status.OFFLINE
    }
    val deviceName = vm.devices.firstOrNull { it.id == vm.selectedId }?.name.orEmpty()

    BottomSheetScaffold(
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = PEEK,
        sheetGesturesEnabled = true,
        containerColor = Palette.Ink,
        sheetContainerColor = Palette.Surface,
        sheetContentColor = Palette.Text,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetDragHandle = { BottomSheetDefaults.DragHandle(color = Palette.Muted) },
        sheetContent = {
            SheetContent(
                ctx = ctx, now = now, latest = latest, status = status, deviceName = deviceName,
                loading = !vm.loaded && vm.selectedId != null, noDevice = vm.devicesLoaded && vm.devices.isEmpty(),
                vm = vm, readOnly = readOnly, expanded = sheetExpanded,
                onToggleSheet = {
                    sheetScope.launch {
                        if (sheetExpanded) scaffoldState.bottomSheetState.partialExpand()
                        else scaffoldState.bottomSheetState.expand()
                    }
                },
            )
        },
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            BikeMap(
                modifier = Modifier.fillMaxSize(),
                position = position,
                bearing = latest?.bearing ?: 0f,
                moving = (latest?.speed ?: 0f) > 1f && latest?.bearing != null,
                trail = trail,
                follow = follow,
                attributionBottomDp = PEEK.value.toInt() + 8,
                onUserGesture = { follow = false },
                geofences = vm.geofences,
                showRouteLine = vm.showRouteLine,
                online = online,
            )

            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.devices.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(vm.devices, key = { it.id.orEmpty() }) { d ->
                            FilterChip(
                                selected = d.id == vm.selectedId,
                                onClick = { d.id?.let(vm::select) },
                                label = { Text(d.name) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Palette.Surface.copy(alpha = 0.9f), labelColor = Palette.Text,
                                    selectedContainerColor = Palette.Ember, selectedLabelColor = Palette.Ink,
                                ),
                            )
                        }
                    }
                }
                if (vm.connectionIssue) {
                    Surface(shape = RoundedCornerShape(50), color = Palette.Danger.copy(alpha = 0.92f)) {
                        Text(
                            stringResource(R.string.connection_issue), color = Color.White,
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                // Offline-map download (issue #5): the chip and its message exist ONLY while
                // offline. While online the map already uses live tiles, so showing them was
                // wrong; the message also clears itself instead of sticking around forever.
                if (latest != null && !online) {
                    FilterChip(
                        selected = false,
                        onClick = { OfflineMap.downloadAround(ctx, latest.lat, latest.lng) { offlineMsg = it } },
                        label = { Text(stringResource(R.string.offline_map)) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Palette.Surface.copy(alpha = 0.9f), labelColor = Palette.Text,
                        ),
                    )
                    offlineMsg?.let {
                        LaunchedEffect(it) { delay(6_000); offlineMsg = null }
                        Text(
                            stringResource(it), color = Palette.Text,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(Palette.Surface.copy(alpha = 0.9f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            SmallFloatingActionButton(
                onClick = { follow = true },
                containerColor = Palette.Surface, contentColor = if (follow) Palette.Ember else Palette.Muted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = PEEK + 16.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.recenter)) }
        }
    }
}

@Composable
private fun SheetContent(
    ctx: Context, now: Long, latest: LocationDto?, status: Status, deviceName: String, loading: Boolean, noDevice: Boolean,
    vm: ViewerViewModel, readOnly: Boolean = false, expanded: Boolean = false, onToggleSheet: () -> Unit = {},
) {
    // Issue #2: while the sheet is only peeking, its content must NOT be scrollable — a
    // scrollable sheet swallows the drag and the sheet gets stuck halfway. Scrolling turns
    // on only once fully expanded; before that every drag goes to the sheet itself.
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxWidth()
            .then(if (expanded) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = 20.dp).padding(bottom = 28.dp)
    ) {
        when {
            noDevice -> {
                Text(stringResource(R.string.viewer_no_device_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.viewer_no_device_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            latest == null -> {
                if (loading) {
                    Skeleton(Modifier.fillMaxWidth(0.5f).height(24.dp)); Spacer(Modifier.height(16.dp))
                    Skeleton(Modifier.fillMaxWidth(0.35f).height(56.dp)); Spacer(Modifier.height(16.dp))
                    Skeleton(Modifier.fillMaxWidth().height(60.dp))
                } else {
                    Text(stringResource(R.string.viewer_waiting), color = Palette.Muted)
                }
            }
            else -> {
                // Tapping the header toggles the sheet fully open / back to peek (issue #2),
                // so the whole sheet is reachable even if a drag is swallowed.
                Row(Modifier.fillMaxWidth().clickable(onClick = onToggleSheet), verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Box(Modifier.size(8.dp).background(status.color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(status.label), color = status.color, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(((latest.speed ?: 0f) * 3.6f).toInt().toString(), style = MaterialTheme.typography.displayLarge)
                    Text(" km/h", color = Palette.Muted, modifier = Modifier.padding(bottom = 14.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mini(stringResource(R.string.stat_battery), latest.battery?.let { "$it%" } ?: stringResource(R.string.none), Modifier.weight(1f), charging = latest.isCharging == true)
                    Mini(stringResource(R.string.stat_updated), agoText(ctx, latest.recordedAtMs, now), Modifier.weight(1f))
                    Mini(stringResource(R.string.stat_accuracy), latest.accuracy?.let { stringResource(R.string.unit_m, it.toInt()) } ?: stringResource(R.string.none), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { navigate(ctx, latest.lat, latest.lng) },
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.Navigation, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.navigate), fontWeight = FontWeight.SemiBold)
                }
                vm.selectedDevice?.let { d ->
                    val odo = d.odometerKm ?: 0.0
                    if (odo > 0) {
                        val svc = d.nextServiceKm?.takeIf { it > 0 }?.let { n ->
                            val left = (n - odo).toInt()
                            if (left >= 0) stringResource(R.string.service_in, left) else stringResource(R.string.service_overdue)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            listOfNotNull(stringResource(R.string.odo_line, odo.toInt()), svc).joinToString("  ·  "),
                            color = Palette.Muted, style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (!readOnly) ViewerControls(vm)
                else Text(stringResource(R.string.invite_mode), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String, modifier: Modifier = Modifier, charging: Boolean = false) {
    Panel(modifier) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (charging) Icon(Icons.Default.BatteryChargingFull, null, tint = Palette.Signal, modifier = Modifier.size(16.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun Skeleton(modifier: Modifier) {
    val a by rememberInfiniteTransition(label = "sk")
        .animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(modifier.alpha(a).background(Palette.Card, RoundedCornerShape(12.dp)))
}

private fun navigate(ctx: Context, lat: Double, lng: Double) {
    val uri = Uri.parse("geo:0,0?q=$lat,$lng(${Uri.encode("BikeGuard")})")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
