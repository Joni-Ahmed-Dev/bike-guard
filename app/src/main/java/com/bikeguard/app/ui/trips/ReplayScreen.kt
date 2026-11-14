package com.bikeguard.app.ui.trips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.TripDto
import com.bikeguard.app.ui.fmtClock
import com.bikeguard.app.ui.fmtDuration
import com.bikeguard.app.ui.fmtKm
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen(modifier: Modifier, vm: TripsViewModel, trip: TripDto) {
    val ctx = LocalContext.current
    val pts = vm.points
    val ready = pts.size >= 2
    val times = remember(pts) { LongArray(pts.size) { pts[it].ms } }
    val startMs = times.firstOrNull() ?: trip.startedMs
    val durationMs = ((times.lastOrNull() ?: trip.endedMs) - startMs).coerceAtLeast(1)

    var progress by remember(pts) { mutableFloatStateOf(0f) }
    var playing by remember(pts) { mutableStateOf(false) }
    var rate by remember { mutableIntStateOf(30) }
    var follow by remember { mutableStateOf(false) }

    // Advance the cursor in real time, scaled by the chosen replay rate.
    LaunchedEffect(playing, rate, pts) {
        if (!playing || !ready) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            progress += ((now - last) / 1_000_000.0 * rate / durationMs).toFloat()
            last = now
            if (progress >= 1f) { progress = 1f; playing = false; break }
        }
    }

    val nowMs = startMs + (progress * durationMs).toLong()
    val sample = remember(pts, progress) { if (ready) sampleAt(pts, times, nowMs) else null }
    val score = remember(pts) { rideScore(pts) }

    Box(modifier.fillMaxSize()) {
        ReplayMap(Modifier.fillMaxSize(), pts, sample, follow, onUserGesture = { follow = false }, showRouteLine = ctx.bikeApp.prefs.showRouteLine)

        IconButton(
            onClick = vm::close,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)
                .background(Palette.Surface.copy(alpha = 0.92f), CircleShape),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.replay_back)) }

        if (vm.pointsLoading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.Ember)
        if (vm.pointsError) Text(stringResource(R.string.trips_error), color = Palette.Danger, modifier = Modifier.align(Alignment.Center))

        if (ready) {
            Panel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(fmtClock(nowMs), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${fmtKm(ctx, trip.distanceM)}  ·  ${fmtDuration(ctx, trip.endedMs - trip.startedMs)}",
                            color = Palette.Muted, style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    Text(
                        stringResource(R.string.unit_kmh, (sample?.speedKmh ?: 0f).toInt()),
                        style = MaterialTheme.typography.titleLarge, color = Palette.Ember,
                    )
                }
                Slider(
                    value = progress, onValueChange = { progress = it },
                    colors = SliderDefaults.colors(
                        thumbColor = Palette.Ember, activeTrackColor = Palette.Ember, inactiveTrackColor = Palette.Stroke,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = { if (progress >= 1f) progress = 0f; playing = !playing },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.Ember, contentColor = Palette.Ink),
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            stringResource(if (playing) R.string.replay_pause else R.string.replay_play),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    listOf(10, 30, 100).forEach { r ->
                        FilterChip(
                            selected = rate == r, onClick = { rate = r }, label = { Text("${r}×") },
                            modifier = Modifier.padding(end = 6.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Palette.Ember.copy(alpha = 0.2f), selectedLabelColor = Palette.Ember,
                            ),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconToggleButton(checked = follow, onCheckedChange = { follow = it }) {
                        Icon(Icons.Default.MyLocation, stringResource(R.string.recenter), tint = if (follow) Palette.Ember else Palette.Muted)
                    }
                }
                // legend
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("0", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                    Box(
                        Modifier.padding(horizontal = 8.dp).width(96.dp).height(6.dp).background(
                            Brush.horizontalGradient(listOf(Color(0xFF3DDBD9), Color(0xFFFFC857), Color(0xFFFF5A5F))),
                            RoundedCornerShape(3.dp),
                        )
                    )
                    Text("80+ km/h", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.ride_score, score.score),
                    style = MaterialTheme.typography.titleMedium, color = Palette.Ember,
                )
                Text(
                    stringResource(R.string.ride_score_hint, score.harshBrakes, score.overSpeedPct),
                    color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
