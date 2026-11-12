package com.bikeguard.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bikeguard.app.R
import com.bikeguard.app.ui.auth.AuthScreen
import com.bikeguard.app.ui.settings.SettingsScreen
import com.bikeguard.app.ui.theme.Palette
import com.bikeguard.app.ui.tracker.TrackerScreen
import com.bikeguard.app.ui.viewer.ViewerScreen

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    Crossfade(targetState = vm.loggedIn, label = "root") { loggedIn ->
        if (loggedIn) HomeScreen(vm) else AuthScreen(vm)
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { vm.ensureTracking() }

    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            NavigationBar(containerColor = Palette.Surface) {
                val items = listOf(
                    Triple(R.string.tab_tracker, Icons.Default.GpsFixed, 0),
                    Triple(R.string.tab_viewer, Icons.Default.Map, 1),
                    Triple(R.string.tab_settings, Icons.Default.Settings, 2),
                )
                items.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label)) },
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
            else -> SettingsScreen(vm, m)
        }
    }
}
