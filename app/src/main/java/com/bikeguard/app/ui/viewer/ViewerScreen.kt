package com.bikeguard.app.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

private val PEEK = 168.dp

// Issue #3: the sheet's expanded height must be a fixed fraction of the screen, NOT the
// content height. BottomSheetScaffold derives its Expanded anchor from the measured sheet
// height (screen height minus content height), so a content-sized sheet could only ever
// cover ~60% of the screen and its bottom rows sat below the visible area — that is why
// v1.2.0's "fully expands" claim was false. A fixed fraction makes the Expanded anchor
// stable from the first layout, independent of content and of the expanded flag below.
private const val SHEET_EXPANDED_FRACTION = 0.92f

private enum class Status(val label: Int) {
    LIVE(R.string.status_live),
    DELAYED(R.string.status_delayed),
    OFFLINE(R.string.status_offline),
}

/** Resolved at composition time (v1.2.20): the old enum stored Palette colors at class-load time, so a theme flip after the process started kept the dark colors. Delayed uses Warn (peach, v1.2.24): Ember is mint now, so it must never mean "delayed" again. */
private fun statusColor(status: Status): Color = when (status) {
    Status.LIVE -> Palette.Signal
    Status.DELAYED -> Palette.Warn
    Status.OFFLINE -> Palette.Muted
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(modifier: Modifier = Modifier, vm: ViewerViewModel = viewModel()) {
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
        containerColor = Palette.Ink,
        sheetContainerColor = Palette.Surface,
        sheetContentColor = Palette.Text,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        sheetDragHandle = { BottomSheetDefaults.DragHandle(color = Palette.Muted) },
        sheetContent = {
            SheetContent(
                ctx = ctx, now = now, latest = latest, status = status, deviceName = deviceName,
                loading = !vm.loaded && vm.selectedId != null, noDevice = vm.devicesLoaded && vm.devices.isEmpty(),
                vm = vm, expanded = sheetExpanded,
                onToggleSheet = {
                    // Read targetValue, not the settled currentValue: if the sheet is still
                    // animating toward Expanded, a second tap must collapse it instead of
                    // re-issuing expand() against a stale value.
                    sheetScope.launch {
                        if (scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded) scaffoldState.bottomSheetState.partialExpand()
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
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Palette.Surface.copy(alpha = 0.9f), labelColor = Palette.Text,
                                    selectedContainerColor = Palette.Ember, selectedLabelColor = Palette.Text,
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
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(50),
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

            // Recenter: a full-size FAB (56dp), not the small 40dp one — the map's
            // only control must meet the 48dp touch target (v1.2.20).
            FloatingActionButton(
                onClick = { follow = true },
                containerColor = Palette.Surface, contentColor = if (follow) Palette.Leaf else Palette.Muted,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = PEEK + 16.dp),
            ) { Icon(Icons.Default.MyLocation, contentDescription = stringResource(R.string.recenter)) }
        }
    }
}

@Composable
private fun SheetContent(
    ctx: Context, now: Long, latest: LocationDto?, status: Status, deviceName: String, loading: Boolean, noDevice: Boolean,
    vm: ViewerViewModel, expanded: Boolean = false, onToggleSheet: () -> Unit = {},
) {
    // Issue #2: while the sheet is only peeking, its content must NOT be scrollable — a
    // scrollable sheet swallows the drag and the sheet gets stuck halfway. Scrolling turns
    // on only once fully expanded; before that every drag goes to the sheet itself.
    // Issue #3: the height is a FIXED screen fraction, applied in both states. The old
    // code let the height follow the content, so expand() could only lift the sheet to
    // the top of its content (~60% of the screen) and any content taller than that was
    // unreachable. Now the Expanded anchor is near the top of the screen from the first
    // layout, and the content scrolls inside that fixed viewport when expanded.
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxWidth()
            .fillMaxHeight(SHEET_EXPANDED_FRACTION)
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
                // Status pill, same as Dashboard (v1.2.23): pale pill, colored dot, dark text.
                Row(Modifier.fillMaxWidth().clickable(onClick = onToggleSheet), verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (Palette.isLight) Color(0xFFF1F4F0) else Color(0xFF232B3A),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(statusColor(status), CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(status.label), color = Palette.Text, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(((latest.speed ?: 0f) * 3.6f).toInt().toString(), style = MaterialTheme.typography.displayLarge)
                    Text(" km/h", color = Palette.Muted, modifier = Modifier.padding(bottom = 14.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Mini(stringResource(R.string.stat_battery), latest.battery?.let { "$it%" } ?: stringResource(R.string.none), Icons.Default.BatteryFull, Palette.Leaf, Modifier.weight(1f), charging = latest.isCharging == true)
                    Mini(stringResource(R.string.stat_updated), agoText(ctx, latest.recordedAtMs, now), Icons.Default.Schedule, Palette.Text, Modifier.weight(1f))
                    Mini(stringResource(R.string.stat_accuracy), latest.accuracy?.let { stringResource(R.string.unit_m, it.toInt()) } ?: stringResource(R.string.none), Icons.Default.MyLocation, Palette.Leaf, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { navigate(ctx, latest.lat, latest.lng) },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(Icons.Default.Navigation, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.navigate), fontWeight = FontWeight.SemiBold)
                }
                // Odometer + last seen row (screenshot 2): value parts bold, separated by a
                // thin divider. The service line lives on the Dashboard; it is not shown here.
                val odo = vm.selectedDevice?.odometerKm ?: 0.0
                val lastSeenMs = latest.recordedAtMs
                if (odo > 0 || lastSeenMs > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (odo > 0) {
                            Icon(Icons.Default.Speed, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(boldFromFirstDigit(stringResource(R.string.odo_line, odo.toInt())), style = MaterialTheme.typography.bodyMedium)
                        }
                        if (odo > 0 && lastSeenMs > 0) {
                            Spacer(Modifier.width(12.dp))
                            VerticalDivider(Modifier.height(20.dp), color = Palette.Stroke)
                            Spacer(Modifier.width(12.dp))
                        }
                        if (lastSeenMs > 0) {
                            Icon(Icons.Default.DateRange, null, tint = Palette.Text, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                boldFromFirstDigit(stringResource(R.string.ctl_last_seen, agoText(ctx, lastSeenMs, now))),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                ViewerControls(vm)
            }
        }
    }
}

@Composable
private fun Mini(label: String, value: String, icon: ImageVector, iconTint: Color, modifier: Modifier = Modifier, charging: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Palette.Card,
        border = BorderStroke(1.dp, Palette.Stroke),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (charging) Icons.Default.BatteryChargingFull else icon, null,
                tint = if (charging) Palette.Signal else iconTint, modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
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

/** Bolds the value part of a label line ("Odometer 1 km" -> "1 km" bold), starting at the first digit. Char.isDigit is Unicode-aware, so Bengali digits work too. */
private fun boldFromFirstDigit(text: String): AnnotatedString {
    val idx = text.indexOfFirst { it.isDigit() }
    if (idx < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(idx)) }
    }
}
