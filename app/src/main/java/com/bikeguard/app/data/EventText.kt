package com.bikeguard.app.data

import android.content.Context
import com.bikeguard.app.R
import org.json.JSONObject

/** Human text for an alert type, in the phone's language. */
fun eventTitle(ctx: Context, type: String, dataJson: String?): String {
    val d = runCatching { JSONObject(dataJson ?: "{}") }.getOrDefault(JSONObject())
    return when (type) {
        "motion" -> ctx.getString(R.string.ev_motion)
        "moved" -> ctx.getString(R.string.ev_moved)
        "geofence_exit" -> ctx.getString(R.string.ev_geofence_exit, d.optString("name"))
        "geofence_enter" -> ctx.getString(R.string.ev_geofence_enter, d.optString("name"))
        "tamper_gps_off" -> ctx.getString(R.string.ev_tamper_gps_off)
        "tamper_airplane" -> ctx.getString(R.string.ev_tamper_airplane)
        "charger_unplugged" -> ctx.getString(R.string.ev_charger_unplugged)
        "battery_low" -> ctx.getString(R.string.ev_battery_low)
        "device_rebooted" -> ctx.getString(R.string.ev_device_rebooted)
        "tracker_restarted" -> ctx.getString(R.string.ev_tracker_restarted)
        "speed_limit" -> ctx.getString(R.string.ev_speed_limit, d.optInt("kmh"))
        "crash" -> ctx.getString(R.string.ev_crash)
        "sos" -> ctx.getString(R.string.ev_sos)
        "service_due" -> ctx.getString(R.string.ev_service_due)
        "device_stale" -> ctx.getString(R.string.ev_device_stale)
        "health_warning" -> ctx.getString(R.string.ev_health_warning)
        else -> ctx.getString(R.string.ev_unknown)
    }
}
