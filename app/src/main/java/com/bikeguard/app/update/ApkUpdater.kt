package com.bikeguard.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.bikeguard.app.R
import com.bikeguard.app.bikeApp
import com.bikeguard.app.data.UpdateInfo

/**
 * In-app update download (v1.2.30): the update prompt used to open the
 * GitHub release page in the browser. Now the APK downloads through the
 * system DownloadManager, and when it finishes the receiver opens the
 * installer. The release page stays as the fallback when a release has
 * no APK asset.
 */
object ApkUpdater {
    private const val MIME_APK = "application/vnd.android.package-archive"

    /** Start downloading [info]'s APK. False when there is no APK link. */
    fun startDownload(ctx: Context, info: UpdateInfo): Boolean {
        val apkUrl = info.apkUrl ?: return false
        return try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("${ctx.getString(R.string.app_name)} ${info.version}")
                .setDescription(ctx.getString(R.string.update_downloading))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "bike-guard-${info.version}.apk")
                .setMimeType(MIME_APK)
                .setAllowedOverRoaming(false)
            ctx.bikeApp.prefs.updateDownloadId = dm.enqueue(req)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Called by the receiver when a download finishes. */
    fun onDownloadComplete(ctx: Context, downloadId: Long) {
        val prefs = ctx.bikeApp.prefs
        if (downloadId != prefs.updateDownloadId) return
        prefs.updateDownloadId = 0L
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val status = dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            if (c != null && c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) return
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            // First install from this app: Android sends the user to the
            // "install unknown apps" switch. The finished-download
            // notification stays in the shade; tapping it installs once
            // the switch is on.
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }
        ctx.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_APK)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
