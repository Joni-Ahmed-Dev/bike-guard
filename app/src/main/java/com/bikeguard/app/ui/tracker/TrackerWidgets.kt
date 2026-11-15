package com.bikeguard.app.ui.tracker

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.data.Perms
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel
import kotlinx.coroutines.delay
import java.util.Locale

/** Split out of TrackerScreen.kt (v1.2.20): the tracker's widgets, unchanged except for the accessibility fixes noted below. */

@Composable
fun StatusRing(running: Boolean, offline: Boolean, always: Boolean, idleLabel: Int, onClick: () -> Unit) {
    val color by animateColorAsState(
        when { !running -> Palette.Muted; offline -> Palette.Ember; else -> Palette.Signal }, label = "ringColor"
    )
    val inf = rememberInfiniteTransition(label = "ring")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "rot")
    val pulse by inf.animateFloat(0.97f, 1.03f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    // Merged descendants + Button role (v1.2.20): TalkBack used to land on the bare
    // clickable Box with no label; now it announces the ring's state.
    val desc = stringResource(when { always -> R.string.ring_always; running -> R.string.ring_tracking; else -> idleLabel })

    Box(
        Modifier.size(120.dp).clickable(
            enabled = !always, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick,
        ).semantics(mergeDescendants = true) { contentDescription = desc; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { if (running) { scaleX = pulse; scaleY = pulse } }) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            drawCircle(color.copy(alpha = 0.07f), radius = size.minDimension / 2)
            drawCircle(color.copy(alpha = 0.22f), radius = size.minDimension / 2 - inset, style = Stroke(stroke))
            if (running) {
                rotate(rot) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, color), center = center),
                        startAngle = 0f, sweepAngle = 280f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(when { always -> R.string.ring_always; running -> R.string.ring_tracking; else -> idleLabel }),
                color = if (running) Palette.Text else Palette.Muted, style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Live "tracking on for HH:MM:SS" timer, shown only while tracking runs. */
@Composable
fun TrackingTimer(startedAtMs: Long) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) { delay(1000); nowMs = System.currentTimeMillis() }
    }
    val elapsed = (nowMs - startedAtMs).coerceAtLeast(0)
    val hours = elapsed / 3_600_000
    val minutes = (elapsed / 60_000) % 60
    val seconds = (elapsed / 1000) % 60
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tracking_timer_label), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Text(
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds),
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun syncReasonText(reason: String): String = when {
    reason == "no internet" -> stringResource(R.string.sync_err_no_internet)
    reason == "timed out" -> stringResource(R.string.sync_err_timeout)
    reason == "signed out" -> stringResource(R.string.sync_err_signed_out)
    reason.contains("server ") -> stringResource(
        R.string.sync_err_server,
        reason.substringAfter("server ").substringBefore(":").substringBefore(" ").trim(),
    )
    else -> reason
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Panel(modifier) {
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (highlight) Palette.Ember else Palette.Text, style = MaterialTheme.typography.titleLarge)
    }
}

private enum class Step { NOTIF, LOCATION, BACKGROUND, ACTIVITY }

@Composable
fun PermissionStepCard(tick: Int, onChanged: () -> Unit, autoMode: Boolean, onSkipActivity: () -> Unit) {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    val steps = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Step.NOTIF)
        add(Step.LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Step.BACKGROUND)
        // Physical activity is only needed in Auto mode: it powers the
        // in-vehicle detection that starts tracking when the bike moves.
        if (autoMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Step.ACTIVITY)
    }
    val done = remember(tick, autoMode) {
        steps.filter { s ->
            when (s) {
                Step.NOTIF -> Perms.hasNotifications(ctx)
                Step.LOCATION -> Perms.hasFine(ctx)
                Step.BACKGROUND -> Perms.hasBackground(ctx)
                Step.ACTIVITY -> AutoMode.hasPermission(ctx)
            }
        }.toSet()
    }
    val current = steps.firstOrNull { it !in done } ?: return
    val (title, body) = when (current) {
        Step.NOTIF -> R.string.perm_notif_title to R.string.perm_notif_body
        Step.LOCATION -> R.string.perm_loc_title to R.string.perm_loc_body
        Step.BACKGROUND -> R.string.perm_bg_title to R.string.perm_bg_body
        Step.ACTIVITY -> R.string.perm_activity_title to R.string.perm_activity_body
    }

    Panel(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.perm_step, steps.indexOf(current) + 1, steps.size), color = Palette.Ember, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { done.size / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Palette.Ember, trackColor = Palette.Stroke,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                when (current) {
                    Step.NOTIF -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                    Step.LOCATION -> loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    Step.BACKGROUND -> bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    Step.ACTIVITY -> activity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            },
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.grant)) }
        // Denying activity must never trap the user on this card: Auto start just
        // stays off, tracking can still be started by tapping the button.
        if (current == Step.ACTIVITY) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSkipActivity, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.perm_skip)) }
        }
    }
}

/** Long-press on purpose: a stray tap must never send an SOS. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SosButton(onTrigger: () -> Unit) {
    var sent by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    // Merged descendants + Button role (v1.2.20): TalkBack used to land on the bare
    // Surface with no label; now it announces the SOS button, and the long-press
    // action combinedClickable already exposes is offered in its actions menu.
    val desc = stringResource(if (sent) R.string.sos_sent else R.string.sos_hold)
    Surface(
        shape = shape, color = Palette.Danger.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Palette.Danger.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clip(shape).combinedClickable(
            onClick = {},
            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onTrigger(); sent = true },
        ).semantics(mergeDescendants = true) { contentDescription = desc; role = Role.Button },
    ) {
        Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(if (sent) R.string.sos_sent else R.string.sos_hold),
                color = Palette.Danger, style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
