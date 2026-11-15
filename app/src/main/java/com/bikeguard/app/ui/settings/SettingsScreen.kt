package com.bikeguard.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.ui.AppViewModel
import com.bikeguard.app.ui.diagnostics.DiagnosticsScreen
import com.bikeguard.app.ui.onboarding.OnboardingScreen
import com.bikeguard.app.ui.rememberNow
import com.bikeguard.app.ui.theme.Panel

/**
 * Settings, split into focused files in v1.2.20 (the single file was ~36 KB):
 * SettingsRows (shared rows), SettingsRemote (Remote editable sections),
 * SettingsTracker (Tracker read-only sections), SettingsSystem (role-independent
 * sections). This file only holds the layout order and the full-screen sub-screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    val now = rememberNow()

    val isTracker = prefs.isTracker
    val isRemote = prefs.isRemote
    var showDiagnostics by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (isRemote) vm.loadRemoteSettings() }

    // Diagnostics is a full screen of its own: it must NOT be nested inside the
    // scrollable settings Column, or Compose measures its own vertical scroll
    // with infinite height and crashes (tap Diagnostics -> app exits).
    if (showDiagnostics) {
        DiagnosticsScreen(onBack = { showDiagnostics = false }, Modifier.fillMaxSize())
        return
    }
    // Same full-screen rule for the setup checklist.
    if (showOnboarding) {
        OnboardingScreen(onFinish = { showOnboarding = false }, Modifier.fillMaxSize())
        return
    }
    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

        // ---- diagnostics: what is happening inside the app, and a report to the diagnostics dashboard ----
        Panel(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showDiagnostics = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diag_title)) }
        }

        // ---- setup checklist: the first-run wizard, for this (bike) phone only ----
        if (isTracker) {
            Panel(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showOnboarding = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.onb_open)) }
            }
        }

        // ---- language & theme (v1.2.20) ----
        AppearancePanel()

        // ---- sync status (Remote only): did the bike phone apply the settings? ----
        if (isRemote) {
            SyncStatusPanel(vm, now)
        }

        // ---- tracker role: settings are view-only ----
        if (isTracker) {
            TrackerNoticePanel()
            TrackerReadOnlyPanel(vm)
            TrackerSyncTestPanel()
        }

        // ---- editable settings: Remote only ----
        if (isRemote) {
            ModePanel(vm)
            IntervalPanel(vm)
            GuardPanel(vm)
            SafetyPanel(vm)
            ServicePanel(vm)
        }

        // ---- app lock ----
        LockPanel()

        // ---- guard schedule + this phone's Viewer prefs (Remote only) ----
        if (isRemote) {
            SchedulePanel(vm)
            LocalViewerPanel()
        }

        // ---- battery ----
        BatteryPanel()

        // ---- notification sounds (issue #8): one ringtone per channel ----
        SoundsPanel()

        // ---- phone role ----
        RolePanel(vm)

        // ---- account ----
        AccountPanel(vm)
    }
}
