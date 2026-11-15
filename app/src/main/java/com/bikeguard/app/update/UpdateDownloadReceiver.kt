package com.bikeguard.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires the installer when the in-app update download finishes (v1.2.30). */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id != -1L) ApkUpdater.onDownloadComplete(ctx, id)
    }
}
