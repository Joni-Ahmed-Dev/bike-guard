package com.bikeguard.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.ROLE_TRACKER
import com.bikeguard.app.push.PushRegistrar
import com.bikeguard.app.ui.alerts.AlertsScreen
import com.bikeguard.app.ui.alerts.AlertsViewModel
import com.bikeguard.app.ui.auth.AuthScreen
import com.bikeguard.app.ui.dashboard.DashboardScreen
import com.bikeguard.app.ui.expenses.ExpensesScreen
import com.bikeguard.app.ui.onboarding.OnboardingScreen
import com.bikeguard.app.ui.roles.RolePickerScreen
import com.bikeguard.app.ui.settings.SettingsScreen
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.tracker.TrackerScreen
import com.bikeguard.app.ui.trips.TripsScreen
import com.bikeguard.app.ui.viewer.ViewerScreen
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    val prefs = ctx.bikeApp.prefs
    var onboardingDone by remember { mutableStateOf(prefs.onboardingDone) }
    // Session-only: skipping the wizard hides it until the next launch, when it
    // shows again while setup is still incomplete. Finishing sets onboardingDone.
    var wizardDismissed by remember { mutableStateOf(false) }
    Crossfade(targetState = vm.loggedIn, label = "root") { loggedIn ->
        if (!loggedIn) AuthScreen(vm)
        else if (vm.role.isEmpty()) RolePickerScreen(vm)
        // The setup checklist is for the bike phone only: location all the time,
        // battery and Autostart do not apply to the Remote phone.
        else if (vm.role == ROLE_TRACKER && !onboardingDone && !wizardDismissed) {
            OnboardingScreen(onFinish = { completed ->
                if (completed) { prefs.onboardingDone = true; onboardingDone = true }
                else wizardDismissed = true
            })
        }
        else HomeScreen(vm)
    }
}

private data class TabItem(val id: String, val label: Int, val icon: ImageVector)

@Composable
private fun HomeScreen(vm: AppViewModel, alerts: AlertsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // Tabs by role: the tracker phone only tracks; the Remote phone controls everything.
    val tabs = when {
        vm.role == com.bikeguard.app.data.ROLE_TRACKER -> listOf(
            TabItem("tracker", R.string.tab_tracker, Icons.Default.GpsFixed),
            TabItem("settings", R.string.tab_settings, Icons.Default.Settings),
        )
        else -> listOf(
            TabItem("dashboard", R.string.tab_dashboard, Icons.Default.Dashboard),
            TabItem("viewer", R.string.tab_viewer, Icons.Default.Map),
            TabItem("trips", R.string.tab_trips, Icons.Default.History),
            TabItem("expenses", R.string.tab_expenses, Icons.Default.Receipt),
            TabItem("settings", R.string.tab_settings, Icons.Default.Settings),
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // Alerts left the bottom bar: it opens from the Dashboard bell instead.
    var showAlerts by rememberSaveable { mutableStateOf(false) }
    val tabId = tabs.getOrNull(tab)?.id ?: tabs.first().id

    LaunchedEffect(Unit) {
        vm.ensureTracking()
        PushRegistrar.register(ctx.bikeApp)
    }
    // Keeps the unread badge fresh while the app is on screen.
    LaunchedEffect(Unit) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { alerts.refresh(); delay(30_000) }
        }
    }
    // Newer GitHub Release found by the startup update check (v1.2.21):
    // one dismissible prompt, shown over any tab.
    val update = vm.updateAvailable
    if (update != null) {
        AlertDialog(
            onDismissRequest = vm::dismissUpdate,
            containerColor = Palette.Surface,
            title = { Text(stringResource(R.string.update_available_title, update.version)) },
            text = { Text(stringResource(R.string.update_available_body)) },
            confirmButton = {
                TextButton(onClick = { vm.startUpdateDownload(ctx) }) {
                    Text(stringResource(R.string.update_download))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.update_later)) }
            },
        )
    }
    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            Surface(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                shape = RoundedCornerShape(28.dp),
                color = Palette.Surface,
                shadowElevation = 6.dp,
            ) {
                NavigationBar(containerColor = Color.Transparent) {
                    tabs.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = tab == index && !showAlerts,
                            onClick = { tab = index; showAlerts = false },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF111111), selectedTextColor = Color(0xFF111111),
                                indicatorColor = Color(0xFF91E9AD),
                                unselectedIconColor = Palette.Muted, unselectedTextColor = Palette.Muted,
                            )
                        )
                    }
                }
            }
        }
    ) { inner ->
        val m = Modifier.padding(bottom = inner.calculateBottomPadding())
        // Alerts left the bottom bar: it opens from the Dashboard bell instead.
        if (showAlerts) AlertsScreen(m, alerts)
        else when (tabId) {
            "tracker" -> TrackerScreen(vm, m)
            "dashboard" -> DashboardScreen(m, alerts = alerts, onAlertsClick = { showAlerts = true })
            "viewer" -> ViewerScreen(m)
            "trips" -> TripsScreen(m)
            "expenses" -> ExpensesScreen(m)
            else -> SettingsScreen(vm, m)
        }
    }
}
