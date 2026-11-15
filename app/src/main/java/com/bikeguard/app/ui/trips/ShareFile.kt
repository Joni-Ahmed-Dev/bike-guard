package com.bikeguard.app.ui.trips

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares an export as a real file (v1.2.20). The old plain-text share pasted the
 * whole GPX/CSV into EXTRA_TEXT, which the share sheet truncates on large trips.
 * A file in the cache dir survives any size; the receiving app gets read access
 * through the FileProvider, and the file is overwritten on the next export of
 * the same name.
 */
fun shareFile(ctx: Context, content: String, fileName: String, mimeType: String) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, fileName).apply { writeText(content) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, null))
}
