package com.bikeguard.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
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
import com.bikeguard.app.ui.settings.SettingsScreen
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.tracker.TrackerScreen
import com.bikeguard.app.ui.trips.TripsScreen
import com.bikeguard.app.ui.viewer.ViewerScreen
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    Crossfade(targetState = vm.loggedIn, label = "root") { loggedIn ->
        if (loggedIn) HomeScreen(vm) else AuthScreen(vm)
    }
}

private const val TAB_ALERTS = 3

@Composable
private fun HomeScreen(vm: AppViewModel, alerts: AlertsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

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
    LaunchedEffect(tab) { if (tab == TAB_ALERTS) alerts.markSeen() }

    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                val items = listOf(
                    Triple(R.string.tab_tracker, Icons.Default.GpsFixed, 0),
                    Triple(R.string.tab_viewer, Icons.Default.Map, 1),
                    Triple(R.string.tab_trips, Icons.Default.History, 2),
                    Triple(R.string.tab_alerts, Icons.Default.Notifications, TAB_ALERTS),
                    Triple(R.string.tab_settings, Icons.Default.Settings, 4),
                )
                items.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = {
                            if (index == TAB_ALERTS && alerts.unread > 0) {
                                BadgedBox(badge = { Badge { Text(alerts.unread.coerceAtMost(99).toString()) } }) {
                                    Icon(icon, contentDescription = null)
                                }
                            } else {
                                Icon(icon, contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(label), maxLines = 1) },
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
        when (tab) {
            0 -> TrackerScreen(vm, m)
            1 -> ViewerScreen(m)
            2 -> TripsScreen(m)
            TAB_ALERTS -> AlertsScreen(m, alerts)
            else -> SettingsScreen(vm, m)
        }
    }
}
