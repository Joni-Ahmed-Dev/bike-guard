package com.bikeguard.app.ui.trips

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
                    FilterChip(
                        selected = d.id == vm.selectedId, onClick = { d.id?.let(vm::select) }, label = { Text(d.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Palette.Ember, selectedLabelColor = Palette.Ink,
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
                items(trips, key = { it.startedAt }) { t -> TripCard(ctx, t) { vm.open(t) } }
            }
        }
    }
}

@Composable
private fun TripCard(ctx: android.content.Context, trip: TripDto, onClick: () -> Unit) {
    Panel(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(fmtDate(trip.startedMs), style = MaterialTheme.typography.titleMedium)
                Text("${fmtTime(trip.startedMs)} – ${fmtTime(trip.endedMs)}", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            }
            Text(fmtKm(ctx, trip.distanceM), style = MaterialTheme.typography.titleLarge, color = Palette.Ember)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val style = MaterialTheme.typography.labelMedium
            Text(fmtDuration(ctx, trip.endedMs - trip.startedMs), color = Palette.Muted, style = style)
            Text(stringResource(R.string.trip_avg, trip.avgSpeedKmh.toInt()), color = Palette.Muted, style = style)
            Text(stringResource(R.string.trip_max, trip.maxSpeedKmh.toInt()), color = Palette.Muted, style = style)
        }
    }
}

@Composable
private fun TripSkeleton() {
    val a by rememberInfiniteTransition(label = "sk")
        .animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxWidth().height(96.dp).alpha(a).background(Palette.Card, RoundedCornerShape(22.dp)))
}
