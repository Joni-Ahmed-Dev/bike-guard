package com.bikeguard.app.ui.trips

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.data.TripDto
import com.bikeguard.app.ui.fmtDate
import com.bikeguard.app.ui.fmtDuration
import com.bikeguard.app.ui.fmtKm
import com.bikeguard.app.ui.fmtTime
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

@Composable
fun TripsScreen(modifier: Modifier = Modifier, vm: TripsViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    val open = vm.openTrip
    if (open != null) {
        BackHandler { vm.close() }
        ReplayScreen(modifier, vm, open)
    } else {
        TripList(modifier, vm)
    }
}

@Composable
private fun TripList(modifier: Modifier, vm: TripsViewModel) {
    val ctx = LocalContext.current
    val trips = vm.trips
    Column(modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text(
            stringResource(R.string.tab_trips), style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
        )
        if (vm.devices.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                items(vm.devices, key = { it.id.orEmpty() }) { d ->
                    // Same chip pattern as the Viewer round: mint when selected,
                    // white with a thin border otherwise, dark text both ways.
                    FilterChip(
                        selected = d.id == vm.selectedId, onClick = { d.id?.let(vm::select) }, label = { Text(d.name) },
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
        when {
            vm.loading && trips == null -> repeat(3) { TripSkeleton(); Spacer(Modifier.height(12.dp)) }
            vm.error && trips == null -> {
                Text(stringResource(R.string.trips_error), color = Palette.Danger)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = vm::load) { Text(stringResource(R.string.retry)) }
            }
            trips.isNullOrEmpty() -> Panel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.trips_empty_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.trips_empty_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    vm.weeklySummary?.let { w ->
                        Panel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AccentCircle(Icons.Default.DateRange)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.weekly_title),
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        "${stringResource(R.string.weekly_trips, w.trips)}  ·  " +
                                            ctx.getString(R.string.unit_km, w.km) + "  ·  " +
                                            fmtDuration(ctx, w.minutes * 60_000L) + "  ·  " +
                                            stringResource(R.string.trip_max, w.topSpeed.toInt()),
                                        color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { shareFile(ctx, vm.weeklyCsv(), "bikeguard-week.csv", "text/csv") },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, Palette.Leaf),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Leaf),
                                ) {
                                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.export_csv), style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        vm.exportLatestTripGpx { gpx ->
                                            if (gpx != null) shareFile(ctx, gpx, "bikeguard-trip.gpx", "application/gpx+xml")
                                            else Toast.makeText(ctx, ctx.getString(R.string.export_failed), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, Palette.Leaf),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.Leaf),
                                ) {
                                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.export_gpx), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                items(trips, key = { it.startedAt }) { t -> TripCard(ctx, t) { vm.open(t) } }
            }
        }
    }
}

@Composable
private fun TripCard(ctx: android.content.Context, trip: TripDto, onClick: () -> Unit) {
    // Merged descendants + Button role (v1.2.20): TalkBack used to land on the
    // bare Panel with no label; now it announces the trip card.
    Panel(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { role = Role.Button }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentCircle(Icons.Default.AltRoute)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(fmtDate(trip.startedMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text("${fmtTime(trip.startedMs)} – ${fmtTime(trip.endedMs)}", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            // Pale mint badge with the distance in bold Leaf, as in screenshot 5.
            Box(
                Modifier.background(Palette.Ember.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    fmtKm(ctx, trip.distanceM),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Palette.Leaf,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.Stroke)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(Icons.Default.Schedule, fmtDuration(ctx, trip.endedMs - trip.startedMs))
            VerticalDivider(Modifier.height(20.dp), color = Palette.Stroke)
            Stat(Icons.Default.Speed, stringResource(R.string.trip_avg, trip.avgSpeedKmh.toInt()))
            VerticalDivider(Modifier.height(20.dp), color = Palette.Stroke)
            Stat(Icons.Default.Speed, stringResource(R.string.trip_max, trip.maxSpeedKmh.toInt()))
        }
    }
}

/** Small icon + muted text stat, as in the bottom row of a trip card. */
@Composable
private fun Stat(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
        Text(text, color = Palette.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * Pale mint circle with a Leaf icon — matches the calendar and route circles in
 * screenshot 5. Dark keeps the Leaf convention (amber icon on translucent mint).
 */
@Composable
private fun AccentCircle(icon: ImageVector) {
    Box(
        Modifier.size(48.dp).background(Palette.Ember.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Palette.Leaf, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TripSkeleton() {
    val a by rememberInfiniteTransition(label = "sk")
        .animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxWidth().height(96.dp).alpha(a).background(Palette.Card, RoundedCornerShape(22.dp)))
}
