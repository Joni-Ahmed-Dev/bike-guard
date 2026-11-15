package com.bikeguard.app.data

import com.bikeguard.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A newer GitHub Release, when one exists. */
data class UpdateInfo(val version: String, val url: String)

sealed interface UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Failed : UpdateCheckResult
}

/**
 * In-app update check (v1.2.21, review item #23): Actions artifacts expire
 * and need a GitHub login, so Releases are the distribution channel — the
 * workflow already publishes one on every v* tag. This checks the latest
 * release on GitHub (public API, no login) and compares it with the
 * running version. Best-effort only: offline, or no releases published
 * yet (404), both come back as Failed / UpToDate, never a crash.
 * Never throws.
 */
object UpdateChecker {
    private const val LATEST_URL =
        "https://api.github.com/repos/Joni-Ahmed-Dev/bike-guard/releases/latest"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatest(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext UpdateCheckResult.UpToDate
                if (!resp.isSuccessful) return@withContext UpdateCheckResult.Failed
                val body = resp.body?.string() ?: return@withContext UpdateCheckResult.Failed
                val json = Json.parseToJsonElement(body).jsonObject
                val tag = json["tag_name"]?.jsonPrimitive?.content
                    ?: return@withContext UpdateCheckResult.Failed
                val url = json["html_url"]?.jsonPrimitive?.content
                    ?: return@withContext UpdateCheckResult.Failed
                val latest = tag.trimStart('v')
                if (isNewer(latest, BuildConfig.VERSION_NAME)) {
                    UpdateCheckResult.Available(UpdateInfo(latest, url))
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed
        }
    }

    /** "1.2.21" vs "1.2.20": compare segment by segment, numerically. */
    fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
