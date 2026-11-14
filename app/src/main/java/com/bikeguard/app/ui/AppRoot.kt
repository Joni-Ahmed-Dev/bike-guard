package com.bikeguard.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.push.PushRegistrar
import com.bikeguard.app.ui.alerts.AlertsScreen
import com.bikeguard.app.ui.alerts.AlertsViewModel
import com.bikeguard.app.ui.auth.AuthScreen
import com.bikeguard.app.ui.dashboard.DashboardScreen
import com.bikeguard.app.ui.expenses.ExpensesScreen
import com.bikeguard.app.ui.roles.RolePickerScreen
import com.bikeguard.app.ui.settings.SettingsScreen
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.tracker.TrackerScreen
import com.bikeguard.app.ui.trips.TripsScreen
import com.bikeguard.app.ui.viewer.ViewerScreen
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    Crossfade(targetState = vm.loggedIn, label = "root") { loggedIn ->
        if (!loggedIn) AuthScreen(vm)
        else if (vm.role.isEmpty()) RolePickerScreen(vm)
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
            TabItem("alerts", R.string.tab_alerts, Icons.Default.Notifications),
            TabItem("expenses", R.string.tab_expenses, Icons.Default.Receipt),
            TabItem("settings", R.string.tab_settings, Icons.Default.Settings),
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
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
    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            if (item.id == "alerts" && alerts.unread > 0) {
                                BadgedBox(badge = { Badge { Text(alerts.unread.coerceAtMost(99).toString()) } }) {
                                    Icon(item.icon, contentDescription = null)
                                }
                            } else {
                                Icon(item.icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(item.label), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Palette.Ember, selectedTextColor = Palette.Ember,
                            indicatorColor = Palette.Ember.copy(alpha = 0.16f),
                            unselectedIconColor = Palette.Muted, unselectedTextColor = Palette.Muted,
                        )
                    )
                }
            }
        }
    ) { inner ->
        val m = Modifier.padding(bottom = inner.calculateBottomPadding())
        when (tabId) {
            "tracker" -> TrackerScreen(vm, m)
            "dashboard" -> DashboardScreen(m, alerts = alerts)
            "viewer" -> ViewerScreen(m)
            "trips" -> TripsScreen(m)
            "alerts" -> AlertsScreen(m, alerts)
            "expenses" -> ExpensesScreen(m)
            else -> SettingsScreen(vm, m)
        }
    }
}
