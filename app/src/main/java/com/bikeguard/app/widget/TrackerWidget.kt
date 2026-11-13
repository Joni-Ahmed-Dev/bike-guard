package com.bikeguard.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bikeguard.app.MainActivity
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.tracker.TrackerState
import com.bikeguard.app.tracker.TrackingControl
import com.bikeguard.app.ui.agoText

/** Home-screen widget: status of this phone, tap to start/stop tracking. */
class TrackerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            // The Remote phone never tracks: the widget does nothing there.
            if (context.bikeApp.prefs.isLoggedIn && !context.bikeApp.prefs.isRemote) {
                TrackingControl.set(context, !TrackerState.running.value)
            }
            update(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.bikeguard.app.WIDGET_TOGGLE"

        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TrackerWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }

        private fun build(ctx: Context): RemoteViews {
            val running = TrackerState.running.value
            val fix = TrackerState.lastFix.value
            val rv = RemoteViews(ctx.packageName, R.layout.widget_tracker)
            rv.setTextViewText(R.id.w_status, ctx.getString(if (running) R.string.ring_tracking else R.string.state_stopped))
            rv.setTextColor(R.id.w_status, if (running) 0xFF3DDBD9.toInt() else 0xFF8A97AB.toInt())
            rv.setTextViewText(
                R.id.w_detail,
                if (running && fix != null) "${fix.speedKmh.toInt()} km/h · ${agoText(ctx, fix.timeMs, System.currentTimeMillis())}"
                else ctx.getString(R.string.widget_tap_hint)
            )
            val toggle = PendingIntent.getBroadcast(
                ctx, 11, Intent(ctx, TrackerWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val open = PendingIntent.getActivity(
                ctx, 12, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.w_toggle, toggle)
            rv.setOnClickPendingIntent(R.id.w_title, open)
            return rv
        }
    }
}
