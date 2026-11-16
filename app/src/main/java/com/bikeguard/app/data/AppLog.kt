package com.bikeguard.app.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small file-backed log (v1.2.41): a timeline of what the app did, so the
 * owner can read it from the diagnostics report instead of asking for
 * screenshots. Android logcat is not readable by a normal app, so the app
 * keeps its own log. No location is ever written here: only event names,
 * command types and short error strings. The file survives a service
 * restart and a reboot; it is capped, so it never grows without bound. */
object AppLog {
    private const val FILE_NAME = "app-log.txt"
    private const val MAX_BYTES = 64 * 1024L
    private const val TRIM_LINES = 150
    private var file: File? = null
    private val lock = Any()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, FILE_NAME)
    }

    /** One line: HH:mm:ss tag message. Best-effort; never throws. */
    fun i(tag: String, msg: String) {
        val f = file ?: return
        val line = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) +
            " " + tag + " " + msg.take(200)
        synchronized(lock) {
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > MAX_BYTES) {
                    val lines = f.readLines()
                    if (lines.size > TRIM_LINES) {
                        f.writeText(lines.takeLast(TRIM_LINES).joinToString("\n") + "\n")
                    }
                }
            }
        }
    }

    /** The last [max] lines, oldest first, for the report and the UI. */
    fun recent(max: Int = 120): List<String> {
        val f = file ?: return emptyList()
        return runCatching {
            synchronized(lock) { f.readLines().takeLast(max) }
        }.getOrDefault(emptyList())
    }
}
