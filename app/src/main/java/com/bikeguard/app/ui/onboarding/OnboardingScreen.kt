package com.bikeguard.app.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.MODE_AUTO
import com.bikeguard.app.data.Perms
import com.bikeguard.app.tracker.AutoMode
import com.bikeguard.app.ui.openAutostartSettings
import com.bikeguard.app.ui.openBatterySettings
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.theme.Panel

private enum class Step { LOCATION, BACKGROUND, ACTIVITY, BATTERY, AUTOSTART }

/** True on Xiaomi-family phones (Xiaomi / Redmi / POCO), where MIUI Autostart exists.
 *  Other phones have no Autostart screen, so the step is left out there. */
private fun isMiuiDevice(): Boolean {
    val m = Build.MANUFACTURER.orEmpty()
    val b = Build.BRAND.orEmpty()
    return m.equals("Xiaomi", ignoreCase = true) ||
        b.equals("Redmi", ignoreCase = true) ||
        b.equals("POCO", ignoreCase = true)
}

/**
 * First-run setup checklist for the bike phone: location, location all the time,
 * physical activity, battery optimisation, and (on MIUI) Autostart — in one place,
 * each with a green check when done and a button to the right system screen when not.
 *
 * Android cannot detect MIUI Autostart, so that step is confirmed by hand.
 * [onFinish] gets true when the user finishes with every step done, false when they
 * skip for now; the caller decides whether to show the wizard again.
 */
@Composable
fun OnboardingScreen(onFinish: (completed: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val prefs = ctx.bikeApp.prefs

    // Re-check every step when the user comes back from system settings.
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bg = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val activity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val autoMode = prefs.mode == MODE_AUTO
    val steps = buildList {
        add(Step.LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Step.BACKGROUND)
        add(Step.ACTIVITY)
        add(Step.BATTERY)
        if (isMiuiDevice()) add(Step.AUTOSTART)
    }
    val done = remember(tick) {
        steps.filter { s ->
            when (s) {
                Step.LOCATION -> Perms.hasFine(ctx)
                Step.BACKGROUND -> Perms.hasBackground(ctx)
                // Physical activity only matters in Auto mode; other modes show the step as not needed.
                Step.ACTIVITY -> !autoMode || AutoMode.hasPermission(ctx)
                Step.BATTERY -> Perms.ignoringBatteryOptimizations(ctx)
                // No API can read MIUI Autostart: the user confirms it by hand.
                Step.AUTOSTART -> prefs.autostartConfirmed
            }
        }.toSet()
    }
    val allDone = done.size == steps.size

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onb_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onb_subtitle), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onb_progress, done.size, steps.size),
            color = Palette.Ember, style = MaterialTheme.typography.labelMedium,
        )
        LinearProgressIndicator(
            progress = { done.size / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = Palette.Ember, trackColor = Palette.Stroke,
        )
        if (allDone) {
            Text(stringResource(R.string.onb_all_done), color = Palette.Signal, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))

        steps.forEach { step ->
            val isDone = step in done
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isDone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        null,
                        tint = if (isDone) Palette.Signal else Palette.Muted,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val (title, body) = when (step) {
                            Step.LOCATION -> R.string.perm_loc_title to R.string.perm_loc_body
                            Step.BACKGROUND -> R.string.perm_bg_title to R.string.perm_bg_body
                            Step.ACTIVITY -> R.string.perm_activity_title to R.string.perm_activity_body
                            Step.BATTERY -> R.string.battery_title to R.string.battery_body
                            Step.AUTOSTART -> R.string.onb_autostart_title to R.string.onb_autostart_body
                        }
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                        if (step == Step.ACTIVITY && !autoMode) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.onb_not_needed), color = Palette.Signal, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (!isDone) {
                    Spacer(Modifier.height(12.dp))
                    when (step) {
                        Step.LOCATION -> Button(
                            onClick = { loc.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.grant)) }
                        Step.BACKGROUND -> Button(
                            onClick = { bg.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.grant)) }
                        Step.ACTIVITY -> Button(
                            onClick = { activity.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.grant)) }
                        Step.BATTERY -> OutlinedButton(
                            onClick = { openBatterySettings(ctx) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.open_settings)) }
                        Step.AUTOSTART -> {
                            OutlinedButton(
                                onClick = { openAutostartSettings(ctx) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.onb_autostart_open)) }
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { prefs.autostartConfirmed = true; tick++ },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.onb_autostart_confirm)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // SOS texts need SEND_SMS, but only when the owner wants them (sosSms
        // defaults off), so this is information, not a gating step: Continue
        // must never force an SMS permission on someone who never wants SOS
        // texts. The Settings safety panel is the other place the gap is
        // warned about (v1.2.19).
        val smsOk = remember(tick) { Perms.hasSms(ctx) }
        val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (smsOk) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null,
                    tint = if (smsOk) Palette.Signal else Palette.Muted,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.perm_sms_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.perm_sms_body), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (!smsOk) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { smsPerm.launch(Manifest.permission.SEND_SMS) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.grant)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { onFinish(true) },
            enabled = allDone,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(stringResource(R.string.continue_label)) }
        TextButton(
            onClick = { onFinish(false) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.perm_skip)) }
    }
}
