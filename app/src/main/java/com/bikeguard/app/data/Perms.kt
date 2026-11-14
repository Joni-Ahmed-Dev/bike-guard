package com.bikeguard.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

object Perms {
    private fun granted(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun hasFine(c: Context) = granted(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasBackground(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun hasNotifications(c: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(c, Manifest.permission.POST_NOTIFICATIONS)

    /** POST_NOTIFICATIONS on Android 13+, or null where no runtime permission exists. */
    fun notificationsPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
    fun ignoringBatteryOptimizations(c: Context) =
        c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
}
