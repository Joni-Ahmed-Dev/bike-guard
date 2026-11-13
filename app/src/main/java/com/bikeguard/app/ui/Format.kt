package com.bikeguard.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.bikeguard.app.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun agoText(ctx: Context, thenMs: Long, nowMs: Long): String {
    val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        s < 5 -> ctx.getString(R.string.ago_now)
        s < 60 -> ctx.getString(R.string.ago_s, s.toInt())
        s < 3600 -> ctx.getString(R.string.ago_m, (s / 60).toInt())
        else -> ctx.getString(R.string.ago_h, (s / 3600).toInt())
    }
}

/** Ticks once per second so "5s ago" labels stay fresh. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    return now
}

fun openBatterySettings(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    try { ctx.startActivity(direct) } catch (e: Exception) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

fun fmtKm(ctx: Context, meters: Double): String = ctx.getString(R.string.unit_km, meters / 1000.0)

fun fmtDuration(ctx: Context, ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) ctx.getString(R.string.dur_hm, min / 60, min % 60) else ctx.getString(R.string.dur_m, min)
}

fun fmtDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
fun fmtTime(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
fun fmtClock(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
fun fmtHourMin(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
